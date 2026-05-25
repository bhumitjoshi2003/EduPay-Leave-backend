package com.indraacademy.ias_management.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private final ConcurrentHashMap<String, RateEntry> entries = new ConcurrentHashMap<>();

    public boolean isRateLimited(String key, int maxAttempts, long windowMillis) {
        long now = System.currentTimeMillis();
        RateEntry entry = entries.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart > windowMillis) {
                return new RateEntry(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        return entry.count.get() > maxAttempts;
    }

    private static class RateEntry {
        final long windowStart;
        final AtomicInteger count;

        RateEntry(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
