package com.visioncart.service.recognition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.AsyncRecognitionResponse;
import com.visioncart.api.dto.CategoryDto;
import com.visioncart.api.dto.PlatformPriceStat;
import com.visioncart.api.dto.ProductSelectionRequest;
import com.visioncart.api.dto.RecognitionCandidate;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
    private final RecognitionImageStorage imageStorage;
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
                                   RecognitionImageStorage imageStorage,
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
        this.imageStorage = imageStorage;
        this.searchOrchestrator = searchOrchestrator;
        this.regionResolver = regionResolver;
    }

    public AsyncRecognitionResponse submitAsync(MultipartFile image, String region, Long userId) {
        String sessionId = UUID.randomUUID().toString();

        byte[] imageBytes;
        try {
            imageBytes = image.getBytes();
        } catch (Exception e) {
            taskManager.createTask(sessionId, null, 0L, userId);
            taskManager.markFailed(sessionId, "读取图片失败");
            throw new IllegalStateException("读取图片失败: " + e.getMessage(), e);
        }

        String originalFilename = image.getOriginalFilename();
        long imageSize = image.getSize();
        String imageHash = HashUtils.sha256Hex(imageBytes);
        taskManager.createTask(sessionId, originalFilename, imageSize, userId, imageHash);
        boolean domestic = regionResolver.isDomestic();

        try {
            CompletableFuture.runAsync(() -> doRecognize(sessionId, imageBytes, region, originalFilename, imageSize, imageHash, userId, domestic),
                    recognitionExecutor);
        } catch (RejectedExecutionException error) {
            taskManager.markFailed(sessionId, "服务繁忙，请稍后重试");
            throw error;
        }

        return new AsyncRecognitionResponse(
                sessionId,
                "PROCESSING",
                WS_TOPIC + sessionId,
                properties.getRecognition().getTimeoutMs() * (properties.getRecognition().getRetryCount() + 1) + 5000
        );
    }

    public RecognitionTaskResult getStatus(String sessionId, Long userId) {
        if (!taskManager.belongsToUser(sessionId, userId)) {
            return null;
        }
        return taskManager.getStatus(sessionId);
    }

    public RecognitionTaskResult getStatus(String sessionId) {
        return taskManager.getStatus(sessionId);
    }

    public AsyncRecognitionResponse selectProduct(String sessionId, ProductSelectionRequest request, Long userId) {
        if (!taskManager.belongsToUser(sessionId, userId)) {
            throw new SecurityException("无权访问该识别任务");
        }
        RecognitionCandidate candidate = taskManager.getCandidate(sessionId, request.candidateId());
        byte[] crop = taskManager.getCandidateCrop(sessionId, request.candidateId());
        if (candidate == null || crop == null) {
            throw new IllegalStateException("候选商品不存在或已过期");
        }

        taskManager.markProcessing(sessionId);
        boolean domestic = regionResolver.isDomestic();
        String originalFilename = taskManager.getOriginalFilename(sessionId);
        long imageSize = taskManager.getImageSize(sessionId);
        String imageHash = taskManager.getImageHash(sessionId);
        try {
            CompletableFuture.runAsync(
                    () -> completeSelectedProduct(sessionId, crop, candidate, originalFilename, imageSize, imageHash, userId, domestic),
                    recognitionExecutor
            );
        } catch (RejectedExecutionException error) {
            taskManager.markFailed(sessionId, "服务繁忙，请稍后重试");
            throw error;
        }

        return new AsyncRecognitionResponse(
                sessionId,
                "PROCESSING",
                WS_TOPIC + sessionId,
                properties.getRecognition().getTimeoutMs() * (properties.getRecognition().getRetryCount() + 1) + 5000
        );
    }

    private void doRecognize(String sessionId, byte[] imageBytes, String region,
                             String originalFilename, long imageSize, String imageHash, Long userId, boolean domestic) {
        try {
            byte[] processed = imageProcessor.process(imageBytes);
            String contentType = "image/jpeg";

            if (visionClient.supportsTwoStageRecognition()) {
                boolean waitingForSelection = doTwoStageRecognize(sessionId, processed, contentType, region,
                        originalFilename, imageSize, imageHash, userId, domestic);
                if (waitingForSelection) {
                    return;
                }
            } else {
                RecognitionResult result;
                try {
                    result = executeWithRetry(sessionId, "single", () -> visionClient.analyze(processed, contentType, region));
                } catch (Exception error) {
                    log.error("Async recognition failed after retries for session {}, using fallback", sessionId, error);
                    result = fallbackResult(originalFilename, sessionId);
                }
                completeRecognition(sessionId, result, originalFilename, imageSize, imageHash, userId, domestic, processed);
            }

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

    private boolean doTwoStageRecognize(String sessionId,
                                        byte[] processed,
                                        String contentType,
                                        String region,
                                        String originalFilename,
                                        long imageSize,
                                        String imageHash,
                                        Long userId,
                                        boolean domestic) throws Exception {
        List<RecognitionCandidate> detected = executeWithRetry(sessionId, "detect",
                () -> visionClient.detectProducts(processed, contentType, region));
        log.info("Session {} detection: raw={} candidates, minConfidence={}", sessionId,
                detected != null ? detected.size() : 0, properties.getRecognition().getMinDetectionConfidence());
        if (detected != null) {
            for (RecognitionCandidate c : detected) {
                log.info("  detected: category={}, brand={}, confidence={}", c.category(), c.brand(), c.confidence());
            }
        }
        List<CandidateWork> candidates = prepareCandidates(processed, detected);
        log.info("Session {} after filtering: {} candidates passed threshold", sessionId, candidates.size());
        if (candidates.isEmpty()) {
            throw new IllegalStateException("未检测到商品，请重新拍照或裁剪后再试");
        }

        int threshold = Math.max(2, properties.getRecognition().getMultiProductThreshold());
        if (candidates.size() >= threshold) {
            List<RecognitionCandidate> visibleCandidates = candidates.stream().map(CandidateWork::candidate).toList();
            Map<String, byte[]> cropImages = new LinkedHashMap<>();
            candidates.forEach(candidate -> cropImages.put(candidate.candidate().candidateId(), candidate.cropBytes()));
            taskManager.markMultiProductPending(sessionId, visibleCandidates, cropImages);
            messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                    sessionId, "MULTI_PRODUCT_PENDING", null, null, null, null, visibleCandidates
            ));
            return true;
        }

        CandidateWork selected = candidates.get(0);
        RecognitionResult result = extractAttributesWithFallback(sessionId, selected.cropBytes(), contentType, selected.candidate());
        completeRecognition(sessionId, result, originalFilename, imageSize, imageHash, userId, domestic, selected.cropBytes());
        return false;
    }

    private void completeSelectedProduct(String sessionId,
                                         byte[] crop,
                                         RecognitionCandidate candidate,
                                         String originalFilename,
                                         long imageSize,
                                         String imageHash,
                                         Long userId,
                                         boolean domestic) {
        try {
            RecognitionResult result = extractAttributesWithFallback(sessionId, crop, "image/jpeg", candidate);
            completeRecognition(sessionId, result, originalFilename, imageSize, imageHash, userId, domestic, crop);
        } catch (Exception e) {
            log.error("Selected product recognition failed for session {}", sessionId, e);
            taskManager.markFailed(sessionId, "识别失败: " + e.getMessage());
            messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                    sessionId, "FAILED", null, "识别失败: " + e.getMessage(), null, Instant.now()
            ));
        }
    }

    private RecognitionResult extractAttributesWithFallback(String sessionId,
                                                            byte[] crop,
                                                            String contentType,
                                                            RecognitionCandidate candidate) {
        try {
            return executeWithRetry(sessionId, "attributes", () ->
                    visionClient.extractAttributes(crop, contentType, candidate.category(), candidate.brand(), "裁剪商品区域"));
        } catch (Exception error) {
            log.warn("Plus attribute extraction failed for session {}, using detection fallback: {}",
                    sessionId, error.getMessage());
            return fallbackFromCandidate(candidate, sessionId);
        }
    }

    private void completeRecognition(String sessionId,
                                     RecognitionResult result,
                                     String originalFilename,
                                     long imageSize,
                                     String imageHash,
                                     Long userId,
                                     boolean domestic,
                                     byte[] historyImageBytes) {
        RecognitionResult withSession = withSessionId(result, sessionId);
        saveHistory(withSession, originalFilename, imageSize, imageHash, userId, historyImageBytes);
        RecognitionResult enriched = enrichWithPlatformStats(withSession, domestic);
        taskManager.markCompleted(sessionId, enriched);
        messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                sessionId, "COMPLETED", enriched, null, null, Instant.now()
        ));
    }

    private List<CandidateWork> prepareCandidates(byte[] imageBytes, List<RecognitionCandidate> detected) {
        if (detected == null || detected.isEmpty()) {
            return List.of();
        }
        List<CandidateWork> candidates = new ArrayList<>();
        double minConfidence = properties.getRecognition().getMinDetectionConfidence();
        int index = 1;
        for (RecognitionCandidate candidate : detected) {
            if (candidate.confidence() < minConfidence) {
                continue;
            }
            try {
                ImageProcessor.CroppedImage crop = imageProcessor.cropToJpeg(imageBytes, candidate.bbox());
                String candidateId = "candidate-" + index++;
                String preview = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(crop.bytes());
                RecognitionCandidate visible = new RecognitionCandidate(
                        candidateId,
                        candidate.bbox(),
                        candidate.category(),
                        candidate.brand(),
                        candidate.confidence(),
                        preview
                );
                candidates.add(new CandidateWork(visible, crop.bytes(), crop.sourceArea()));
            } catch (ImageQualityException e) {
                log.debug("Skipping invalid detected bbox {}: {}", candidate.bbox(), e.getReason());
            }
        }
        return candidates.stream()
                .sorted(Comparator
                        .comparingLong(CandidateWork::sourceArea).reversed()
                        .thenComparing((CandidateWork item) -> item.candidate().confidence(), Comparator.reverseOrder()))
                .limit(Math.max(1, properties.getRecognition().getMaxProducts()))
                .toList();
    }

    private <T> T executeWithRetry(String sessionId, String stage, RetryableOperation<T> operation) throws Exception {
        int maxRetry = properties.getRecognition().getRetryCount();
        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                return operation.run();
            } catch (Exception e) {
                lastError = e;
                log.warn("Recognition {} attempt {}/{} failed for session {}: {}",
                        stage, attempt + 1, maxRetry + 1, sessionId, e.getMessage());
                if (!RetryPolicy.isRetryable(e)) {
                    break;
                }
                if (attempt < maxRetry) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(RetryPolicy.backoffDelayMs(attempt, properties.getRecognition().getRetryBaseDelayMs()));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw lastError == null ? new IllegalStateException("识别失败") : lastError;
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

    private RecognitionResult fallbackFromCandidate(RecognitionCandidate candidate, String sessionId) {
        String category = candidate.category() == null || candidate.category().isBlank() ? "未知" : candidate.category();
        String brand = candidate.brand() == null || candidate.brand().isBlank() ? "未知" : candidate.brand();
        return new RecognitionResult(
                sessionId,
                new CategoryDto("商品", category, category, Math.max(0.1, candidate.confidence())),
                Map.of(
                        "品牌", new AttributeValue(brand, "未知".equals(brand) ? 0.3 : candidate.confidence(), false),
                        "颜色", new AttributeValue("未知", 0.3, false),
                        "款式", new AttributeValue("未知", 0.3, false),
                        "材质", new AttributeValue("未知", 0.3, false),
                        "适用人群", new AttributeValue("通用", 0.3, false)
                ),
                List.of(category),
                Math.max(0.1, candidate.confidence())
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

    private void saveHistory(RecognitionResult result, String filename, long size, String imageHash, Long userId, byte[] historyImageBytes) {
        try {
            String imageUrl = imageStorage.historyImageUrl(result.sessionId());
            try {
                imageStorage.saveHistoryImage(result.sessionId(), historyImageBytes);
            } catch (Exception imageError) {
                log.warn("Failed to save recognition history image for session {}: {}", result.sessionId(), imageError.getMessage());
            }

            RecognitionHistory history = new RecognitionHistory();
            history.setSessionId(result.sessionId());
            history.setImageUrl(imageUrl);
            history.setImageHash(imageHash != null ? imageHash : HashUtils.sha256Hex(filename + ":" + size));
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

    private record CandidateWork(RecognitionCandidate candidate, byte[] cropBytes, long sourceArea) {
    }

    @FunctionalInterface
    private interface RetryableOperation<T> {
        T run() throws Exception;
    }
}
