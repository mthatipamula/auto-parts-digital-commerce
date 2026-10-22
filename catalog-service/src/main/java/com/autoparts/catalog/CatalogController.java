package com.autoparts.catalog;

import com.autoparts.catalog.CatalogStore.Part;
import com.autoparts.catalog.CatalogStore.Vehicle;
import com.autoparts.common.ApiErrors.ApiException;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Year / make / model vehicle lookup and parts that fit, cached in Valkey. Prices served here are the authority:
 * checkout re-prices from this service rather than trusting the browser.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final CatalogStore store;
    private final Cache cache;
    private final String imageBase;

    public CatalogController(CatalogStore store, Cache cache, @Value("${platform.images.base-url}") String imageBase) {
        this.store = store;
        this.cache = cache;
        this.imageBase = imageBase;
    }

    public record PartView(String sku, String name, String brand, String category, long priceCents, String description,
                           String imageUrl) {}

    private PartView view(Part p) {
        return new PartView(p.sku(), p.name(), p.brand(), p.category(), p.priceCents(), p.description(),
                p.imageObject() == null ? null : imageBase + p.imageObject());
    }

    private static <T> ResponseEntity<T> cacheable(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(body);
    }

    @GetMapping("/vehicles/years")
    public ResponseEntity<List<Long>> years() {
        return cacheable(cache.get("cat:years", TTL, cache.listOf(Long.class), store::years));
    }

    @GetMapping("/vehicles/makes")
    public ResponseEntity<List<String>> makes(@RequestParam long year) {
        return cacheable(cache.get("cat:makes:" + year, TTL, cache.listOf(String.class), () -> store.makes(year)));
    }

    @GetMapping("/vehicles/models")
    public ResponseEntity<List<Vehicle>> models(@RequestParam long year, @RequestParam String make) {
        return cacheable(cache.get("cat:models:" + year + ":" + make, TTL, cache.listOf(Vehicle.class), () -> store.models(year, make)));
    }

    @GetMapping("/vehicles/{vehicleId}/parts")
    public ResponseEntity<List<PartView>> partsFor(@PathVariable String vehicleId, @RequestParam(required = false) String category) {
        List<Part> parts = cache.get("cat:fit:" + vehicleId + ":" + category, TTL, cache.listOf(Part.class),
                () -> store.partsFor(vehicleId, category));
        return cacheable(parts.stream().map(this::view).toList());
    }

    @GetMapping("/parts")
    public List<PartView> search(@RequestParam(required = false) String q, @RequestParam(required = false) String category) {
        return store.search(q, category).stream().map(this::view).toList();
    }

    @GetMapping("/parts/{sku}")
    public PartView part(@PathVariable String sku) {
        return store.bySkus(List.of(sku)).stream().findFirst().map(this::view).orElseThrow(() -> ApiException.notFound("Part " + sku));
    }

    @GetMapping("/categories")
    public ResponseEntity<List<String>> categories() {
        return cacheable(cache.get("cat:categories", TTL, cache.listOf(String.class), store::categories));
    }

    public record PriceRequest(@NotEmpty @Size(max = 100) List<String> skus) {}

    /** Authoritative prices for checkout (POST so a long SKU list fits; still read-only). */
    @PostMapping("/prices")
    public List<PartView> prices(@RequestBody PriceRequest r) {
        return store.bySkus(r.skus()).stream().map(this::view).toList();
    }
}
