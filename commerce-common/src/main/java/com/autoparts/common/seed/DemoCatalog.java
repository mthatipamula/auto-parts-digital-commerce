package com.autoparts.common.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic demo data shared by catalog and inventory so their SKUs agree. Brands are fictional.
 * Fitment follows simple real-world rules (e.g. a 5W-30 oil fits these engines) so vehicle search is believable.
 */
public final class DemoCatalog {
    private DemoCatalog() {}

    public record Vehicle(String id, int year, String make, String model, String engine) {}

    public record Part(String sku, String name, String brand, String category, long priceCents, String description,
                       String fitsEngines) {}

    public record Store(String id, String name, String city, String state) {}

    private static final String[][] MODELS = {
        {"Toyota", "Camry", "2.5L I4"}, {"Toyota", "RAV4", "2.5L I4"}, {"Honda", "Civic", "2.0L I4"},
        {"Honda", "Accord", "1.5L I4 Turbo"}, {"Ford", "F-150", "5.0L V8"}, {"Ford", "Escape", "1.5L I3 Turbo"},
        {"Chevrolet", "Silverado 1500", "5.3L V8"}, {"Chevrolet", "Malibu", "1.5L I4 Turbo"},
        {"Nissan", "Altima", "2.5L I4"}, {"Jeep", "Wrangler", "3.6L V6"},
    };

    public static List<Vehicle> vehicles() {
        List<Vehicle> out = new ArrayList<>();
        for (String[] m : MODELS) {
            for (int year = 2019; year <= 2024; year++) {
                String id = UUID.nameUUIDFromBytes((year + m[0] + m[1]).getBytes()).toString();
                out.add(new Vehicle(id, year, m[0], m[1], m[2]));
            }
        }
        return out;
    }

    /** Parts with the engines they fit ("*" = universal). */
    public static List<Part> parts() {
        return List.of(
            new Part("BRK-PAD-1001", "Ceramic Front Brake Pads", "StopRight", "Brakes", 5499, "Low-dust ceramic pads with shims and hardware.", "2.5L I4,2.0L I4,1.5L I4 Turbo"),
            new Part("BRK-PAD-1002", "Severe Duty Front Brake Pads", "StopRight", "Brakes", 8999, "Semi-metallic pads for towing and trucks.", "5.0L V8,5.3L V8,3.6L V6"),
            new Part("BRK-ROT-2001", "Front Brake Rotor", "IronLine", "Brakes", 6799, "Coated rotor, corrosion resistant.", "2.5L I4,2.0L I4,1.5L I4 Turbo,1.5L I3 Turbo"),
            new Part("BRK-ROT-2002", "Heavy Duty Front Brake Rotor", "IronLine", "Brakes", 11499, "Vented rotor for trucks and SUVs.", "5.0L V8,5.3L V8,3.6L V6"),
            new Part("FLT-OIL-3001", "Oil Filter", "PureFlow", "Filters", 899, "Synthetic media oil filter, 10,000 mile rating.", "2.5L I4,2.0L I4,1.5L I4 Turbo,1.5L I3 Turbo"),
            new Part("FLT-OIL-3002", "Oil Filter, Large Capacity", "PureFlow", "Filters", 1199, "Large-capacity filter for V6 and V8 engines.", "5.0L V8,5.3L V8,3.6L V6"),
            new Part("FLT-AIR-3101", "Engine Air Filter", "PureFlow", "Filters", 2199, "High-efficiency engine air filter.", "*"),
            new Part("FLT-CAB-3201", "Cabin Air Filter with Carbon", "PureFlow", "Filters", 2499, "Activated carbon cabin filter reduces odors.", "*"),
            new Part("BAT-H6-4001", "AGM Battery, Group H6", "VoltMax", "Batteries", 21999, "760 CCA AGM battery, 3-year replacement.", "2.5L I4,1.5L I4 Turbo,1.5L I3 Turbo"),
            new Part("BAT-65-4002", "Battery, Group 65", "VoltMax", "Batteries", 18999, "850 CCA flooded battery for trucks.", "5.0L V8,5.3L V8,3.6L V6"),
            new Part("BAT-51R-4003", "Battery, Group 51R", "VoltMax", "Batteries", 15999, "500 CCA battery for compact cars.", "2.0L I4"),
            new Part("WIP-22-5001", "Beam Wiper Blade, 22 in", "ClearView", "Wipers", 2299, "All-season beam blade.", "*"),
            new Part("WIP-26-5002", "Beam Wiper Blade, 26 in", "ClearView", "Wipers", 2499, "All-season beam blade.", "*"),
            new Part("IGN-PLG-6001", "Iridium Spark Plug", "SparkCore", "Ignition", 1099, "Iridium tip, 100,000 mile service life.", "*"),
            new Part("OIL-0W20-7001", "Full Synthetic Motor Oil 0W-20, 5 qt", "TorqueMax", "Oil & Fluids", 3299, "Dexos approved full synthetic.", "2.5L I4,2.0L I4,1.5L I4 Turbo,1.5L I3 Turbo"),
            new Part("OIL-5W30-7002", "Full Synthetic Motor Oil 5W-30, 5 qt", "TorqueMax", "Oil & Fluids", 3299, "Full synthetic for V6 and V8 engines.", "5.0L V8,5.3L V8,3.6L V6"),
            new Part("ALT-8001", "Remanufactured Alternator", "VoltMax", "Charging", 17999, "Remanufactured, 100% tested, lifetime limited warranty.", "2.5L I4,2.0L I4"),
            new Part("ALT-8002", "Remanufactured Alternator, High Output", "VoltMax", "Charging", 24999, "High-output alternator for trucks.", "5.0L V8,5.3L V8")
        );
    }

    public static boolean fits(Part p, Vehicle v) {
        return p.fitsEngines().equals("*") || List.of(p.fitsEngines().split(",")).contains(v.engine());
    }

    public static List<Store> stores() {
        return List.of(
            new Store("ST-0001", "Springfield - Glenstone", "Springfield", "MO"),
            new Store("ST-0002", "Kansas City - Main St", "Kansas City", "MO"),
            new Store("ST-0003", "Dallas - Mockingbird", "Dallas", "TX"),
            new Store("ST-0004", "Tulsa - Memorial", "Tulsa", "OK"),
            new Store("ST-0005", "Little Rock - University", "Little Rock", "AR"));
    }

    /** Stock levels with a few deliberate gaps so "out of stock at this store" is demonstrable. */
    public static int stock(String storeId, String sku) {
        int h = Math.abs((storeId + sku).hashCode());
        if (sku.startsWith("ALT") && !storeId.equals("ST-0001")) return 0; // alternators only at the hub store
        return h % 7 == 0 ? 0 : 2 + h % 18;
    }
}
