package com.autoparts.common.seed;

import java.util.List;
import java.util.Map;

/**
 * Demo commercial (Pro) accounts. User IDs match the fixed Keycloak user IDs in infra/keycloak/realm.json, so
 * membership can be seeded: Keycloak proves who the user is, the account service decides which account they buy for.
 * Account names and addresses are illustrative.
 */
public final class DemoAccounts {
    private DemoAccounts() {}

    public static final String RICK_USER = "0f6b1c3a-1d2e-4c5b-9a01-7e1c0b0a1001";
    public static final String JORDAN_USER = "0f6b1c3a-1d2e-4c5b-9a01-7e1c0b0a1002";
    public static final String MIKE_USER = "0f6b1c3a-1d2e-4c5b-9a01-7e1c0b0a2001";

    public record Account(String id, String name, String type, String tier, long creditLimitCents, long openBalanceCents,
                          String terms, String homeStoreId, String deliveryAddress) {}

    public record Member(String accountId, String userId, String username, String role, long orderLimitCents) {}

    public static List<Account> accounts() {
        return List.of(
            new Account("ACC-1001", "Rick's Automotive", "SHOP", "GOLD", 500_000, 120_000, "NET30", "ST-0001",
                    "1420 W Sunshine St, Springfield, MO"),
            new Account("ACC-2001", "Mike's Mobile Mechanic", "INDEPENDENT", "SILVER", 100_000, 85_000, "NET30", "ST-0001",
                    "Mobile service, Springfield, MO"));
    }

    /** OWNER manages the account and sees every order; BUYER (a technician) orders up to a per-order limit. */
    public static List<Member> members() {
        return List.of(
            new Member("ACC-1001", RICK_USER, "rick", "OWNER", 0),
            new Member("ACC-1001", JORDAN_USER, "jordan", "BUYER", 75_000),
            new Member("ACC-2001", MIKE_USER, "mike", "OWNER", 0));
    }

    /** Tier discounts in basis points by category ("*" = any other category). */
    public static Map<String, Map<String, Integer>> tierDiscounts() {
        return Map.of(
            "GOLD", Map.of("Brakes", 2500, "Filters", 2000, "*", 1500),
            "SILVER", Map.of("Brakes", 1500, "*", 1000));
    }

    /** Negotiated contract prices: these override the tier discount for that account and part. */
    public static Map<String, Map<String, Long>> contractPrices() {
        return Map.of("ACC-1001", Map.of("BRK-PAD-1001", 3499L, "OIL-0W20-7001", 2199L));
    }
}
