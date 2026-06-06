package com.visioncart.service.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.CandidateLightweight;
import com.visioncart.api.dto.ProductCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Session-level candidate cache for NLP filtering.
 * Stores lightweight candidates (no imageUrl/detailUrl) to reduce Redis memory.
 * Full ProductCard is built on-demand for frontend display.
 */
@Service
public class CandidateSessionCache {

    private static final Logger log = LoggerFactory.getLogger(CandidateSessionCache.class);
    private static final String KEY_PREFIX = "visioncart:session:candidates:";
    private static final int TTL_MINUTES = 30;
    private static final int MAX_CANDIDATES = 1000;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CandidateSessionCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Save ranked candidates for a session as lightweight objects.
     * Truncates to MAX_CANDIDATES.
     * Preserves mainCategoryCode + productRole from ProductCard for NLP filtering.
     */
    public void saveCandidates(String sessionId, List<ProductCard> candidates) {
        if (sessionId == null || sessionId.isBlank() || candidates == null || candidates.isEmpty()) {
            return;
        }
        try {
            List<CandidateLightweight> lightweight = candidates.stream()
                    .limit(MAX_CANDIDATES)
                    .map(card -> CandidateLightweight.from(card, card.similarity()))
                    .toList();
            String json = objectMapper.writeValueAsString(lightweight);
            String key = KEY_PREFIX + sessionId;
            redisTemplate.opsForValue().set(key, json, TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("Cached {} lightweight candidates for session {}", lightweight.size(), sessionId);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize candidates for session {}: {}", sessionId, e.getMessage());
        } catch (Exception e) {
            log.warn("Redis unavailable for candidate cache (session {}): {}", sessionId, e.getMessage());
        }
    }

    /**
     * Retrieve cached candidates as lightweight objects.
     */
    public List<CandidateLightweight> getLightweightCandidates(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        try {
            String key = KEY_PREFIX + sessionId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to read candidate cache for session {}: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Retrieve cached candidates converted to ProductCard (without imageUrl/detailUrl).
     * Use this for filtering; build full ProductCard for frontend display.
     */
    public List<ProductCard> getCandidates(String sessionId) {
        return getLightweightCandidates(sessionId).stream()
                .map(CandidateLightweight::toProductCard)
                .toList();
    }

    /**
     * Check if candidates exist for a session.
     */
    public boolean exists(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sessionId));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Delete cached candidates for a session.
     */
    public void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("Failed to delete candidate cache for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Get the count of cached candidates.
     */
    public int getCandidateCount(String sessionId) {
        return getLightweightCandidates(sessionId).size();
    }
}
