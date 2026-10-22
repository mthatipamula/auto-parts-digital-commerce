package com.autoparts.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deliberate performance problems for practicing JVM diagnostics (see docs/jvm-diagnostics.md). Disabled unless
 * platform.lab.enabled=true, and admin-only. Never enable in production.
 */
@RestController
@RequestMapping("/api/catalog/lab")
@ConditionalOnProperty(name = "platform.lab.enabled", havingValue = "true")
@PreAuthorize("hasRole('admin')")
public class JvmLab {
    private static final List<byte[]> LEAK = new ArrayList<>();       // a static collection that only grows
    private static final Object LOCK = new Object();

    /** Memory leak: retain N MB per call. Find it with a heap dump (dominator tree -> JvmLab.LEAK). */
    @PostMapping("/leak")
    public Map<String, Object> leak(@RequestParam(defaultValue = "50") int mb) {
        for (int i = 0; i < Math.min(mb, 500); i++) LEAK.add(new byte[1024 * 1024]);
        return Map.of("retainedMb", LEAK.size(), "heapUsedMb", used());
    }

    @DeleteMapping("/leak")
    public Map<String, Object> clear() {
        LEAK.clear();
        return Map.of("retainedMb", 0);
    }

    /** CPU hot spot: shows up as a wide frame in a flame graph (JFR / async-profiler). */
    @PostMapping("/cpu")
    public Map<String, Object> cpu(@RequestParam(defaultValue = "2000") long ms) {
        long end = System.nanoTime() + Math.min(ms, 30_000) * 1_000_000;
        double x = 0;
        while (System.nanoTime() < end) x += Math.sqrt(x + 1);
        return Map.of("result", x);
    }

    /** Lock contention: concurrent calls queue on one monitor; a thread dump shows threads BLOCKED on JvmLab.LOCK. */
    @PostMapping("/contention")
    public Map<String, Object> contention(@RequestParam(defaultValue = "500") long ms) throws InterruptedException {
        synchronized (LOCK) {
            Thread.sleep(Math.min(ms, 10_000));
        }
        return Map.of("heldMs", ms);
    }

    private static long used() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
    }
}
