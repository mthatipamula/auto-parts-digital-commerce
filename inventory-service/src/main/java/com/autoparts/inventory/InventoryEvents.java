package com.autoparts.inventory;

import com.autoparts.common.events.OrderEvent;
import com.autoparts.common.gcp.PubSubSetup;
import com.autoparts.common.gcp.SpannerSetup;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Choreography side of checkout: inventory reacts to order events on its own.
 * - OrderConfirmed: the held stock leaves the store (COMMITTED).
 * - OrderFailed / ReservationReleaseRequested: the hold is released.
 * Handlers are idempotent (status transitions only happen from HELD), so Pub/Sub redelivery is harmless.
 * A sweeper releases holds whose saga never finished (e.g. the checkout pod died mid-checkout).
 */
@Component
public class InventoryEvents implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(InventoryEvents.class);
    static final String SUBSCRIPTION = "inventory.order-events";
    private final InventoryStore store;
    private final PubSubTemplate pubsub;
    private final PubSubSetup pubsubSetup;
    private final SpannerSetup spanner;
    private final ObjectMapper json;

    public InventoryEvents(InventoryStore store, PubSubTemplate pubsub, PubSubSetup pubsubSetup, SpannerSetup spanner, ObjectMapper json) {
        this.store = store;
        this.pubsub = pubsub;
        this.pubsubSetup = pubsubSetup;
        this.spanner = spanner;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        spanner.ensure(InventoryStore.DDL);
        if (store.empty()) {
            store.seed();
            log.info("Seeded stores and stock");
        }
        pubsubSetup.orderedSubscription(SUBSCRIPTION, OrderEvent.TOPIC);
        pubsub.subscribe(SUBSCRIPTION, msg -> {
            try {
                OrderEvent e = json.readValue(msg.getPubsubMessage().getData().toByteArray(), OrderEvent.class);
                switch (e.type()) {
                    case OrderEvent.CONFIRMED -> store.finish(e.orderId(), true);
                    case OrderEvent.FAILED, OrderEvent.RELEASE_REQUESTED -> store.finish(e.orderId(), false);
                    default -> { }
                }
                msg.ack();
            } catch (Exception ex) {
                log.warn("order event failed; will be redelivered: {}", ex.toString());
                msg.nack();
            }
        });
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void releaseExpiredHolds() {
        for (String orderId : store.expiredHolds(100)) {
            store.finish(orderId, false);
            log.info("released expired hold for order {}", orderId);
        }
    }
}
