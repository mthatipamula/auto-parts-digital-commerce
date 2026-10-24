package com.autoparts.account;

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
 * Choreography, like inventory: OrderConfirmed invoices the held amount (it moves to the open balance, due Net 30);
 * OrderFailed / ReservationReleaseRequested releases it. Idempotent, so Pub/Sub redelivery is harmless.
 * A sweeper releases holds whose checkout never finished.
 */
@Component
public class AccountEvents implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountEvents.class);
    static final String SUBSCRIPTION = "account.order-events";
    private final AccountStore store;
    private final PubSubTemplate pubsub;
    private final PubSubSetup pubsubSetup;
    private final SpannerSetup spanner;
    private final ObjectMapper json;
    private volatile boolean ready;

    public AccountEvents(AccountStore store, PubSubTemplate pubsub, PubSubSetup pubsubSetup, SpannerSetup spanner, ObjectMapper json) {
        this.store = store;
        this.pubsub = pubsub;
        this.pubsubSetup = pubsubSetup;
        this.spanner = spanner;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        spanner.ensure(AccountStore.DDL);
        if (store.empty()) {
            store.seed();
            log.info("Seeded Pro accounts");
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
        ready = true;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void releaseStaleHolds() {
        if (!ready) return;
        for (String orderId : store.staleHolds(30)) {
            store.finish(orderId, false);
            log.info("released stale credit hold for order {}", orderId);
        }
    }
}
