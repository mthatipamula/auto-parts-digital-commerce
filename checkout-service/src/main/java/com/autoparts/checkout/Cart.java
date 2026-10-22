package com.autoparts.checkout;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shopping cart in Memorystore (Valkey): a hash of SKU -> quantity per customer, expiring after a week idle. */
@Component
public class Cart {
    private static final Duration TTL = Duration.ofDays(7);
    private final StringRedisTemplate redis;

    public Cart(StringRedisTemplate redis) { this.redis = redis; }

    private static String key(String customerId) { return "cart:" + customerId; }

    public Map<String, Integer> items(String customerId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        redis.<String, String>opsForHash().entries(key(customerId)).forEach((k, v) -> out.put(k, Integer.parseInt(v)));
        return out;
    }

    public void set(String customerId, String sku, int quantity) {
        if (quantity <= 0) {
            redis.opsForHash().delete(key(customerId), sku);
        } else {
            redis.opsForHash().put(key(customerId), sku, Integer.toString(quantity));
        }
        redis.expire(key(customerId), TTL);
    }

    public void clear(String customerId) { redis.delete(key(customerId)); }
}
