package com.autoparts.checkout;

import com.autoparts.common.events.OrderEvent;
import com.autoparts.common.gcp.PubSubSetup;
import com.autoparts.common.gcp.SpannerSetup;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * - Outbox relay: publishes committed events to Pub/Sub in order (ordering key = order ID), at least once.
 * - CQRS projection: builds each customer's order history in Valkey from the same events. The query side is fast
 *   and denormalized; it's eventually consistent with Spanner (typically well under a second behind).
 */
@Component
public class Messaging implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(Messaging.class);
    static final String PROJECTION_SUBSCRIPTION = "checkout.order-history";
    private static final List<String> STATUS_ORDER = List.of("PENDING", "STOCK_RESERVED", "CONFIRMED", "REJECTED", "FAILED", "PAYMENT_FAILED");

    private final OrderStore orders;
    private final PubSubTemplate pubsub;
    private final PubSubSetup pubsubSetup;
    private final SpannerSetup spanner;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    /** The relay must not touch Spanner before setup: a client that sees "instance not found" stays invalid. */
    private volatile boolean ready;

    public Messaging(OrderStore orders, PubSubTemplate pubsub, PubSubSetup pubsubSetup, SpannerSetup spanner,
                     StringRedisTemplate redis, ObjectMapper json) {
        this.orders = orders;
        this.pubsub = pubsub;
        this.pubsubSetup = pubsubSetup;
        this.spanner = spanner;
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        spanner.ensure(OrderStore.DDL);
        pubsubSetup.orderedSubscription(PROJECTION_SUBSCRIPTION, OrderEvent.TOPIC);
        pubsub.subscribe(PROJECTION_SUBSCRIPTION, msg -> {
            try {
                project(json.readValue(msg.getPubsubMessage().getData().toByteArray(), OrderEvent.class));
                msg.ack();
            } catch (Exception e) {
                log.warn("projection failed; will be redelivered: {}", e.toString());
                msg.nack();
            }
        });
        ready = true;
    }

    @Scheduled(fixedDelay = 500)
    public void relay() {
        if (!ready) return;
        for (OrderStore.OutboxRow row : orders.unpublished(100)) {
            try {
                pubsub.publish(row.topic(), PubsubMessage.newBuilder().setData(ByteString.copyFrom(row.payload(), StandardCharsets.UTF_8))
                        .setOrderingKey(row.orderingKey()).build()).get(10, TimeUnit.SECONDS);
                orders.markPublished(row.eventId());
            } catch (Exception e) {
                log.warn("publish failed for {}, will retry: {}", row.eventId(), e.toString());
                return;  // keep per-order ordering: stop at the first failure
            }
        }
    }

    /** Idempotent and order-tolerant: an event is applied only if it moves the order forward. */
    void project(OrderEvent e) {
        String viewKey = "orderview:" + e.orderId();
        String current = redis.<String, String>opsForHash().get(viewKey, "status");
        if (current != null && rank(current) >= rank(e.status()) && !e.type().equals(OrderEvent.CREATED)) {
            if (rank(current) > rank(e.status())) return;
        }
        if (current != null && e.type().equals(OrderEvent.CREATED)) return;  // a later event already arrived
        Map<String, String> view = new java.util.HashMap<>(Map.of("orderId", e.orderId(), "status", e.status(), "storeId", e.storeId(),
                "totalCents", Long.toString(e.totalCents()), "itemCount", Integer.toString(e.items().stream().mapToInt(OrderEvent.Line::quantity).sum()),
                "reason", e.reason() == null ? "" : e.reason(), "updatedAt", e.occurredAt().toString()));
        view.put("poNumber", e.poNumber() == null ? "" : e.poNumber());
        view.put("placedBy", e.placedBy() == null ? "" : e.placedBy());
        view.put("fulfillment", e.fulfillment() == null ? "PICKUP" : e.fulfillment());
        view.put("paymentMethod", e.paymentMethod() == null ? "CARD" : e.paymentMethod());
        redis.opsForHash().putAll(viewKey, view);
        redis.expire(viewKey, Duration.ofDays(90));
        double score = -e.occurredAt().toEpochMilli() / 1000.0;
        redis.opsForZSet().add("orders:" + e.customerId(), e.orderId(), score);
        if (e.accountId() != null) redis.opsForZSet().add("account-orders:" + e.accountId(), e.orderId(), score);  // owner's view
    }

    private static int rank(String status) {
        int i = STATUS_ORDER.indexOf(status);
        return i < 0 ? 0 : i;
    }
}
