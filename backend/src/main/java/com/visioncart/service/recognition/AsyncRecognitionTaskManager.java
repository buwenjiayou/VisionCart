package com.visioncart.service.recognition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.RecognitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class AsyncRecognitionTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AsyncRecognitionTaskManager.class);
    private static final String KEY_PREFIX = "visioncart:recognition:task:";
    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AsyncRecognitionTaskManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void createTask(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        redisTemplate.opsForHash().putAll(key, Map.of(
                "status", "PROCESSING",
                "createdAt", Instant.now().toString()
        ));
        redisTemplate.expire(key, TTL);
    }

    public void markCompleted(String sessionId, RecognitionResult result) {
        String key = KEY_PREFIX + sessionId;
        try {
            String resultJson = objectMapper.writeValueAsString(result);
            redisTemplate.opsForHash().putAll(key, Map.of(
                    "status", "COMPLETED",
                    "result", resultJson,
                    "completedAt", Instant.now().toString()
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize recognition result for session {}", sessionId, e);
            markFailed(sessionId, "Internal error");
        }
    }

    public void markFailed(String sessionId, String error) {
        String key = KEY_PREFIX + sessionId;
        redisTemplate.opsForHash().putAll(key, Map.of(
                "status", "FAILED",
                "error", error,
                "completedAt", Instant.now().toString()
        ));
    }

    public RecognitionTaskResult getStatus(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return null;
        }

        String status = (String) entries.getOrDefault("status", "UNKNOWN");
        String error = (String) entries.get("error");
        String resultJson = (String) entries.get("result");
        String createdAtStr = (String) entries.get("createdAt");
        String completedAtStr = (String) entries.get("completedAt");

        RecognitionResult result = null;
        if (resultJson != null) {
            try {
                result = objectMapper.readValue(resultJson, RecognitionResult.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize recognition result for session {}", sessionId);
            }
        }

        Instant createdAt = createdAtStr != null ? Instant.parse(createdAtStr) : null;
        Instant completedAt = completedAtStr != null ? Instant.parse(completedAtStr) : null;

        return new RecognitionTaskResult(sessionId, status, result, error, createdAt, completedAt);
    }
}
