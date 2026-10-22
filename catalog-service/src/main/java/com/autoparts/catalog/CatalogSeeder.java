package com.autoparts.catalog;

import com.autoparts.common.gcp.SpannerSetup;
import com.autoparts.common.seed.DemoCatalog;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Creates the schema and loads the demo catalog and its images on first start. */
@Component
public class CatalogSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);
    private static final Map<String, String> COLORS = Map.of("Brakes", "#c0392b", "Filters", "#2e86c1", "Batteries", "#1e8449",
            "Wipers", "#7d3c98", "Ignition", "#d35400", "Oil & Fluids", "#b7950b", "Charging", "#566573");
    private final SpannerSetup spanner;
    private final CatalogStore store;
    private final Storage storage;
    private final String bucket;

    public CatalogSeeder(SpannerSetup spanner, CatalogStore store, Storage storage, @Value("${platform.images.bucket}") String bucket) {
        this.spanner = spanner;
        this.store = store;
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public void run(ApplicationArguments args) {
        spanner.ensure(CatalogStore.DDL);
        if (store.empty()) {
            store.seed(DemoCatalog.vehicles(), DemoCatalog.parts());
            log.info("Seeded {} vehicles and {} parts", DemoCatalog.vehicles().size(), DemoCatalog.parts().size());
        }
        try {
            if (storage.get(bucket) == null) storage.create(BucketInfo.of(bucket));
            COLORS.forEach((category, color) -> storage.create(
                    BlobInfo.newBuilder(BlobId.of(bucket, CatalogStore.imageObject(category))).setContentType("image/svg+xml")
                            .setCacheControl("public, max-age=86400").build(),
                    svg(category, color).getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            log.warn("Could not upload product images to GCS (catalog still works without them): {}", e.toString());
        }
    }

    private static String svg(String label, String color) {
        return """
                <svg xmlns="http://www.w3.org/2000/svg" width="320" height="200" viewBox="0 0 320 200">
                  <rect width="320" height="200" rx="16" fill="%s"/>
                  <text x="160" y="112" font-family="sans-serif" font-size="28" fill="#fff" text-anchor="middle">%s</text>
                </svg>""".formatted(color, label.replace("&", "&amp;"));
    }
}
