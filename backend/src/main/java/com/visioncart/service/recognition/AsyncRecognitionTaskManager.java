package com.visioncart.service.recognition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.RecognitionCandidate;
import com.visioncart.api.dto.RecognitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class AsyncRecognitionTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AsyncRecognitionTaskManager.class);
    private static final String KEY_PREFIX = "visioncart:recognition:task:";
    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final com.visioncart.config.VisionCartProperties properties;

    // Local memory fallback when Redis is unavailable (Caffeine auto-expires)
    private final Cache<String, Map<String, String>> localStore = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    public AsyncRecognitionTaskManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                       com.visioncart.config.VisionCartProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    // ==================== Local store helpers ====================

    private Map<String, String> getLocalEntry(String key) {
        return localStore.getIfPresent(key);
    }

    private void putLocal(String key, Map<String, String> fields) {
        localStore.asMap().merge(key, fields, (existing, newFields) -> {
            Map<String, String> merged = new LinkedHashMap<>(existing);
            merged.putAll(newFields);
            return merged;
        });
    }

    private void putFieldLocal(String key, String field, String value) {
        localStore.asMap().compute(key, (k, existing) -> {
            Map<String, String> merged = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();
            merged.put(field, value);
            return merged;
        });
    }

    private void deleteFieldsLocal(String key, String... fields) {
        localStore.asMap().computeIfPresent(key, (k, v) -> {
            for (String f : fields) v.remove(f);
            return v;
        });
    }

    private String getFieldLocal(String key, String field) {
        Map<String, String> entry = getLocalEntry(key);
        return entry != null ? entry.get(field) : null;
    }

    private Map<Object, Object> getAllLocal(String key) {
        Map<String, String> entry = getLocalEntry(key);
        if (entry == null) return Map.of();
        Map<Object, Object> result = new LinkedHashMap<>();
        entry.forEach((k, v) -> result.put(k, v));
        return result;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000) // Every 5 minutes
    public void cleanupLocalStore() {
        int before = (int) localStore.estimatedSize();
        localStore.cleanUp();
        int after = (int) localStore.estimatedSize();
        int removed = before - after;
        if (removed > 0) {
            log.debug("Cleaned up {} expired entries from local recognition task store", removed);
        }
    }

    // ==================== Public API ====================

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

        try {
            redisTemplate.opsForHash().putAll(key, fields);
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("Redis unavailable for createTask, using local memory: {}", e.getMessage());
            putLocal(key, fields);
        }
    }

    public void markProcessing(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        try {
            redisTemplate.opsForHash().put(key, "status", "PROCESSING");
            redisTemplate.opsForHash().delete(key, "error", "completedAt");
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("Redis unavailable for markProcessing, using local memory: {}", e.getMessage());
            putFieldLocal(key, "status", "PROCESSING");
            deleteFieldsLocal(key, "error", "completedAt");
        }
    }

    public void markProgress(String sessionId, String progressStep) {
        String key = KEY_PREFIX + sessionId;
        try {
            redisTemplate.opsForHash().put(key, "progressStep", progressStep);
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            putFieldLocal(key, "progressStep", progressStep);
        }
    }

    public void markCompleted(String sessionId, RecognitionResult result) {
        // Don't overwrite if already terminal (e.g., timed out and marked FAILED)
        if (isTerminal(sessionId)) {
            log.info("Skipping markCompleted for session {} — already terminal", sessionId);
            return;
        }
        String key = KEY_PREFIX + sessionId;
        try {
            String resultJson = objectMapper.writeValueAsString(result);
            try {
                redisTemplate.opsForHash().putAll(key, Map.of(
                        "status", "COMPLETED",
                        "result", resultJson,
                        "completedAt", Instant.now().toString()
                ));
                redisTemplate.opsForHash().delete(key, "error");
                redisTemplate.expire(key, TTL);
            } catch (Exception redisEx) {
                log.warn("Redis unavailable for markCompleted, using local memory: {}", redisEx.getMessage());
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("status", "COMPLETED");
                fields.put("result", resultJson);
                fields.put("completedAt", Instant.now().toString());
                putLocal(key, fields);
                deleteFieldsLocal(key, "error");
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize recognition result for session {}", sessionId, e);
            markFailed(sessionId, "Internal error");
        }
    }

    public void markMultiProductPending(String sessionId,
                                        List<RecognitionCandidate> candidates,
                                        Map<String, String> cropFilePaths) {
        String key = KEY_PREFIX + sessionId;
        try {
            String candidatesJson = objectMapper.writeValueAsString(candidates);
            try {
                redisTemplate.opsForHash().putAll(key, Map.of(
                        "status", "MULTI_PRODUCT_PENDING",
                        "candidates", candidatesJson
                ));
                redisTemplate.opsForHash().delete(key, "result", "error", "completedAt");
                cropFilePaths.forEach((candidateId, path) ->
                        redisTemplate.opsForHash().put(key, "crop_path:" + candidateId, path));
                redisTemplate.expire(key, TTL);
            } catch (Exception redisEx) {
                log.warn("Redis unavailable for markMultiProductPending, using local memory: {}", redisEx.getMessage());
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("status", "MULTI_PRODUCT_PENDING");
                fields.put("candidates", candidatesJson);
                putLocal(key, fields);
                deleteFieldsLocal(key, "result", "error", "completedAt");
                cropFilePaths.forEach((candidateId, path) ->
                        putFieldLocal(key, "crop_path:" + candidateId, path));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize recognition candidates for session {}", sessionId, e);
            markFailed(sessionId, "Internal error");
        }
    }

    public void updateResult(String sessionId, RecognitionResult result) {
        String key = KEY_PREFIX + sessionId;
        try {
            String resultJson = objectMapper.writeValueAsString(result);
            try {
                redisTemplate.opsForHash().put(key, "result", resultJson);
                redisTemplate.expire(key, TTL);
            } catch (Exception redisEx) {
                log.warn("Redis unavailable for updateResult, using local memory: {}", redisEx.getMessage());
                putFieldLocal(key, "result", resultJson);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to update result for session {}", sessionId, e);
        }
    }

    public void markFailed(String sessionId, String error) {
        String key = KEY_PREFIX + sessionId;
        try {
            redisTemplate.opsForHash().putAll(key, Map.of(
                    "status", "FAILED",
                    "error", error,
                    "completedAt", Instant.now().toString()
            ));
            redisTemplate.opsForHash().delete(key, "result");
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("Redis unavailable for markFailed, using local memory: {}", e.getMessage());
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("status", "FAILED");
            fields.put("error", error);
            fields.put("completedAt", Instant.now().toString());
            putLocal(key, fields);
            deleteFieldsLocal(key, "result");
        }
    }

    public boolean isTerminal(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        try {
            Object status = redisTemplate.opsForHash().get(key, "status");
            return "COMPLETED".equals(status) || "FAILED".equals(status);
        } catch (Exception e) {
            String status = getFieldLocal(key, "status");
            return "COMPLETED".equals(status) || "FAILED".equals(status);
        }
    }

    public boolean isFailed(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        try {
            Object status = redisTemplate.opsForHash().get(key, "status");
            return "FAILED".equals(status);
        } catch (Exception e) {
            return "FAILED".equals(getFieldLocal(key, "status"));
        }
    }

    /**
     * Resolve crop path: if relative, resolve against historyImageDir; if absolute, use as-is.
     */
    private java.nio.file.Path resolveCropPath(String storedPath) {
        java.nio.file.Path path = java.nio.file.Path.of(storedPath);
        if (path.isAbsolute()) {
            return path;
        }
        // Relative path — resolve against configured historyImageDir
        String baseDir = properties.getRecognition().getHistoryImageDir();
        return java.nio.file.Path.of(baseDir).toAbsolutePath().normalize().resolve(path);
    }

    public byte[] getCandidateCrop(String sessionId, String candidateId) {
        String key = KEY_PREFIX + sessionId;
        // Try file-based storage first (new approach)
        try {
            Object filePath = redisTemplate.opsForHash().get(key, "crop_path:" + candidateId);
            if (filePath != null) {
                java.nio.file.Path path = resolveCropPath(String.valueOf(filePath));
                if (java.nio.file.Files.isRegularFile(path)) {
                    return java.nio.file.Files.readAllBytes(path);
                }
            }
        } catch (Exception e) {
            String localPath = getFieldLocal(key, "crop_path:" + candidateId);
            if (localPath != null) {
                try {
                    java.nio.file.Path path = resolveCropPath(localPath);
                    if (java.nio.file.Files.isRegularFile(path)) {
                        return java.nio.file.Files.readAllBytes(path);
                    }
                } catch (Exception ex) {
                    log.warn("Failed to load crop from disk for session {} candidate {}: {}", sessionId, candidateId, ex.getMessage());
                }
            }
        }
        // Fallback: legacy Base64 storage
        try {
            Object encoded = redisTemplate.opsForHash().get(key, "crop:" + candidateId);
            if (encoded == null) return null;
            return Base64.getDecoder().decode(String.valueOf(encoded));
        } catch (Exception e) {
            String encoded = getFieldLocal(key, "crop:" + candidateId);
            if (encoded == null) return null;
            return Base64.getDecoder().decode(encoded);
        }
    }

    public RecognitionCandidate getCandidate(String sessionId, String candidateId) {
        return getCandidates(sessionId).stream()
                .filter(candidate -> candidate.candidateId().equals(candidateId))
                .findFirst()
                .orElse(null);
    }

    public String getOriginalFilename(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        try {
            Object value = redisTemplate.opsForHash().get(key, "originalFilename");
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return getFieldLocal(key, "originalFilename");
        }
    }

    public long getImageSize(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        String value;
        try {
            Object v = redisTemplate.opsForHash().get(key, "imageSize");
            value = v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            value = getFieldLocal(key, "imageSize");
        }
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public String getImageHash(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        try {
            Object value = redisTemplate.opsForHash().get(key, "imageHash");
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return getFieldLocal(key, "imageHash");
        }
    }

    public Long getUserId(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        String value;
        try {
            Object v = redisTemplate.opsForHash().get(key, "userId");
            value = v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            value = getFieldLocal(key, "userId");
        }
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public boolean belongsToUser(String sessionId, Long userId) {
        Long owner = getUserId(sessionId);
        if (owner == null) return userId == null;
        return owner.equals(userId);
    }

    public List<RecognitionCandidate> getCandidates(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        String candidatesJson;
        try {
            Object v = redisTemplate.opsForHash().get(key, "candidates");
            candidatesJson = v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            candidatesJson = getFieldLocal(key, "candidates");
        }
        if (candidatesJson == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(candidatesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, RecognitionCandidate.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize recognition candidates for session {}", sessionId);
            return List.of();
        }
    }

    public RecognitionTaskResult getStatus(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        Map<Object, Object> entries;
        try {
            entries = redisTemplate.opsForHash().entries(key);
        } catch (Exception e) {
            entries = getAllLocal(key);
        }
        if (entries.isEmpty()) {
            return null;
        }

        String status = (String) entries.getOrDefault("status", "UNKNOWN");
        String error = (String) entries.get("error");
        String resultJson = (String) entries.get("result");
        String createdAtStr = (String) entries.get("createdAt");
        String completedAtStr = (String) entries.get("completedAt");
        String candidatesJson = (String) entries.get("candidates");
        String progressStep = (String) entries.get("progressStep");

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

        return new RecognitionTaskResult(sessionId, status, result, error, createdAt, completedAt, candidates, progressStep);
    }
}
