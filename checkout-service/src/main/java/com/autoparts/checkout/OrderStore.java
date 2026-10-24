package com.autoparts.checkout;

import com.autoparts.common.events.OrderEvent;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The write model. Every state change writes the order, a saga step and an outbox event in ONE Spanner
 * transaction, so the event can never be lost or published for a change that didn't commit.
 * Keys are UUIDs, not timestamps or sequences: monotonically increasing keys would send every insert to the same
 * Spanner split (a hotspot).
 */
@Repository
public class OrderStore {

    public static final List<String> DDL = List.of(
            """
            CREATE TABLE orders (
              order_id STRING(36) NOT NULL, customer_id STRING(64) NOT NULL, store_id STRING(20) NOT NULL,
              status STRING(24) NOT NULL, total_cents INT64 NOT NULL, items JSON NOT NULL, payment_id STRING(40),
              failure_reason STRING(500),
              account_id STRING(20), po_number STRING(40), placed_by STRING(100), fulfillment STRING(10) NOT NULL,
              payment_method STRING(10) NOT NULL, delivery_address STRING(300),
              created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
              updated_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
            ) PRIMARY KEY (order_id)""",
            "CREATE INDEX orders_by_customer ON orders (customer_id, created_at DESC)",
            "CREATE INDEX orders_by_account ON orders (account_id, created_at DESC)",
            """
            CREATE TABLE saga_steps (
              order_id STRING(36) NOT NULL, seq INT64 NOT NULL, step STRING(40) NOT NULL, outcome STRING(20) NOT NULL,
              detail STRING(1000), occurred_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
            ) PRIMARY KEY (order_id, seq), INTERLEAVE IN PARENT orders ON DELETE CASCADE""",
            """
            CREATE TABLE outbox (
              event_id STRING(36) NOT NULL, topic STRING(80) NOT NULL, ordering_key STRING(64) NOT NULL, payload JSON NOT NULL,
              published BOOL NOT NULL, created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
            ) PRIMARY KEY (event_id)""",
            "CREATE INDEX outbox_unpublished ON outbox (published, created_at)",
            """
            CREATE TABLE idempotency_keys (
              idem_key STRING(100) NOT NULL, customer_id STRING(64) NOT NULL, order_id STRING(36) NOT NULL,
              created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
            ) PRIMARY KEY (customer_id, idem_key)""");

    public record Line(String sku, String name, int quantity, long unitPriceCents, long listPriceCents) {}

    /** How the order is bought: B2C card + pickup, or B2B on account (PO number, delivery to the shop). */
    public record Terms(String accountId, String poNumber, String placedBy, String fulfillment, String paymentMethod,
                        String deliveryAddress) {}

    public record Step(long seq, String step, String outcome, String detail, Instant at) {}

    public record Order(String orderId, String customerId, String storeId, String status, long totalCents, List<Line> items,
                        String paymentId, String failureReason, Instant createdAt, Instant updatedAt, List<Step> steps,
                        Terms terms) {}

    public record OutboxRow(String eventId, String topic, String orderingKey, String payload) {}

    private final Supplier<DatabaseClient> db;
    private final ObjectMapper json;

