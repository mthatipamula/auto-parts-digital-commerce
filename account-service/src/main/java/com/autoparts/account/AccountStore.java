package com.autoparts.account;

import com.autoparts.common.ApiErrors.ApiException;
import com.autoparts.common.seed.DemoAccounts;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Commercial accounts in Spanner. Members and contract prices are interleaved under their account (stored with it).
 * Credit holds use read-write transactions on the account row, so concurrent orders can't exceed the credit limit.
 */
@Repository
public class AccountStore {

    public static final List<String> DDL = List.of(
            """
            CREATE TABLE accounts (
              account_id STRING(20) NOT NULL, name STRING(200) NOT NULL, type STRING(20) NOT NULL, tier STRING(20) NOT NULL,
              credit_limit_cents INT64 NOT NULL, open_balance_cents INT64 NOT NULL, held_cents INT64 NOT NULL,
              terms STRING(20) NOT NULL, home_store_id STRING(20) NOT NULL, delivery_address STRING(300), status STRING(12) NOT NULL
            ) PRIMARY KEY (account_id)""",
            """
            CREATE TABLE account_members (
              account_id STRING(20) NOT NULL, user_id STRING(64) NOT NULL, username STRING(100) NOT NULL,
              role STRING(10) NOT NULL, order_limit_cents INT64 NOT NULL
            ) PRIMARY KEY (account_id, user_id), INTERLEAVE IN PARENT accounts ON DELETE CASCADE""",
            "CREATE UNIQUE INDEX members_by_user ON account_members (user_id) STORING (username, role, order_limit_cents)",
            """
            CREATE TABLE contract_prices (
              account_id STRING(20) NOT NULL, sku STRING(40) NOT NULL, price_cents INT64 NOT NULL
            ) PRIMARY KEY (account_id, sku), INTERLEAVE IN PARENT accounts ON DELETE CASCADE""",
            "CREATE TABLE tier_discounts (tier STRING(20) NOT NULL, category STRING(80) NOT NULL, discount_bps INT64 NOT NULL) PRIMARY KEY (tier, category)",
            """
            CREATE TABLE credit_holds (
              order_id STRING(36) NOT NULL, account_id STRING(20) NOT NULL, user_id STRING(64) NOT NULL,
              amount_cents INT64 NOT NULL, status STRING(10) NOT NULL,
              created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true),
              updated_at TIMESTAMP OPTIONS (allow_commit_timestamp = true)
            ) PRIMARY KEY (order_id)""",
            "CREATE INDEX holds_by_status ON credit_holds (status, created_at)");

    public record Account(String id, String name, String type, String tier, long creditLimitCents, long openBalanceCents,
                          long heldCents, String terms, String homeStoreId, String deliveryAddress, String status) {
        public long availableCents() { return Math.max(0, creditLimitCents - openBalanceCents - heldCents); }
    }

    public record Member(String accountId, String userId, String username, String role, long orderLimitCents) {}

    public record Hold(String orderId, String accountId, long amountCents, String status) {}

    /** A definitive "no" from the credit check: not retried, and the saga compensates. */
    public static class CreditDeclined extends ApiException {
        public CreditDeclined(String message) { super(HttpStatus.PAYMENT_REQUIRED, message); }
    }

    private final Supplier<DatabaseClient> db;

    public AccountStore(Supplier<DatabaseClient> db) { this.db = db; }

