package com.autoparts.checkout;

import com.autoparts.checkout.Downstream.Business;
import com.autoparts.checkout.OrderStore.Line;
import com.autoparts.checkout.OrderStore.Order;
import com.autoparts.common.ApiErrors.ApiException;
import com.autoparts.common.events.OrderEvent;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Checkout as an orchestrated saga. Each step is recorded; each failure after stock is held is compensated.
 *
 *   CREATE_ORDER -> RESERVE_STOCK -> AUTHORIZE_PAYMENT -> CONFIRM
 *                        |                  |
 *                   out of stock      declined / payment down
 *                        v                  v
 *                    REJECTED       RELEASE_STOCK (compensation) -> PAYMENT_FAILED
 *
 * Safety nets: every remote call is idempotent by order ID (retries can't double-reserve or double-charge);
 * if compensation itself fails, a ReservationReleaseRequested event lets inventory release the stock on its own;
 * and inventory expires holds that no saga finished.
 */
@Service
public class CheckoutSaga {
    private static final Logger log = LoggerFactory.getLogger(CheckoutSaga.class);
    private final OrderStore orders;
    private final Cart cart;
    private final Downstream downstream;

    public CheckoutSaga(OrderStore orders, Cart cart, Downstream downstream) {
        this.orders = orders;
        this.cart = cart;
        this.downstream = downstream;
    }

    record Priced(String sku, String name, long priceCents) {}

    public Order checkout(String customerId, String idemKey, String storeId) {
        // A retry (double-click, network timeout) gets the original result, even though the cart is now empty.
        Order previous = orders.byIdempotencyKey(customerId, idemKey);
        if (previous != null) return previous;

        Map<String, Integer> items = cart.items(customerId);
        if (items.isEmpty()) throw ApiException.badRequest("Your cart is empty");

        // Price from the catalog (the authority), never from the browser.
        List<Priced> prices = guarded("catalog", () -> downstream.call("catalog", c -> c.post().uri("/api/catalog/prices")
                .body(Map.of("skus", List.copyOf(items.keySet()))).retrieve().body(new ParameterizedTypeReference<List<Priced>>() {})));
        Map<String, Priced> bySku = prices.stream().collect(java.util.stream.Collectors.toMap(Priced::sku, p -> p));
        List<Line> lines = items.entrySet().stream().map(e -> {
            Priced p = bySku.get(e.getKey());
            if (p == null) throw ApiException.badRequest("Part " + e.getKey() + " is no longer sold");
            return new Line(p.sku(), p.name(), e.getValue(), p.priceCents());
        }).toList();

        Order order = orders.create(customerId, idemKey, storeId, lines);
        if (!order.status().equals("PENDING")) return order;                // idempotent replay of a finished checkout
        String id = order.orderId();

        // 1. Reserve stock at the pickup store.
        try {
            downstream.call("inventory", c -> c.post().uri("/api/inventory/reservations")
                    .body(Map.of("orderId", id, "storeId", storeId, "items",
                            lines.stream().map(l -> Map.of("sku", l.sku(), "quantity", l.quantity())).toList()))
                    .retrieve().toBodilessEntity());
            orders.advance(id, "RESERVE_STOCK", "OK", null, "STOCK_RESERVED", null, null, null);
        } catch (Business e) {
            orders.advance(id, "RESERVE_STOCK", "REJECTED", e.body, "REJECTED", OrderEvent.FAILED, null, "Out of stock at this store");
            return orders.get(id);
        } catch (RuntimeException e) {
            // Unknown outcome (timeout, open circuit): the hold may exist, so ask inventory to release it via an event.
            orders.advance(id, "RESERVE_STOCK", "ERROR", describe(e), "FAILED", OrderEvent.FAILED, null, "Inventory unavailable, please retry");
            return orders.get(id);
        }

        // 2. Authorize payment.
        String paymentId;
        try {
            Map<?, ?> auth = downstream.call("payment", c -> c.post().uri("/api/payments/authorizations")
                    .body(Map.of("orderId", id, "amountCents", order.totalCents())).retrieve().body(Map.class));
            paymentId = String.valueOf(auth.get("paymentId"));
        } catch (RuntimeException e) {
            boolean declined = e instanceof Business;
            orders.advance(id, "AUTHORIZE_PAYMENT", declined ? "DECLINED" : "ERROR", describe(e), null, null, null, null);
            compensate(id);
            orders.advance(id, "FINISH", "FAILED", null, "PAYMENT_FAILED", OrderEvent.FAILED, null,
                    declined ? "Payment declined" : "Payment service unavailable, please retry");
            return orders.get(id);
        }

        // 3. Confirm. Inventory commits the held stock when it sees OrderConfirmed (choreography).
        orders.advance(id, "AUTHORIZE_PAYMENT", "OK", paymentId, "CONFIRMED", OrderEvent.CONFIRMED, paymentId, null);
        cart.clear(customerId);
        return orders.get(id);
    }

    /** Compensation: release the stock hold. If inventory can't be reached, fall back to an event. */
    private void compensate(String orderId) {
        try {
            downstream.call("inventory", c -> c.delete().uri("/api/inventory/reservations/{id}", orderId).retrieve().toBodilessEntity());
            orders.advance(orderId, "RELEASE_STOCK", "OK", null, null, null, null, null);
        } catch (RuntimeException e) {
            log.warn("compensation failed for {}, falling back to an event: {}", orderId, e.toString());
            orders.advance(orderId, "RELEASE_STOCK", "DEFERRED", describe(e), null, OrderEvent.RELEASE_REQUESTED, null, null);
        }
    }

    private static <T> T guarded(String dependency, java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (CallNotPermittedException | BulkheadFullException | Downstream.Transient e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The " + dependency + " service is unavailable; please try again shortly");
        }
    }

    private static String describe(RuntimeException e) {
        if (e instanceof CallNotPermittedException) return "circuit breaker open";
        if (e instanceof BulkheadFullException) return "bulkhead full";
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
