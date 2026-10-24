package com.autoparts.common.events;

import java.time.Instant;
import java.util.List;

/**
 * Events on the order-events topic (ordering key = orderId, so one order's events arrive in order).
 * Consumers must be idempotent: Pub/Sub delivers at least once, so eventId is used for de-duplication.
 */
public record OrderEvent(String eventId, String type, String orderId, String customerId, String storeId,
                         String status, long totalCents, List<Line> items, String reason, Instant occurredAt,
                         String accountId, String poNumber, String placedBy, String fulfillment, String paymentMethod) {

    public static final String TOPIC = "order-events";

    public static final String CREATED = "OrderCreated";
    public static final String CONFIRMED = "OrderConfirmed";
    public static final String FAILED = "OrderFailed";
    /** Fallback when the orchestrator can't release a reservation synchronously: inventory releases it on this. */
    public static final String RELEASE_REQUESTED = "ReservationReleaseRequested";

    public record Line(String sku, String name, int quantity, long unitPriceCents) {}
}