    public OrderStore(Supplier<DatabaseClient> db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** Creates the order (or returns the one this idempotency key already created). */
    public Order create(String customerId, String idemKey, String storeId, List<Line> lines, Terms terms) {
        String existing = db.get().readWriteTransaction().run(tx -> {
            Struct k = tx.readRow("idempotency_keys", Key.of(customerId, idemKey), List.of("order_id"));
            if (k != null) return k.getString("order_id");
            String orderId = UUID.randomUUID().toString();
            long total = lines.stream().mapToLong(l -> l.unitPriceCents() * l.quantity()).sum();
            tx.buffer(List.of(
                    Mutation.newInsertBuilder("orders").set("order_id").to(orderId).set("customer_id").to(customerId)
                            .set("store_id").to(storeId).set("status").to("PENDING").set("total_cents").to(total)
                            .set("items").to(Value.json(json.writeValueAsString(lines)))
                            .set("account_id").to(terms.accountId()).set("po_number").to(terms.poNumber())
                            .set("placed_by").to(terms.placedBy()).set("fulfillment").to(terms.fulfillment())
                            .set("payment_method").to(terms.paymentMethod()).set("delivery_address").to(terms.deliveryAddress())
                            .set("created_at").to(Value.COMMIT_TIMESTAMP).set("updated_at").to(Value.COMMIT_TIMESTAMP).build(),
                    Mutation.newInsertBuilder("idempotency_keys").set("idem_key").to(idemKey).set("customer_id").to(customerId)
                            .set("order_id").to(orderId).set("created_at").to(Value.COMMIT_TIMESTAMP).build()));
            step(tx, orderId, 1, "CREATE_ORDER", "OK", null);
            outbox(tx, event(OrderEvent.CREATED, orderId, customerId, storeId, "PENDING", total, lines, null, terms));
            return orderId;
        });
        return get(existing);
    }

    /** The order an earlier request with this Idempotency-Key created, if any. */
    public Order byIdempotencyKey(String customerId, String idemKey) {
        Struct k = db.get().singleUse().readRow("idempotency_keys", Key.of(customerId, idemKey), List.of("order_id"));
        return k == null ? null : get(k.getString("order_id"));
    }

    /** Records a saga step and optionally moves the order to a new status, with its event, atomically. */
    public void advance(String orderId, String stepName, String outcome, String detail, String newStatus, String eventType,
                        String paymentId, String reason) {
        db.get().readWriteTransaction().run(tx -> {
            Struct o = tx.readRow("orders", Key.of(orderId), List.of("customer_id", "store_id", "total_cents", "items", "status",
                    "account_id", "po_number", "placed_by", "fulfillment", "payment_method", "delivery_address"));
            long seq = nextSeq(tx, orderId);
            step(tx, orderId, seq, stepName, outcome, detail);
            if (newStatus != null) {
                Mutation.WriteBuilder m = Mutation.newUpdateBuilder("orders").set("order_id").to(orderId).set("status").to(newStatus)
                        .set("updated_at").to(Value.COMMIT_TIMESTAMP);
                if (paymentId != null) m.set("payment_id").to(paymentId);
                if (reason != null) m.set("failure_reason").to(reason.length() > 500 ? reason.substring(0, 500) : reason);
                tx.buffer(m.build());
            }
            if (eventType != null) {
                List<Line> lines = json.readValue(o.getJson("items"), new TypeReference<List<Line>>() {});
                outbox(tx, event(eventType, orderId, o.getString("customer_id"), o.getString("store_id"),
                        newStatus != null ? newStatus : o.getString("status"), o.getLong("total_cents"), lines, reason, terms(o)));
            }
            return null;
        });
    }

    public Order get(String orderId) {
        Struct o = db.get().singleUse().readRow("orders", Key.of(orderId), List.of("order_id", "customer_id", "store_id", "status",
                "total_cents", "items", "payment_id", "failure_reason", "created_at", "updated_at",
                "account_id", "po_number", "placed_by", "fulfillment", "payment_method", "delivery_address"));
        if (o == null) return null;
        List<Step> steps = new ArrayList<>();
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.newBuilder(
                "SELECT seq, step, outcome, detail, occurred_at FROM saga_steps WHERE order_id = @o ORDER BY seq").bind("o").to(orderId).build())) {
            while (rs.next()) {
                steps.add(new Step(rs.getLong(0), rs.getString(1), rs.getString(2), rs.isNull(3) ? null : rs.getString(3),
                        rs.getTimestamp(4).toSqlTimestamp().toInstant()));
            }
        }
        return new Order(o.getString("order_id"), o.getString("customer_id"), o.getString("store_id"), o.getString("status"),
                o.getLong("total_cents"), json.readValue(o.getJson("items"), new TypeReference<List<Line>>() {}),
                o.isNull("payment_id") ? null : o.getString("payment_id"), o.isNull("failure_reason") ? null : o.getString("failure_reason"),
                o.getTimestamp("created_at").toSqlTimestamp().toInstant(), o.getTimestamp("updated_at").toSqlTimestamp().toInstant(), steps,
                terms(o));
    }

    public List<OutboxRow> unpublished(int limit) {
        List<OutboxRow> out = new ArrayList<>();
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.newBuilder(
                "SELECT event_id, topic, ordering_key, payload FROM outbox@{FORCE_INDEX=outbox_unpublished} "
                        + "WHERE published = false ORDER BY created_at LIMIT @n").bind("n").to(limit).build())) {
            while (rs.next()) out.add(new OutboxRow(rs.getString(0), rs.getString(1), rs.getString(2), rs.getJson(3)));
        }
        return out;
    }

    public void markPublished(String eventId) {
        db.get().write(List.of(Mutation.newUpdateBuilder("outbox").set("event_id").to(eventId).set("published").to(true).build()));
    }

    private static long nextSeq(TransactionContext tx, String orderId) {
        try (ResultSet rs = tx.executeQuery(Statement.newBuilder("SELECT COALESCE(MAX(seq), 0) + 1 FROM saga_steps WHERE order_id = @o")
                .bind("o").to(orderId).build())) {
            rs.next();
            return rs.getLong(0);
        }
    }

    private static void step(TransactionContext tx, String orderId, long seq, String step, String outcome, String detail) {
        tx.buffer(Mutation.newInsertBuilder("saga_steps").set("order_id").to(orderId).set("seq").to(seq).set("step").to(step)
                .set("outcome").to(outcome).set("detail").to(detail == null ? null : detail.length() > 1000 ? detail.substring(0, 1000) : detail)
                .set("occurred_at").to(Value.COMMIT_TIMESTAMP).build());
    }

    private OrderEvent event(String type, String orderId, String customerId, String storeId, String status, long total,
                             List<Line> lines, String reason, Terms t) {
        return new OrderEvent(UUID.randomUUID().toString(), type, orderId, customerId, storeId, status, total,
                lines.stream().map(l -> new OrderEvent.Line(l.sku(), l.name(), l.quantity(), l.unitPriceCents())).toList(),
                reason, Instant.now(), t.accountId(), t.poNumber(), t.placedBy(), t.fulfillment(), t.paymentMethod());
    }

    private static Terms terms(Struct o) {
        java.util.function.Function<String, String> s = c -> o.isNull(c) ? null : o.getString(c);
        return new Terms(s.apply("account_id"), s.apply("po_number"), s.apply("placed_by"), s.apply("fulfillment"),
                s.apply("payment_method"), s.apply("delivery_address"));
    }

    private void outbox(TransactionContext tx, OrderEvent e) {
        tx.buffer(Mutation.newInsertBuilder("outbox").set("event_id").to(e.eventId()).set("topic").to(OrderEvent.TOPIC)
                .set("ordering_key").to(e.orderId()).set("payload").to(Value.json(json.writeValueAsString(e)))
                .set("published").to(false).set("created_at").to(Value.COMMIT_TIMESTAMP).build());
    }
}
