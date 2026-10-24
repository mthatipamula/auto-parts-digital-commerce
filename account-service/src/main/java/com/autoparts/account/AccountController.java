package com.autoparts.account;

import com.autoparts.account.AccountStore.Account;
import com.autoparts.account.AccountStore.Hold;
import com.autoparts.account.AccountStore.Member;
import com.autoparts.common.ApiErrors.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pro (B2B) accounts: who buys for which account, their prices and their credit.
 * Keycloak authenticates the user; this service owns the business relationship (membership lives here, not in the IdP).
 * Credit holds are internal: the gateway doesn't route them; in the mesh only checkout's identity may call them.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountStore store;
    private final RestClient catalog;

    public AccountController(AccountStore store, RestClient.Builder http, @Value("${platform.services.catalog}") String catalogUrl) {
        this.store = store;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(1));
        f.setReadTimeout(Duration.ofSeconds(3));
        this.catalog = http.clone().baseUrl(catalogUrl).requestFactory(f).build();
    }

    private Member member(Jwt user) {
        return store.memberByUser(user.getSubject())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "You are not a member of a Pro account"));
    }

    public record Credit(long limitCents, long openBalanceCents, long onHoldCents, long availableCents, String terms) {}

    public record Me(Account account, Member member, Credit credit, List<Member> members) {}

    @GetMapping("/me")
    @PreAuthorize("hasRole('pro')")
    public Me me(@AuthenticationPrincipal Jwt user) {
        Member m = member(user);
        Account a = store.account(m.accountId());
        return new Me(a, m, credit(a), m.role().equals("OWNER") ? store.members(a.id()) : List.of(m));
    }

    private static Credit credit(Account a) {
        return new Credit(a.creditLimitCents(), a.openBalanceCents(), a.heldCents(), a.availableCents(), a.terms());
    }

    // ------------------------------------------------------------ pricing: contract > tier discount > list

    public record QuoteRequest(@NotEmpty @Size(max = 100) List<String> skus) {}

    public record CatalogPart(String sku, String name, String category, long priceCents) {}

    public record PriceLine(String sku, String name, String category, long listPriceCents, long netPriceCents, String rule) {}

    @PostMapping("/me/quote")
    @PreAuthorize("hasRole('pro')")
    public List<PriceLine> quote(@Valid @RequestBody QuoteRequest r, @AuthenticationPrincipal Jwt user) {
        Account a = store.account(member(user).accountId());
        List<CatalogPart> parts = catalog.post().uri("/api/catalog/prices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.getTokenValue())
                .body(Map.of("skus", r.skus())).retrieve().body(new ParameterizedTypeReference<List<CatalogPart>>() {});
        Map<String, Long> contract = store.contractPrices(a.id());
        Map<String, Long> tier = store.tierDiscounts(a.tier());
        return parts.stream().map(p -> {
            Long c = contract.get(p.sku());
            if (c != null) return new PriceLine(p.sku(), p.name(), p.category(), p.priceCents(), c, "Contract price");
            long bps = tier.getOrDefault(p.category(), tier.getOrDefault("*", 0L));
            long net = Math.round(p.priceCents() * (10_000 - bps) / 10_000.0);
            return new PriceLine(p.sku(), p.name(), p.category(), p.priceCents(), net,
                    bps == 0 ? "List price" : "%s tier %d%% off".formatted(a.tier(), bps / 100));
        }).toList();
    }

    // ------------------------------------------------------------ credit (called by the checkout saga)

    public record HoldRequest(@NotBlank String orderId, @Positive long amountCents) {}

    @PostMapping("/me/credit-holds")
    @PreAuthorize("hasRole('pro')")
    public Hold hold(@Valid @RequestBody HoldRequest r, @AuthenticationPrincipal Jwt user) {
        Member m = member(user);
        try {
            return store.hold(m, r.orderId(), r.amountCents());
        } catch (RuntimeException e) {
            throw AccountStore.unwrap(e);
        }
    }

    /** Compensation step: idempotent, so releasing twice (or a missing hold) is not an error. */
    @DeleteMapping("/me/credit-holds/{orderId}")
    @PreAuthorize("hasRole('pro')")
    public Map<String, String> release(@PathVariable String orderId) {
        Hold h = store.finish(orderId, false);
        return Map.of("orderId", orderId, "status", h == null ? "NONE" : h.status());
    }

    // ------------------------------------------------------------ store staff

    @GetMapping
    @PreAuthorize("hasAnyRole('associate', 'admin')")
    public List<Map<String, Object>> all() {
        return store.all().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("account", a);
            m.put("credit", credit(a));
            m.put("members", store.members(a.id()).size());
            return m;
        }).toList();
    }
}
