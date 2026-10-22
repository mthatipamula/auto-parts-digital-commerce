package com.autoparts.inventory;

import com.autoparts.common.ApiErrors.ApiException;
import com.autoparts.common.seed.DemoCatalog;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Store stock in Spanner. Reservations are checked and applied inside one read-write transaction, so two customers
 * can't both take the last unit (Spanner serializes the conflicting transactions; the loser retries automatically).
 */
@Repository
public class InventoryStore {

    public static final List<String> DDL = List.of(
            "CREATE TABLE stores (store_id STRING(20) NOT NULL, name STRING(120) NOT NULL, city STRING(80), state STRING(2)) PRIMARY KEY (store_id)",
            """
            CREATE TABLE stock (
              store_id STRING(20) NOT NULL, sku STRING(40) NOT NULL, on_hand INT64 NOT NULL, reserved INT64 NOT NULL
            ) PRIMARY KEY (store_id, sku), INTERLEAVE IN PARENT stores ON DELETE CASCADE""",
            """
            CREATE TABLE reservations (
              order_id STRING(36) NOT NULL, store_id STRING(20) NOT NULL, status STRING(12) NOT NULL, items JSON NOT NULL,
              created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true), expires_at TIMESTAMP NOT NULL,
              updated_at TIMESTAMP OPTIONS (allow_commit_timestamp = true)
            ) PRIMARY KEY (order_id)""",
            "CREATE INDEX reservations_by_status ON reservations (status, expires_at)");

    public record Line(String sku, int quantity) {}

    public record Reservation(String orderId, String storeId, String status, List<Line> items) {}

    public record Shortage(String sku, long requested, long available) {}

    public static class OutOfStock extends ApiException {
        public final List<Shortage> shortages;

        OutOfStock(List<Shortage> shortages) {
            super(HttpStatus.CONFLICT, "Not enough stock: " + shortages);
            this.shortages = shortages;
        }
    }

    private final Supplier<DatabaseClient> db;
    private final ObjectMapper json;

