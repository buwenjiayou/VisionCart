package com.visioncart.service.recognition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.RecognitionCandidate;
import com.visioncart.api.dto.RecognitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
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
        createTask(sessionId, null, 0L, null);
    }

    public void createTask(String sessionId, String originalFilename, long imageSize) {
        createTask(sessionId, originalFilename, imageSize, null);
    }

    public void createTask(String sessionId, String originalFilename, long imageSize, Long userId) {
        createTask(sessionId, originalFilename, imageSize, userId, null);
    }

    public void createTask(String sessionId, String originalFilename, long imageSize, Long userId, String imageHash) {
        String key = KEY_PREFIX + sessionId;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", "PROCESSING");
        fields.put("createdAt", Instant.now().toString());
        if (userId != null) {
            fields.put("userId", String.valueOf(userId));
        }
        if (imageHash != null) {
            fields.put("imageHash", imageHash);
        }
        if (originalFilename != null) {
            fields.put("originalFilename", originalFilename);
        }
        fields.put("imageSize", String.valueOf(imageSize));
        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, TTL);
    }

    public void markProcessing(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        redisTemplate.opsForHash().put(key, "status", "PROCESSING");
        redisTemplate.opsForHash().delete(key, "error", "completedAt");
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
            redisTemplate.opsForHash().delete(key, "error");
            redisTemplate.expire(key, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize recognition result for session {}", sessionId, e);
            markFailed(sessionId, "Internal error");
        }
    }

    public void markMultiProductPending(String sessionId,
                                        List<RecognitionCandidate> candidates,
                                        Map<String, byte[]> cropImages) {
        String key = KEY_PREFIX + sessionId;
        try {
            redisTemplate.opsForHash().putAll(key, Map.of(
                    "status", "MULTI_PRODUCT_PENDING",
                    "candidates", objectMapper.writeValueAsString(candidates)
            ));
            redisTemplate.opsForHash().delete(key, "result", "error", "completedAt");
            cropImages.forEach((candidateId, bytes) ->
                    redisTemplate.opsForHash().put(key, "crop:" + candidateId, Base64.getEncoder().encodeToString(bytes)));
            redisTemplate.expire(key, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize recognition candidates for session {}", sessionId, e);
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
        redisTemplate.opsForHash().delete(key, "result");
        redisTemplate.expire(key, TTL);
    }

    public byte[] getCandidateCrop(String sessionId, String candidateId) {
        Object encoded = redisTemplate.opsForHash().get(KEY_PREFIX + sessionId, "crop:" + candidateId);
        if (encoded == null) {
            return null;
        }
        return Base64.getDecoder().decode(String.valueOf(encoded));
    }

    public RecognitionCandidate getCandidate(String sessionId, String candidateId) {
        return getCandidates(sessionId).stream()
                .filter(candidate -> candidate.candidateId().equals(candidateId))
                .findFirst()
                .orElse(null);
    }

    public String getOriginalFilename(String sessionId) {
        Object value = redisTemplate.opsForHash().get(KEY_PREFIX + sessionId, "originalFilename");
        return value == null ? null : String.valueOf(value);
    }

    public long getImageSize(String sessionId) {
        Object value = redisTemplate.opsForHash().get(KEY_PREFIX + sessionId, "imageSize");
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public String getImageHash(String sessionId) {
        Object value = redisTemplate.opsForHash().get(KEY_PREFIX + sessionId, "imageHash");
        return value == null ? null : String.valueOf(value);
    }

    public Long getUserId(String sessionId) {
        Object value = redisTemplate.opsForHash().get(KEY_PREFIX + sessionId, "userId");
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public boolean belongsToUser(String sessionId, Long userId) {
        Long owner = getUserId(sessionId);
        return owner != null && owner.equals(userId);
    }

    public List<RecognitionCandidate> getCandidates(String sessionId) {
        Object candidatesJson = redisTemplate.opsForHash().get(KEY_PREFIX + sessionId, "candidates");
        if (candidatesJson == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(String.valueOf(candidatesJson),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, RecognitionCandidate.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize recognition candidates for session {}", sessionId);
            return List.of();
        }
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
        String candidatesJson = (String) entries.get("candidates");

        RecognitionResult result = null;
        if (resultJson != null) {
            try {
                result = objectMapper.readValue(resultJson, RecognitionResult.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize recognition result for session {}", sessionId);
            }
        }

        List<RecognitionCandidate> candidates = null;
        if (candidatesJson != null) {
            try {
                candidates = objectMapper.readValue(candidatesJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, RecognitionCandidate.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize recognition candidates for session {}", sessionId);
            }
        }

        Instant createdAt = createdAtStr != null ? Instant.parse(createdAtStr) : null;
        Instant completedAt = completedAtStr != null ? Instant.parse(completedAtStr) : null;

        return new RecognitionTaskResult(sessionId, status, result, error, createdAt, completedAt, candidates);
    }
}
