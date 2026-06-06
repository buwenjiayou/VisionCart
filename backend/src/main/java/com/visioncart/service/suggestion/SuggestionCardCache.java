package com.visioncart.service.suggestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.SuggestionCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Service
public class SuggestionCardCache {
    private static final Logger log = LoggerFactory.getLogger(SuggestionCardCache.class);
    private static final String KEY_PREFIX = "visioncart:session:suggestion-cards:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SuggestionCardCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(String sessionId, List<SuggestionCard> cards) {
        if (sessionId == null || sessionId.isBlank() || cards == null || cards.isEmpty()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId,
                    objectMapper.writeValueAsString(cards),
                    TTL);
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialize suggestion cards for session {}: {}", sessionId, e.getMessage());
        } catch (Exception e) {
            log.debug("Suggestion card cache unavailable for session {}: {}", sessionId, e.getMessage());
        }
    }

    public List<SuggestionCard> get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
            if (json == null || json.isBlank()) {
                return List.of();
            }
            SuggestionCard[] cards = objectMapper.readValue(json, SuggestionCard[].class);
            return Arrays.asList(cards);
        } catch (Exception e) {
            log.debug("Failed to read suggestion card cache for session {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    public void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.debug("Failed to delete suggestion card cache for session {}: {}", sessionId, e.getMessage());
        }
    }
}
