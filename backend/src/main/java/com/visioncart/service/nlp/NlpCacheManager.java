package com.visioncart.service.nlp;

import com.visioncart.api.dto.SearchFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NlpCacheManager {
    private final StringRedisTemplate redisTemplate;
    private final CacheKeyGenerator cacheKeyGenerator;
    private final Map<String, SearchFilter> memoryCache = new ConcurrentHashMap<>();

    public NlpCacheManager(StringRedisTemplate redisTemplate, CacheKeyGenerator cacheKeyGenerator) {
        this.redisTemplate = redisTemplate;
        this.cacheKeyGenerator = cacheKeyGenerator;
    }

    public Optional<SearchFilter> get(String key) {
        SearchFilter local = memoryCache.get(key);
        if (local != null) {
            return Optional.of(local);
        }
        try {
            String json = redisTemplate.opsForValue().get(redisKey(key));
            return json == null ? Optional.empty() : Optional.of(cacheKeyGenerator.fromJson(json));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    public void put(String key, SearchFilter filter) {
        memoryCache.put(key, filter);
        try {
            redisTemplate.opsForValue().set(redisKey(key), cacheKeyGenerator.toJson(filter), Duration.ofHours(24));
        } catch (Exception ignored) {
            // Local memory cache keeps development resilient when Redis is temporarily absent.
        }
    }

    private String redisKey(String key) {
        return "nlp:cache:" + key;
    }
}
