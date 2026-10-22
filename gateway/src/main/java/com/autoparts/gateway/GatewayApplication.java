package com.autoparts.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

/**
 * Edge for the storefront: validates tokens, rate-limits per user (token bucket in Valkey), and routes to services.
 * Internal endpoints (stock reservations, payment authorizations) have no route here, so they can't be called
 * from outside even with a valid customer token.
 */
@SpringBootApplication
@RestController
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${platform.security.jwk-set-uri}") String jwks, @Value("${platform.security.issuer}") String issuer) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(a -> a
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/catalog/**", "/api/inventory/stores", "/api/inventory/availability").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {}))
                .build();
    }

    /** Rate-limit key: the signed-in user, or the client IP for anonymous catalog browsing. */
    @Bean
    KeyResolver userOrIp() {
        return exchange -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(p -> "user:" + ((JwtAuthenticationToken) p).getToken().getSubject())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + Objects.requireNonNull(exchange.getRequest().getRemoteAddress()).getAddress().getHostAddress()));
    }

    /** Catalog circuit-breaker fallback: tell the client clearly instead of hanging. */
    @RequestMapping("/fallback/catalog")
    Mono<ResponseEntity<Map<String, Object>>> catalogFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "title", "Catalog temporarily unavailable", "status", 503, "detail", "Please try again in a few seconds.")));
    }
}