    public boolean empty() {
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.of("SELECT COUNT(*) FROM accounts"))) {
            return rs.next() && rs.getLong(0) == 0;
        }
    }

    public void seed() {
        List<Mutation> m = new ArrayList<>();
        for (DemoAccounts.Account a : DemoAccounts.accounts()) {
            m.add(Mutation.newInsertOrUpdateBuilder("accounts").set("account_id").to(a.id()).set("name").to(a.name())
                    .set("type").to(a.type()).set("tier").to(a.tier()).set("credit_limit_cents").to(a.creditLimitCents())
                    .set("open_balance_cents").to(a.openBalanceCents()).set("held_cents").to(0).set("terms").to(a.terms())
                    .set("home_store_id").to(a.homeStoreId()).set("delivery_address").to(a.deliveryAddress())
                    .set("status").to("ACTIVE").build());
        }
        for (DemoAccounts.Member u : DemoAccounts.members()) {
            m.add(Mutation.newInsertOrUpdateBuilder("account_members").set("account_id").to(u.accountId()).set("user_id").to(u.userId())
                    .set("username").to(u.username()).set("role").to(u.role()).set("order_limit_cents").to(u.orderLimitCents()).build());
        }
        DemoAccounts.tierDiscounts().forEach((tier, rules) -> rules.forEach((cat, bps) -> m.add(Mutation.newInsertOrUpdateBuilder("tier_discounts")
                .set("tier").to(tier).set("category").to(cat).set("discount_bps").to(bps).build())));
        DemoAccounts.contractPrices().forEach((acct, prices) -> prices.forEach((sku, cents) -> m.add(Mutation.newInsertOrUpdateBuilder("contract_prices")
                .set("account_id").to(acct).set("sku").to(sku).set("price_cents").to(cents).build())));
        db.get().write(m);
    }

    public Optional<Member> memberByUser(String userId) {
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.newBuilder(
                "SELECT account_id, user_id, username, role, order_limit_cents FROM account_members@{FORCE_INDEX=members_by_user} "
                        + "WHERE user_id = @u").bind("u").to(userId).build())) {
            return rs.next() ? Optional.of(new Member(rs.getString(0), rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)))
                    : Optional.empty();
        }
    }

    public List<Member> members(String accountId) {
        List<Member> out = new ArrayList<>();
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.newBuilder(
                "SELECT account_id, user_id, username, role, order_limit_cents FROM account_members WHERE account_id = @a ORDER BY role, username")
                .bind("a").to(accountId).build())) {
            while (rs.next()) out.add(new Member(rs.getString(0), rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
        }
        return out;
    }

    public Account account(String accountId) {
        Struct r = db.get().singleUse().readRow("accounts", Key.of(accountId), ACCOUNT_COLS);
        if (r == null) throw ApiException.notFound("Account " + accountId);
        return toAccount(r);
    }

    public List<Account> all() {
        List<Account> out = new ArrayList<>();
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.of("SELECT " + String.join(", ", ACCOUNT_COLS) + " FROM accounts ORDER BY name"))) {
            while (rs.next()) out.add(toAccount(rs.getCurrentRowAsStruct()));
        }
        return out;
    }

    public Map<String, Long> contractPrices(String accountId) {
        Map<String, Long> out = new HashMap<>();
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.newBuilder(
                "SELECT sku, price_cents FROM contract_prices WHERE account_id = @a").bind("a").to(accountId).build())) {
            while (rs.next()) out.put(rs.getString(0), rs.getLong(1));
        }
        return out;
    }

    public Map<String, Long> tierDiscounts(String tier) {
        Map<String, Long> out = new HashMap<>();
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.newBuilder(
                "SELECT category, discount_bps FROM tier_discounts WHERE tier = @t").bind("t").to(tier).build())) {
            while (rs.next()) out.put(rs.getString(0), rs.getLong(1));
        }
        return out;
    }

    /**
     * Places a credit hold for an order: idempotent by order ID, and checked + applied in one read-write transaction
     * on the account row, so two concurrent orders can't both use the last of the credit line.
     */
    public Hold hold(Member member, String orderId, long amountCents) {
        return db.get().readWriteTransaction().run(tx -> {
            Struct existing = tx.readRow("credit_holds", Key.of(orderId), List.of("order_id", "account_id", "amount_cents", "status"));
            if (existing != null) return toHold(existing);
            Struct a = tx.readRow("accounts", Key.of(member.accountId()), ACCOUNT_COLS);
            Account acct = toAccount(a);
            if (!acct.status().equals("ACTIVE")) throw new CreditDeclined("Account is on hold; contact your store");
            if (member.role().equals("BUYER") && amountCents > member.orderLimitCents()) {
                throw new CreditDeclined("Order of $%.2f exceeds your purchase limit of $%.2f; ask the account owner"
                        .formatted(amountCents / 100.0, member.orderLimitCents() / 100.0));
            }
            if (amountCents > acct.availableCents()) {
                throw new CreditDeclined("Credit limit exceeded: order $%.2f, available credit $%.2f"
                        .formatted(amountCents / 100.0, acct.availableCents() / 100.0));
            }
            tx.buffer(List.of(
                    Mutation.newUpdateBuilder("accounts").set("account_id").to(acct.id()).set("held_cents").to(acct.heldCents() + amountCents).build(),
                    Mutation.newInsertBuilder("credit_holds").set("order_id").to(orderId).set("account_id").to(acct.id())
                            .set("user_id").to(member.userId()).set("amount_cents").to(amountCents).set("status").to("HELD")
                            .set("created_at").to(Value.COMMIT_TIMESTAMP).build()));
            return new Hold(orderId, acct.id(), amountCents, "HELD");
        });
    }

    /** HELD -> CAPTURED (invoiced: moves to the open balance) or HELD -> RELEASED. Idempotent. */
    public Hold finish(String orderId, boolean capture) {
        return db.get().readWriteTransaction().run(tx -> {
            Struct h = tx.readRow("credit_holds", Key.of(orderId), List.of("order_id", "account_id", "amount_cents", "status"));
            if (h == null) return null;
            Hold hold = toHold(h);
            if (!hold.status().equals("HELD")) return hold;
            Struct a = tx.readRow("accounts", Key.of(hold.accountId()), List.of("open_balance_cents", "held_cents"));
            Mutation.WriteBuilder acct = Mutation.newUpdateBuilder("accounts").set("account_id").to(hold.accountId())
                    .set("held_cents").to(Math.max(0, a.getLong("held_cents") - hold.amountCents()));
            if (capture) acct.set("open_balance_cents").to(a.getLong("open_balance_cents") + hold.amountCents());
            tx.buffer(List.of(acct.build(), Mutation.newUpdateBuilder("credit_holds").set("order_id").to(orderId)
                    .set("status").to(capture ? "CAPTURED" : "RELEASED").set("updated_at").to(Value.COMMIT_TIMESTAMP).build()));
            return new Hold(orderId, hold.accountId(), hold.amountCents(), capture ? "CAPTURED" : "RELEASED");
        });
    }

    public List<String> staleHolds(int minutes) {
        List<String> ids = new ArrayList<>();
        try (ResultSet rs = db.get().singleUse().executeQuery(Statement.newBuilder(
                "SELECT order_id FROM credit_holds@{FORCE_INDEX=holds_by_status} WHERE status = 'HELD' "
                        + "AND created_at < TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL @m MINUTE) LIMIT 100").bind("m").to(minutes).build())) {
            while (rs.next()) ids.add(rs.getString(0));
        }
        return ids;
    }

    private static final List<String> ACCOUNT_COLS = List.of("account_id", "name", "type", "tier", "credit_limit_cents",
            "open_balance_cents", "held_cents", "terms", "home_store_id", "delivery_address", "status");

    private static Account toAccount(Struct r) {
        return new Account(r.getString("account_id"), r.getString("name"), r.getString("type"), r.getString("tier"),
                r.getLong("credit_limit_cents"), r.getLong("open_balance_cents"), r.getLong("held_cents"), r.getString("terms"),
                r.getString("home_store_id"), r.isNull("delivery_address") ? null : r.getString("delivery_address"), r.getString("status"));
    }

    private static Hold toHold(Struct h) {
        return new Hold(h.getString("order_id"), h.getString("account_id"), h.getLong("amount_cents"), h.getString("status"));
    }

    /** Business errors thrown inside a Spanner transaction come back wrapped; surface ours as-is. */
    public static RuntimeException unwrap(RuntimeException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof ApiException api) return api;
            t = t.getCause();
        }
        return e;
    }
}