    public InventoryStore(Supplier<DatabaseClient> db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public boolean empty() {
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.of("SELECT COUNT(*) FROM stores"))) {
            return rs.next() && rs.getLong(0) == 0;
        }
    }

    public void seed() {
        List<Mutation> m = new ArrayList<>();
        for (DemoCatalog.Store s : DemoCatalog.stores()) {
            m.add(Mutation.newInsertOrUpdateBuilder("stores").set("store_id").to(s.id()).set("name").to(s.name())
                    .set("city").to(s.city()).set("state").to(s.state()).build());
            for (DemoCatalog.Part p : DemoCatalog.parts()) {
                m.add(Mutation.newInsertOrUpdateBuilder("stock").set("store_id").to(s.id()).set("sku").to(p.sku())
                        .set("on_hand").to(DemoCatalog.stock(s.id(), p.sku())).set("reserved").to(0).build());
            }
        }
        db.get().write(m);
    }

    public List<Map<String, Object>> stores() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.of("SELECT store_id, name, city, state FROM stores ORDER BY store_id"))) {
            while (rs.next()) out.add(Map.of("storeId", rs.getString(0), "name", rs.getString(1), "city", rs.getString(2), "state", rs.getString(3)));
        }
        return out;
    }

    /** Available-to-sell per SKU at a store: on hand minus already reserved. Stale reads are fine for display. */
    public Map<String, Long> availability(String storeId, List<String> skus) {
        Map<String, Long> out = new LinkedHashMap<>();
        skus.forEach(s -> out.put(s, 0L));
        Statement st = Statement.newBuilder("SELECT sku, on_hand - reserved FROM stock WHERE store_id = @s AND sku IN UNNEST(@skus)")
                .bind("s").to(storeId).bind("skus").toStringArray(skus).build();
        try (ResultSet rs = db.get().singleUse(com.google.cloud.spanner.TimestampBound.ofMaxStaleness(10, java.util.concurrent.TimeUnit.SECONDS))
                .executeQuery(st)) {
            while (rs.next()) out.put(rs.getString(0), Math.max(0, rs.getLong(1)));
        }
        return out;
    }

    /** All-or-nothing reservation, idempotent by order ID. */
    public Reservation reserve(String orderId, String storeId, List<Line> lines, long holdMinutes) {
        return db.get().readWriteTransaction().run(tx -> {
            Struct existing = tx.readRow("reservations", Key.of(orderId), List.of("order_id", "store_id", "status", "items"));
            if (existing != null) return toReservation(existing);          // retry of the same order: same answer
            List<Shortage> short_ = new ArrayList<>();
            List<Mutation> updates = new ArrayList<>();
            for (Line l : lines) {
                Struct row = tx.readRow("stock", Key.of(storeId, l.sku()), List.of("on_hand", "reserved"));
                long available = row == null ? 0 : row.getLong("on_hand") - row.getLong("reserved");
                if (available < l.quantity()) {
                    short_.add(new Shortage(l.sku(), l.quantity(), Math.max(0, available)));
                } else {
                    updates.add(Mutation.newUpdateBuilder("stock").set("store_id").to(storeId).set("sku").to(l.sku())
                            .set("reserved").to(row.getLong("reserved") + l.quantity()).build());
                }
            }
            if (!short_.isEmpty()) throw new OutOfStock(short_);           // aborts the transaction; nothing changes
            updates.add(Mutation.newInsertBuilder("reservations").set("order_id").to(orderId).set("store_id").to(storeId)
                    .set("status").to("HELD").set("items").to(Value.json(json.writeValueAsString(lines)))
                    .set("created_at").to(Value.COMMIT_TIMESTAMP)
                    .set("expires_at").to(Timestamp.ofTimeSecondsAndNanos(Timestamp.now().getSeconds() + holdMinutes * 60, 0))
                    .build());
            tx.buffer(updates);
            return new Reservation(orderId, storeId, "HELD", lines);
        });
    }

    /** HELD -> RELEASED (stock returned) or HELD -> COMMITTED (stock leaves the store). Idempotent. */
    public Reservation finish(String orderId, boolean commit) {
        return db.get().readWriteTransaction().run(tx -> {
            Struct r = tx.readRow("reservations", Key.of(orderId), List.of("order_id", "store_id", "status", "items"));
            if (r == null) return null;
            Reservation res = toReservation(r);
            if (!res.status().equals("HELD")) return res;                   // already finished: nothing to do
            for (Line l : res.items()) adjust(tx, res.storeId(), l, commit);
            tx.buffer(Mutation.newUpdateBuilder("reservations").set("order_id").to(orderId)
                    .set("status").to(commit ? "COMMITTED" : "RELEASED").set("updated_at").to(Value.COMMIT_TIMESTAMP).build());
            return new Reservation(orderId, res.storeId(), commit ? "COMMITTED" : "RELEASED", res.items());
        });
    }

    private static void adjust(TransactionContext tx, String storeId, Line l, boolean commit) {
        Struct row = tx.readRow("stock", Key.of(storeId, l.sku()), List.of("on_hand", "reserved"));
        if (row == null) return;
        Mutation.WriteBuilder m = Mutation.newUpdateBuilder("stock").set("store_id").to(storeId).set("sku").to(l.sku())
                .set("reserved").to(Math.max(0, row.getLong("reserved") - l.quantity()));
        if (commit) m.set("on_hand").to(Math.max(0, row.getLong("on_hand") - l.quantity()));
        tx.buffer(m.build());
    }

    public List<String> expiredHolds(int limit) {
        List<String> ids = new ArrayList<>();
        Statement st = Statement.newBuilder("SELECT order_id FROM reservations@{FORCE_INDEX=reservations_by_status} "
                + "WHERE status = 'HELD' AND expires_at < CURRENT_TIMESTAMP() LIMIT @n").bind("n").to(limit).build();
        try (ResultSet rs = db.get().singleUse().executeQuery(st)) {
            while (rs.next()) ids.add(rs.getString(0));
        }
        return ids;
    }

    public Reservation get(String orderId) {
        Struct r = db.get().singleUse().readRow("reservations", Key.of(orderId), List.of("order_id", "store_id", "status", "items"));
        return r == null ? null : toReservation(r);
    }

    private Reservation toReservation(Struct r) {
        List<Line> items = json.readValue(r.getJson("items"), new TypeReference<List<Line>>() {});
        return new Reservation(r.getString("order_id"), r.getString("store_id"), r.getString("status"), items);
    }

    /** Exceptions thrown inside a Spanner transaction come back wrapped; surface our own business errors as-is. */
    public static RuntimeException unwrap(RuntimeException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof ApiException api) return api;
            t = t.getCause();
        }
        return e;
    }
}
