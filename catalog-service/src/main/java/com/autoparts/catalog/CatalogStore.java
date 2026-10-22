package com.autoparts.catalog;

import com.autoparts.common.seed.DemoCatalog;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Catalog data in Spanner, via the client library (explicit SQL, no ORM). */
@Repository
public class CatalogStore {

    /** fitments is interleaved in vehicles: a vehicle's fitment rows are stored with it, so "parts for my car" is local. */
    public static final List<String> DDL = List.of(
            """
            CREATE TABLE vehicles (
              vehicle_id STRING(36) NOT NULL, year INT64 NOT NULL, make STRING(50) NOT NULL, model STRING(80) NOT NULL,
              engine STRING(80) NOT NULL
            ) PRIMARY KEY (vehicle_id)""",
            "CREATE INDEX vehicles_by_ymm ON vehicles (year, make, model) STORING (engine)",  // covering index: no back-join
            """
            CREATE TABLE parts (
              sku STRING(40) NOT NULL, name STRING(200) NOT NULL, brand STRING(80) NOT NULL, category STRING(80) NOT NULL,
              price_cents INT64 NOT NULL, description STRING(MAX), image_object STRING(200)
            ) PRIMARY KEY (sku)""",
            "CREATE INDEX parts_by_category ON parts (category)",
            """
            CREATE TABLE fitments (
              vehicle_id STRING(36) NOT NULL, sku STRING(40) NOT NULL
            ) PRIMARY KEY (vehicle_id, sku), INTERLEAVE IN PARENT vehicles ON DELETE CASCADE""",
            "CREATE INDEX fitments_by_sku ON fitments (sku)");

    public record Vehicle(String id, long year, String make, String model, String engine) {}

    public record Part(String sku, String name, String brand, String category, long priceCents, String description,
                       String imageObject) {}

    private final Supplier<DatabaseClient> db;

    public CatalogStore(Supplier<DatabaseClient> db) { this.db = db; }

    public boolean empty() {
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.of("SELECT COUNT(*) FROM parts"))) {
            return rs.next() && rs.getLong(0) == 0;
        }
    }

    public void seed(List<DemoCatalog.Vehicle> vehicles, List<DemoCatalog.Part> parts) {
        List<Mutation> m = new ArrayList<>();
        for (DemoCatalog.Vehicle v : vehicles) {
            m.add(Mutation.newInsertOrUpdateBuilder("vehicles").set("vehicle_id").to(v.id()).set("year").to(v.year())
                    .set("make").to(v.make()).set("model").to(v.model()).set("engine").to(v.engine()).build());
        }
        for (DemoCatalog.Part p : parts) {
            m.add(Mutation.newInsertOrUpdateBuilder("parts").set("sku").to(p.sku()).set("name").to(p.name())
                    .set("brand").to(p.brand()).set("category").to(p.category()).set("price_cents").to(p.priceCents())
                    .set("description").to(p.description()).set("image_object").to(imageObject(p.category())).build());
            for (DemoCatalog.Vehicle v : vehicles) {
                if (DemoCatalog.fits(p, v)) {
                    m.add(Mutation.newInsertOrUpdateBuilder("fitments").set("vehicle_id").to(v.id()).set("sku").to(p.sku()).build());
                }
            }
        }
        db.get().write(m);
    }

    public static String imageObject(String category) {
        return "categories/" + category.toLowerCase().replaceAll("[^a-z0-9]+", "-") + ".svg";
    }

    public List<Long> years() {
        return query(Statement.of("SELECT DISTINCT year FROM vehicles ORDER BY year DESC"), rs -> rs.getLong(0));
    }

    public List<String> makes(long year) {
        return query(Statement.newBuilder("SELECT DISTINCT make FROM vehicles WHERE year = @y ORDER BY make").bind("y").to(year).build(),
                rs -> rs.getString(0));
    }

    public List<Vehicle> models(long year, String make) {
        return query(Statement.newBuilder("SELECT vehicle_id, year, make, model, engine FROM vehicles@{FORCE_INDEX=vehicles_by_ymm} "
                        + "WHERE year = @y AND make = @m ORDER BY model").bind("y").to(year).bind("m").to(make).build(),
                rs -> new Vehicle(rs.getString(0), rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    public List<Part> partsFor(String vehicleId, String category) {
        Statement.Builder s = Statement.newBuilder("""
                SELECT p.sku, p.name, p.brand, p.category, p.price_cents, p.description, p.image_object
                FROM fitments f JOIN parts p ON p.sku = f.sku
                WHERE f.vehicle_id = @v""").bind("v").to(vehicleId);
        if (category != null) s.append(" AND p.category = @c").bind("c").to(category);
        return query(s.append(" ORDER BY p.category, p.price_cents").build(), CatalogStore::part);
    }

    public List<Part> search(String q, String category) {
        Statement.Builder s = Statement.newBuilder("SELECT sku, name, brand, category, price_cents, description, image_object FROM parts WHERE TRUE");
        if (q != null && !q.isBlank()) {
            s.append(" AND (LOWER(name) LIKE @q OR LOWER(sku) LIKE @q OR LOWER(brand) LIKE @q)").bind("q").to("%" + q.toLowerCase() + "%");
        }
        if (category != null) s.append(" AND category = @c").bind("c").to(category);
        return query(s.append(" ORDER BY category, name LIMIT 100").build(), CatalogStore::part);
    }

    public List<Part> bySkus(List<String> skus) {
        return query(Statement.newBuilder("SELECT sku, name, brand, category, price_cents, description, image_object FROM parts "
                + "WHERE sku IN UNNEST(@skus)").bind("skus").toStringArray(skus).build(), CatalogStore::part);
    }

    public List<String> categories() {
        return query(Statement.of("SELECT DISTINCT category FROM parts ORDER BY category"), rs -> rs.getString(0));
    }

    private static Part part(ResultSet rs) {
        return new Part(rs.getString(0), rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.isNull(5) ? null : rs.getString(5), rs.isNull(6) ? null : rs.getString(6));
    }

    private <T> List<T> query(Statement st, java.util.function.Function<ResultSet, T> map) {
        List<T> out = new ArrayList<>();
        try (ResultSet rs = db.get().singleUse().executeQuery(st)) {
            while (rs.next()) out.add(map.apply(rs));
        }
        return out;
    }
}
