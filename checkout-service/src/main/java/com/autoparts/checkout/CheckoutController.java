package com.autoparts.checkout;

import com.autoparts.checkout.OrderStore.Order;
import com.autoparts.common.ApiErrors.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class CheckoutController {
    private final Cart cart;
    private final CheckoutSaga saga;
    private final OrderStore orders;
    private final StringRedisTemplate redis;
    private final Downstream downstream;

    public CheckoutController(Cart cart, CheckoutSaga saga, OrderStore orders, StringRedisTemplate redis, Downstream downstream) {
        this.cart = cart;
        this.saga = saga;
        this.orders = orders;
        this.redis = redis;
        this.downstream = downstream;
    }

    // ------------------------------------------------------------ cart (Valkey)

    @GetMapping("/cart")
    public Map<String, Integer> cart(@AuthenticationPrincipal Jwt user) { return cart.items(user.getSubject()); }

    public record Quantity(@Min(0) @Max(20) int quantity) {}

    @PutMapping("/cart/items/{sku}")
    public Map<String, Integer> setItem(@PathVariable @Pattern(regexp = "[A-Z0-9-]{3,40}") String sku, @RequestBody Quantity q,
                                        @AuthenticationPrincipal Jwt user) {
        cart.set(user.getSubject(), sku, q.quantity());
        return cart.items(user.getSubject());
    }

    @DeleteMapping("/cart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@AuthenticationPrincipal Jwt user) { cart.clear(user.getSubject()); }

    // ------------------------------------------------------------ checkout (command side, Spanner)

    public record CheckoutRequest(@NotBlank String storeId,
                                  @Pattern(regexp = "PICKUP|DELIVERY") String fulfillment,
                                  @Pattern(regexp = "CARD|ACCOUNT") String paymentMethod,
                                  @jakarta.validation.constraints.Size(max = 40) String poNumber) {}

    /** The Idempotency-Key makes a double-click or a client retry return the same order instead of a second one. */
    @PostMapping("/orders")
    public Order checkout(@jakarta.validation.Valid @RequestBody CheckoutRequest r,
                          @RequestHeader("Idempotency-Key") @NotBlank String idemKey, @AuthenticationPrincipal Jwt user) {
        if (idemKey.length() > 100) throw ApiException.badRequest("Idempotency-Key is too long");
        CheckoutSaga.Buyer buyer = new CheckoutSaga.Buyer(user.getSubject(), user.getClaimAsString("preferred_username"),
                hasAnyRole(user, Set.of("pro")));
        return saga.checkout(buyer, idemKey, new CheckoutSaga.Request(r.storeId(),
                r.fulfillment() == null ? "PICKUP" : r.fulfillment(), r.paymentMethod() == null ? "CARD" : r.paymentMethod(), r.poNumber()));
    }

    /** Source of truth, including every saga step. Customers see their own orders; associates and admins see any. */
    @GetMapping("/orders/{orderId}")
    public Order order(@PathVariable String orderId, @AuthenticationPrincipal Jwt user) {
        Order o = orders.get(orderId);
        if (o == null) throw ApiException.notFound("Order " + orderId);
        boolean allowed = hasAnyRole(user, Set.of("associate", "admin")) || o.customerId().equals(user.getSubject())
                || (o.terms().accountId() != null && o.terms().accountId().equals(ownedAccount(user)));
        if (!allowed) throw ApiException.notFound("Order " + orderId);    // 404, not 403: don't reveal it exists
        return o;
    }

    // ------------------------------------------------------------ order history (query side, Valkey)

    /** scope=mine (default): my orders. scope=account: every order on my Pro account (account owners only). */
    @GetMapping("/orders")
    public List<Map<String, String>> history(@AuthenticationPrincipal Jwt user,
                                             @org.springframework.web.bind.annotation.RequestParam(defaultValue = "mine") String scope) {
        String key = "orders:" + user.getSubject();
        if (scope.equals("account")) {
            String account = ownedAccount(user);
            if (account == null) throw new ApiException(HttpStatus.FORBIDDEN, "Only a Pro account owner can see all account orders");
            key = "account-orders:" + account;
        }
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> ids = redis.opsForZSet().range(key, 0, 49);
        if (ids == null) return out;
        for (String id : ids) {
            Map<String, String> view = redis.<String, String>opsForHash().entries("orderview:" + id);
            if (!view.isEmpty()) out.add(view);
        }
        return out;
    }

    // ------------------------------------------------------------ resilience status (for the demo / ops)

    @GetMapping("/checkout/dependencies")
    @PreAuthorize("hasAnyRole('associate', 'admin')")
    public List<Map<String, Object>> dependencies() { return downstream.health(); }

    /** The Pro account this user OWNS (null for non-owners): asks the account service, the owner of membership. */
    @SuppressWarnings("unchecked")
    private String ownedAccount(Jwt user) {
        if (!hasAnyRole(user, Set.of("pro"))) return null;
        try {
            Map<String, Object> me = downstream.call("account", c -> c.get().uri("/api/accounts/me").retrieve().body(Map.class));
            Map<String, Object> member = (Map<String, Object>) me.get("member");
            return "OWNER".equals(member.get("role")) ? String.valueOf(member.get("accountId")) : null;
        } catch (Downstream.Business e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasAnyRole(Jwt jwt, Set<String> roles) {
        Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
        List<String> list = realm == null ? List.of() : (List<String>) realm.getOrDefault("roles", List.of());
        return list.stream().anyMatch(roles::contains);
    }
}
