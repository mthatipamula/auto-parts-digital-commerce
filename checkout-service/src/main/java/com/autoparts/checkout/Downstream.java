package com.autoparts.checkout;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Calls to catalog, inventory and payment, each wrapped as Retry(CircuitBreaker(Bulkhead(call))):
 * - Bulkhead caps concurrent calls per dependency, so a slow payment provider can't use up every thread.
 * - The circuit breaker opens when a dependency keeps failing or is slow, and fails fast while it recovers.
 * - Retry only re-tries Transient failures (5xx, timeouts, connection errors) on idempotent calls; a Business
 *   failure (out of stock, card declined) is an answer, not an outage, so it is never retried and doesn't count
 *   against the circuit breaker.
 * Every call forwards the caller's token: each service checks it again (zero trust).
 */
@Component
public class Downstream {

    /** A dependency's outage or timeout: worth retrying, counts toward opening the circuit. */
    public static class Transient extends RuntimeException {
        public Transient(String message, Throwable cause) { super(message, cause); }
    }

    /** A dependency's definitive answer (4xx): never retried. */
    public static class Business extends RuntimeException {
        public final int status;
        public final String body;

        public Business(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
            this.body = body;
        }
    }

    private final Map<String, RestClient> clients;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;
    private final BulkheadRegistry bulkheads;

    public Downstream(RestClient.Builder builder, CircuitBreakerRegistry breakers, RetryRegistry retries, BulkheadRegistry bulkheads,
                      @Value("${platform.services.catalog}") String catalog, @Value("${platform.services.inventory}") String inventory,
                      @Value("${platform.services.payment}") String payment, @Value("${platform.services.account}") String account) {
        this.breakers = breakers;
        this.retries = retries;
        this.bulkheads = bulkheads;
        this.clients = Map.of("catalog", client(builder, catalog, 2), "inventory", client(builder, inventory, 3),
                "payment", client(builder, payment, 5), "account", client(builder, account, 3));
    }

    private static RestClient client(RestClient.Builder builder, String baseUrl, int timeoutSeconds) {
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(Duration.ofSeconds(1));
        http.setReadTimeout(Duration.ofSeconds(timeoutSeconds));   // the time limit for each attempt
        return builder.clone().baseUrl(baseUrl).requestFactory(http).build();
    }

    /** Run a call against a dependency with its bulkhead, circuit breaker and retry policy. */
    public <T> T call(String dependency, java.util.function.Function<RestClient, T> request) {
        RestClient client = clients.get(dependency);
        String token = currentToken();
        Supplier<T> attempt = () -> classify(() -> request.apply(withToken(client, token)));
        Supplier<T> guarded = Retry.decorateSupplier(retries.retry(dependency),          // outermost: retry the whole guarded call
                CircuitBreaker.decorateSupplier(breakers.circuitBreaker(dependency),
                        Bulkhead.decorateSupplier(bulkheads.bulkhead(dependency), attempt)));  // innermost: concurrency cap
        return guarded.get();
    }

    private static <T> T classify(Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            HttpStatusCode s = e.getStatusCode();
            if (s.is5xxServerError() || s.value() == 429) throw new Transient("HTTP " + s.value(), e);
            throw new Business(s.value(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {                      // timeouts, connection refused
            throw new Transient(e.getMessage(), e);
        }
    }

    private static RestClient withToken(RestClient client, String token) {
        return token == null ? client : client.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, token).build();
    }

    private static String currentToken() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            return a.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        }
        return null;
    }

    public CircuitBreaker.State state(String dependency) { return breakers.circuitBreaker(dependency).getState(); }

    public List<Map<String, Object>> health() {
        return List.of("catalog", "inventory", "payment", "account").stream().map(d -> {
            CircuitBreaker cb = breakers.circuitBreaker(d);
            Bulkhead bh = bulkheads.bulkhead(d);
            Retry r = retries.retry(d);
            return Map.<String, Object>of("dependency", d, "circuit", cb.getState().name(),
                    "failureRate", cb.getMetrics().getFailureRate(), "bufferedCalls", cb.getMetrics().getNumberOfBufferedCalls(),
                    "notPermittedCalls", cb.getMetrics().getNumberOfNotPermittedCalls(),
                    "bulkheadAvailable", bh.getMetrics().getAvailableConcurrentCalls(),
                    "retriedCalls", r.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt() + r.getMetrics().getNumberOfFailedCallsWithRetryAttempt());
        }).toList();
    }
}
