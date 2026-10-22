package com.autoparts.payment;

import com.autoparts.common.ApiErrors.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stand-in for a payment processor. Authorizations are idempotent by order ID (a retried call returns the same
 * result instead of charging twice). Admins can inject failures and latency to exercise the checkout service's
 * circuit breaker, retries and timeouts.
 */
@SpringBootApplication(scanBasePackages = "com.autoparts")
@RestController
@RequestMapping("/api/payments")
public class PaymentApplication {
    public static void main(String[] args) { SpringApplication.run(PaymentApplication.class, args); }

    public record AuthorizeRequest(@NotBlank String orderId, @Positive long amountCents) {}

    public record Authorization(String paymentId, String orderId, long amountCents, String status, Instant at) {}

    public record Chaos(@DecimalMin("0") @DecimalMax("1") double failureRate, @Min(0) @Max(30_000) int latencyMs) {}

    private final Map<String, Authorization> byOrder = new ConcurrentHashMap<>();
    private volatile Chaos chaos = new Chaos(0, 0);

    @PostMapping("/authorizations")
    public Authorization authorize(@Valid @RequestBody AuthorizeRequest r) throws InterruptedException {
        Authorization existing = byOrder.get(r.orderId());
        if (existing != null) return existing;                  // idempotent: safe to retry
        Chaos c = chaos;
        if (c.latencyMs() > 0) Thread.sleep(c.latencyMs());
        if (ThreadLocalRandom.current().nextDouble() < c.failureRate()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Payment processor unavailable (injected fault)");
        }
        if (r.amountCents() > 500_000) {                         // business decline, not a technical failure
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "Card declined: amount exceeds limit");
        }
        return byOrder.computeIfAbsent(r.orderId(), id ->
                new Authorization("PAY-" + UUID.randomUUID().toString().substring(0, 8), id, r.amountCents(), "AUTHORIZED", Instant.now()));
    }

    @GetMapping("/authorizations/{orderId}")
    public Authorization get(@PathVariable String orderId) {
        Authorization a = byOrder.get(orderId);
        if (a == null) throw ApiException.notFound("Authorization for order " + orderId);
        return a;
    }

    @GetMapping("/chaos")
    public Chaos chaos() { return chaos; }

    @PutMapping("/chaos")
    @PreAuthorize("hasRole('admin')")
    public Chaos setChaos(@Valid @RequestBody Chaos c) {
        chaos = c;
        return c;
    }
}
