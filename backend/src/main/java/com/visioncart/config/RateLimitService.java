package com.visioncart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final String KEY_PREFIX = "visioncart:rate:";

    private final StringRedisTemplate redisTemplate;
    private final VisionCartProperties properties;
    private final Map<String, LocalCounter> localCounters = new ConcurrentHashMap<>();

    public RateLimitService(StringRedisTemplate redisTemplate, VisionCartProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public boolean allow(String scope, String identity, int limit, int windowSeconds) {
        if (!properties.getRateLimit().isEnabled()) {
            return true;
        }
        int safeLimit = Math.max(1, limit);
        int safeWindow = Math.max(1, windowSeconds);
        long now = Instant.now().getEpochSecond();
        long windowStart = now / safeWindow;
        String key = KEY_PREFIX + scope + ":" + identity + ":" + windowStart;

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, safeWindow + 2L, TimeUnit.SECONDS);
            }
            return count == null || count <= safeLimit;
        } catch (Exception error) {
            log.warn("Redis unavailable for rate limit, using local fallback: {}", error.getMessage());
            return allowLocal(key, safeLimit, now + safeWindow + 2L);
        }
    }

    private boolean allowLocal(String key, int limit, long expiresAtEpochSecond) {
        trimIfNeeded();
        LocalCounter counter = localCounters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.expiresAtEpochSecond < Instant.now().getEpochSecond()) {
                return new LocalCounter(1, expiresAtEpochSecond);
            }
            existing.count++;
            return existing;
        });
        return counter.count <= limit;
    }

    private void trimIfNeeded() {
        int maxKeys = Math.max(100, properties.getRateLimit().getMaxLocalKeys());
        if (localCounters.size() <= maxKeys) {
            return;
        }
        cleanupExpired();
        if (localCounters.size() <= maxKeys) {
            return;
        }
        localCounters.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAtEpochSecond))
                .limit(Math.max(1, localCounters.size() - maxKeys))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(localCounters::remove);
    }

    @Scheduled(fixedDelay = 300_000)
    void cleanupExpired() {
        long now = Instant.now().getEpochSecond();
        localCounters.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochSecond < now);
    }

    private static class LocalCounter {
        int count;
        final long expiresAtEpochSecond;

        LocalCounter(int count, long expiresAtEpochSecond) {
            this.count = count;
            this.expiresAtEpochSecond = expiresAtEpochSecond;
        }
    }
}
