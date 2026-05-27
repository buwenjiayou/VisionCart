package com.visioncart.service.recognition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.AsyncRecognitionResponse;
import com.visioncart.api.dto.PlatformPriceStat;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.api.dto.SearchRequest;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.service.ai.HashUtils;
import com.visioncart.service.ai.RetryPolicy;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.search.RegionResolver;
import com.visioncart.service.search.SearchOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Service
public class RecognitionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RecognitionOrchestrator.class);
    private static final String WS_TOPIC = "/topic/recognition/";

    private final ImageProcessor imageProcessor;
    private final VisionModelService visionClient;
    private final AsyncRecognitionTaskManager taskManager;
    private final RecognitionHistoryRepository historyRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService recognitionExecutor;
    private final VisionCartProperties properties;
    private final SearchOrchestrator searchOrchestrator;
    private final RegionResolver regionResolver;

    public RecognitionOrchestrator(ImageProcessor imageProcessor,
                                   VisionModelService visionClient,
                                   AsyncRecognitionTaskManager taskManager,
                                   RecognitionHistoryRepository historyRepository,
                                   SimpMessagingTemplate messagingTemplate,
                                   ObjectMapper objectMapper,
                                   @Qualifier("recognitionExecutor") ExecutorService recognitionExecutor,
                                   VisionCartProperties properties,
                                   SearchOrchestrator searchOrchestrator,
                                   RegionResolver regionResolver) {
        this.imageProcessor = imageProcessor;
        this.visionClient = visionClient;
        this.taskManager = taskManager;
        this.historyRepository = historyRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.recognitionExecutor = recognitionExecutor;
        this.properties = properties;
        this.searchOrchestrator = searchOrchestrator;
        this.regionResolver = regionResolver;
    }

    public AsyncRecognitionResponse submitAsync(MultipartFile image, String region, Long userId) {
        String sessionId = UUID.randomUUID().toString();
        taskManager.createTask(sessionId);

        byte[] imageBytes;
        try {
            imageBytes = image.getBytes();
        } catch (Exception e) {
            taskManager.markFailed(sessionId, "读取图片失败");
            throw new IllegalStateException("读取图片失败: " + e.getMessage(), e);
        }

        String originalFilename = image.getOriginalFilename();
        long imageSize = image.getSize();
        boolean domestic = regionResolver.isDomestic();

        CompletableFuture.runAsync(() -> doRecognize(sessionId, imageBytes, region, originalFilename, imageSize, userId, domestic),
                recognitionExecutor);

        return new AsyncRecognitionResponse(
                sessionId,
                "PROCESSING",
                WS_TOPIC + sessionId,
                properties.getRecognition().getTimeoutMs() * (properties.getRecognition().getRetryCount() + 1) + 5000
        );
    }

    public RecognitionTaskResult getStatus(String sessionId) {
        return taskManager.getStatus(sessionId);
    }

    private void doRecognize(String sessionId, byte[] imageBytes, String region,
                             String originalFilename, long imageSize, Long userId, boolean domestic) {
        try {
            byte[] processed = imageProcessor.process(imageBytes);
            String contentType = "image/jpeg";

            int maxRetry = properties.getRecognition().getRetryCount();
            RecognitionResult result = null;
            Exception lastError = null;

            for (int attempt = 0; attempt <= maxRetry; attempt++) {
                try {
                    result = visionClient.analyze(processed, contentType, region);
                    break;
                } catch (Exception e) {
                    lastError = e;
                    log.warn("Async recognition attempt {}/{} failed for session {}: {}",
                            attempt + 1, maxRetry + 1, sessionId, e.getMessage());
                    if (!RetryPolicy.isRetryable(e)) {
                        log.warn("Non-retryable error, skipping remaining attempts for session {}", sessionId);
                        break;
                    }
                    if (attempt < maxRetry) {
                        try {
                            Thread.sleep(RetryPolicy.backoffDelayMs(attempt, 1000L));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            if (result == null) {
                log.error("Async recognition failed after {} attempts for session {}, using fallback",
                        maxRetry + 1, sessionId);
                result = fallbackResult(originalFilename, sessionId);
            } else {
                result = withSessionId(result, sessionId);
            }

            saveHistory(result, originalFilename, imageSize, userId);

            // 识别完成后搜索各平台均价
            RecognitionResult enriched = enrichWithPlatformStats(result, domestic);

            taskManager.markCompleted(sessionId, enriched);
            messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                    sessionId, "COMPLETED", enriched, null, null, Instant.now()
            ));

        } catch (ImageQualityException e) {
            log.warn("Image quality rejected for session {}: {}", sessionId, e.getReason());
            taskManager.markFailed(sessionId, e.getMessage());
            messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                    sessionId, "FAILED", null, e.getMessage(), null, Instant.now()
            ));
        } catch (Exception e) {
            log.error("Async recognition failed for session {}", sessionId, e);
            taskManager.markFailed(sessionId, "识别失败: " + e.getMessage());
            messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                    sessionId, "FAILED", null, "识别失败: " + e.getMessage(), null, Instant.now()
            ));
        }
    }

    private RecognitionResult enrichWithPlatformStats(RecognitionResult result, boolean domestic) {
        try {
            SearchResult searchResult = searchOrchestrator.search(new SearchRequest(
                    result.sessionId(), RecognitionSearchMapper.toSearchAttributes(result), null, 1, 20, "recognition"
            ), domestic);

            List<PlatformPriceStat> stats = searchResult.platformStats();
            if (stats != null && !stats.isEmpty()) {
                return new RecognitionResult(
                        result.sessionId(), result.category(), result.attributes(),
                        result.keywords(), result.overallConfidence(), stats);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch platform stats for session {}: {}", result.sessionId(), e.getMessage());
        }
        return result;
    }

    private RecognitionResult fallbackResult(String filename, String sessionId) {
        String hint = filename != null ? filename.replaceAll("\\.[^.]+$", "") : "未知商品";
        return new RecognitionResult(
                sessionId,
                new com.visioncart.api.dto.CategoryDto("未知", "未知", "未知", 0.1),
                Map.of(
                        "品牌", new com.visioncart.api.dto.AttributeValue("未知", 0.1, false),
                        "颜色", new com.visioncart.api.dto.AttributeValue("未知", 0.1, false),
                        "款式", new com.visioncart.api.dto.AttributeValue("未知", 0.1, false)
                ),
                List.of(hint),
                0.1
        );
    }

    private RecognitionResult withSessionId(RecognitionResult result, String sessionId) {
        return new RecognitionResult(
                sessionId,
                result.category(),
                result.attributes(),
                result.keywords(),
                result.overallConfidence(),
                result.platformStats()
        );
    }

    private void saveHistory(RecognitionResult result, String filename, long size, Long userId) {
        try {
            RecognitionHistory history = new RecognitionHistory();
            history.setSessionId(result.sessionId());
            history.setImageUrl("upload://" + result.sessionId());
            history.setImageHash(HashUtils.sha256Hex(filename + ":" + size));
            history.setCategoryJson(objectMapper.writeValueAsString(result.category()));
            history.setAttributesJson(objectMapper.writeValueAsString(result.attributes()));
            history.setKeywords(String.join(",", result.keywords()));
            history.setConfidence(result.overallConfidence());
            history.setUserId(userId);
            history.setCreatedAt(Instant.now());
            historyRepository.save(history);
        } catch (JsonProcessingException e) {
            log.error("Failed to save recognition history for session {}", result.sessionId(), e);
        }
    }
}
