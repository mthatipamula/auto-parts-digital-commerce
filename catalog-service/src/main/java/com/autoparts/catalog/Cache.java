package com.autoparts.catalog;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Cache-aside on Memorystore (Valkey): read the cache, on a miss load from Spanner and store with a TTL.
 * If Valkey is down the request still succeeds from Spanner (the cache is an optimization, never a dependency).
 */
@Component
public class Cache {
    private static final Logger log = LoggerFactory.getLogger(Cache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public Cache(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.meters = meters;
    }

    public <T> T get(String key, Duration ttl, JavaType type, Supplier<T> loader) {
        try {
            String hit = redis.opsForValue().get(key);
            if (hit != null) {
                meters.counter("catalog.cache", "result", "hit").increment();
                return json.readValue(hit, type);
            }
        } catch (RuntimeException e) {
            log.warn("cache read failed, falling back to Spanner: {}", e.toString());
        }
        meters.counter("catalog.cache", "result", "miss").increment();
        T value = loader.get();
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (RuntimeException e) {
            log.warn("cache write failed: {}", e.toString());
        }
        return value;
    }

    public JavaType listOf(Class<?> element) {
        return json.getTypeFactory().constructCollectionType(java.util.List.class, element);
    }
}
