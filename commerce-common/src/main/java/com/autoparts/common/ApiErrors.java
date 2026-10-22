package com.autoparts.common;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** RFC 7807 problem responses, including clear answers when a resilience guard rejects a call. */
@AutoConfiguration
@RestControllerAdvice
public class ApiErrors {

    public static class ApiException extends RuntimeException {
        private final HttpStatus status;

        public ApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() { return status; }

        public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, what + " not found"); }
        public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
        public static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) { return ProblemDetail.forStatusAndDetail(e.status(), e.getMessage()); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        p.setProperty("fields", fields);
        return p;
    }

    @ExceptionHandler(CallNotPermittedException.class)
    ProblemDetail circuitOpen(CallNotPermittedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "A dependency is failing and its circuit breaker is open; try again shortly (" + e.getCausingCircuitBreakerName() + ")");
    }

    @ExceptionHandler(BulkheadFullException.class)
    ProblemDetail bulkhead(BulkheadFullException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Too many concurrent requests to a dependency; try again shortly");
    }

    @ExceptionHandler(RequestNotPermitted.class)
    ProblemDetail rateLimited(RequestNotPermitted e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
    }
}
