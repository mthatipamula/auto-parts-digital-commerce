package com.autoparts.common.gcp;

import com.google.cloud.spring.pubsub.PubSubAdmin;
import com.google.pubsub.v1.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates topics and subscriptions when running against the emulator. In GCP they come from Terraform, along with
 * dead-letter topics and retry policies.
 */
public class PubSubSetup {
    private static final Logger log = LoggerFactory.getLogger(PubSubSetup.class);
    private final PubSubAdmin admin;
    private final boolean create;

    public PubSubSetup(PubSubAdmin admin, boolean create) {
        this.admin = admin;
        this.create = create;
    }

    public void topic(String topic) {
        if (create && admin.getTopic(topic) == null) {
            admin.createTopic(topic);
            log.info("Created topic {}", topic);
        }
    }

    /** Ordered delivery: events with the same ordering key (the order ID) arrive in publish order. */
    public void orderedSubscription(String subscription, String topic) {
        topic(topic);
        if (create && admin.getSubscription(subscription) == null) {
            admin.createSubscription(Subscription.newBuilder().setName(subscription).setTopic(topic)
                    .setEnableMessageOrdering(true).setAckDeadlineSeconds(30));
            log.info("Created subscription {} on {}", subscription, topic);
        }
    }
}
