package com.autoparts.inventory;

import com.autoparts.common.ApiErrors.ApiException;
import com.autoparts.inventory.InventoryStore.Line;
import com.autoparts.inventory.InventoryStore.OutOfStock;
import com.autoparts.inventory.InventoryStore.Reservation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Availability is public through the gateway. Reservations are internal: the gateway doesn't route them, and in
 * the mesh an Istio AuthorizationPolicy only lets the checkout service's identity call them.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private static final long HOLD_MINUTES = 15;
    private final InventoryStore store;

    public InventoryController(InventoryStore store) { this.store = store; }

    @GetMapping("/stores")
    public List<Map<String, Object>> stores() { return store.stores(); }

    @GetMapping("/availability")
    public Map<String, Long> availability(@RequestParam String storeId, @RequestParam List<String> skus) {
        if (skus.size() > 100) throw ApiException.badRequest("At most 100 SKUs");
        return store.availability(storeId, skus);
    }

    public record LineIn(@NotBlank String sku, @Min(1) int quantity) {}

    public record ReserveRequest(@NotBlank String orderId, @NotBlank String storeId, @NotEmpty @Size(max = 50) List<@Valid LineIn> items) {}

    @PostMapping("/reservations")
    public Reservation reserve(@Valid @RequestBody ReserveRequest r) {
        try {
            return store.reserve(r.orderId(), r.storeId(), r.items().stream().map(l -> new Line(l.sku(), l.quantity())).toList(), HOLD_MINUTES);
        } catch (RuntimeException e) {
            throw InventoryStore.unwrap(e);
        }
    }

    @GetMapping("/reservations/{orderId}")
    public Reservation get(@PathVariable String orderId) {
        Reservation r = store.get(orderId);
        if (r == null) throw ApiException.notFound("Reservation for order " + orderId);
        return r;
    }

    /** Compensation step of the checkout saga. Idempotent: releasing twice (or a missing hold) is not an error. */
    @DeleteMapping("/reservations/{orderId}")
    public Map<String, String> release(@PathVariable String orderId) {
        Reservation r = store.finish(orderId, false);
        return Map.of("orderId", orderId, "status", r == null ? "NONE" : r.status());
    }

    @ExceptionHandler(OutOfStock.class)
    ProblemDetail outOfStock(OutOfStock e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Not enough stock at this store");
        p.setProperty("shortages", e.shortages);
        return p;
    }
}
