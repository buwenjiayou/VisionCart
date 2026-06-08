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
import org.apache.commons.lang3.StringUtils;
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
import java.util.HashMap;
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
    private final ExecutorService searchExecutor;
    private final VisionCartProperties properties;
    private final RecognitionImageStorage imageStorage;
    private final SearchOrchestrator searchOrchestrator;
    private final RegionResolver regionResolver;
    private final com.visioncart.service.metrics.PerformanceMetricsService metricsService;

    public RecognitionOrchestrator(ImageProcessor imageProcessor,
                                   VisionModelService visionClient,
                                   AsyncRecognitionTaskManager taskManager,
                                   RecognitionHistoryRepository historyRepository,
                                   SimpMessagingTemplate messagingTemplate,
                                   ObjectMapper objectMapper,
                                   @Qualifier("recognitionExecutor") ExecutorService recognitionExecutor,
                                   @Qualifier("searchExecutor") ExecutorService searchExecutor,
                                   VisionCartProperties properties,
                                   RecognitionImageStorage imageStorage,
                                   SearchOrchestrator searchOrchestrator,
                                   RegionResolver regionResolver,
                                   com.visioncart.service.metrics.PerformanceMetricsService metricsService) {
        this.imageProcessor = imageProcessor;
        this.visionClient = visionClient;
        this.taskManager = taskManager;
        this.historyRepository = historyRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.recognitionExecutor = recognitionExecutor;
        this.searchExecutor = searchExecutor;
        this.properties = properties;
        this.imageStorage = imageStorage;
        this.searchOrchestrator = searchOrchestrator;
        this.regionResolver = regionResolver;
        this.metricsService = metricsService;
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
            long timeoutMs = properties.getRecognition().getTimeoutMs() * (properties.getRecognition().getRetryCount() + 1) + 10000;
            CompletableFuture.runAsync(() -> doRecognize(sessionId, imageBytes, region, originalFilename, imageSize, imageHash, userId, domestic),
                    recognitionExecutor)
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        if (!taskManager.isTerminal(sessionId)) {
                            taskManager.markFailed(sessionId, "识别超时，请重试");
                        }
                        return null;
                    });
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
        if (candidate == null) {
            throw new IllegalStateException("候选商品不存在或已过期");
        }
        // Load crop: disk → Redis → re-crop from original image
        byte[] crop = imageStorage.loadCandidateCrop(sessionId, request.candidateId())
                .orElseGet(() -> taskManager.getCandidateCrop(sessionId, request.candidateId()));
        if (crop == null && candidate.bbox() != null && !candidate.bbox().isEmpty()) {
            log.info("Candidate crop missing for {}, re-cropping from original image", request.candidateId());
            crop = imageStorage.loadHistoryImageBytes(sessionId)
                    .map(original -> imageProcessor.cropToJpeg(original, candidate.bbox()).bytes())
                    .orElse(null);
        }
        taskManager.markProcessing(sessionId);
        boolean domestic = regionResolver.isDomestic();
        String originalFilename = taskManager.getOriginalFilename(sessionId);
        long imageSize = taskManager.getImageSize(sessionId);
        String imageHash = taskManager.getImageHash(sessionId);

        // If crop is completely unavailable, use fallback from candidate metadata
        if (crop == null) {
            log.warn("All crop sources exhausted for candidate {}, using fallbackFromCandidate", request.candidateId());
            RecognitionResult fallback = fallbackFromCandidate(candidate, sessionId);
            byte[] historyImage = imageStorage.loadHistoryImageBytes(sessionId).orElse(new byte[0]);
            completeRecognition(sessionId, fallback, originalFilename, imageSize, imageHash, userId, domestic, historyImage);
            return new AsyncRecognitionResponse(sessionId, "PROCESSING", WS_TOPIC + sessionId, 5000);
        }

        final byte[] finalCrop = crop;
        try {
            long timeoutMs = properties.getRecognition().getTimeoutMs() * 2 + 10000;
            CompletableFuture.runAsync(
                    () -> completeSelectedProduct(sessionId, finalCrop, candidate, originalFilename, imageSize, imageHash, userId, domestic),
                    recognitionExecutor
            ).orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        if (!taskManager.isTerminal(sessionId)) {
                            taskManager.markFailed(sessionId, "识别超时，请重试");
                        }
                        return null;
                    });
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
        io.micrometer.core.instrument.Timer.Sample recognitionTimer = metricsService.startRecognitionTimer();
        try {
            sendProgress(sessionId, "正在识别商品主体");
            byte[] processed = imageProcessor.process(imageBytes);
            String contentType = "image/jpeg";

            if (visionClient.supportsTwoStageRecognition()) {
                boolean waitingForSelection = doTwoStageRecognize(sessionId, processed, contentType, region,
                        originalFilename, imageSize, imageHash, userId, domestic);
                if (waitingForSelection) {
                    return;
                }
            } else {
                sendProgress(sessionId, "正在提取颜色/品牌/材质");
                RecognitionResult result;
                io.micrometer.core.instrument.Timer.Sample visionTimer = metricsService.startVisionModelTimer();
                try {
                    result = executeWithRetry(sessionId, "single", () -> visionClient.analyze(processed, contentType, region));
                    metricsService.stopVisionModelTimer(visionTimer, "doubao", true);
                } catch (Exception error) {
                    metricsService.stopVisionModelTimer(visionTimer, "doubao", false);
                    log.error("Async recognition failed after retries for session {}, using fallback", sessionId, error);
                    result = fallbackResult(originalFilename, sessionId);
                }
                completeRecognition(sessionId, result, originalFilename, imageSize, imageHash, userId, domestic, processed);
            }
            metricsService.stopRecognitionTimer(recognitionTimer, true);

        } catch (ImageQualityException e) {
            metricsService.stopRecognitionTimer(recognitionTimer, false);
            log.warn("Image quality rejected for session {}: {}", sessionId, e.getReason());
            taskManager.markFailed(sessionId, e.getMessage());
            messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                    sessionId, "FAILED", null, e.getMessage(), null, Instant.now()
            ));
        } catch (Exception e) {
            metricsService.stopRecognitionTimer(recognitionTimer, false);
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
        List<RecognitionCandidate> detected;
        try {
            sendProgress(sessionId, "正在识别商品主体");
            detected = executeWithRetry(sessionId, "detect",
                    () -> visionClient.detectProducts(processed, contentType, region));
        } catch (Exception e) {
            log.warn("Two-stage Flash detection failed for session {}, falling back to single-stage: {}", sessionId, e.getMessage());
            RecognitionResult result;
            try {
                result = executeWithRetry(sessionId, "single-fallback", () -> visionClient.analyze(processed, contentType, region));
            } catch (Exception ex) {
                log.error("Single-stage fallback also failed for session {}, using fallback result", sessionId, ex);
                result = fallbackResult(originalFilename, sessionId);
            }
            completeRecognition(sessionId, result, originalFilename, imageSize, imageHash, userId, domestic, processed);
            return false;
        }
        log.info("Session {} detection: raw={} candidates, minConfidence={}", sessionId,
                detected != null ? detected.size() : 0, properties.getRecognition().getMinDetectionConfidence());
        if (detected != null) {
            for (RecognitionCandidate c : detected) {
                log.info("  detected: category={}, brand={}, confidence={}", c.category(), c.brand(), c.confidence());
            }
        }
        // 提前保存原图，供候选商品裁剪显示
        log.info("Pre-saving history image for session {}, size={} bytes", sessionId, processed != null ? processed.length : 0);
        try {
            imageStorage.saveHistoryImage(sessionId, processed);
            log.info("Pre-saved history image for session {}", sessionId);
        } catch (Exception e) {
            log.warn("Failed to pre-save history image for session {}: {}", sessionId, e.getMessage(), e);
        }

        List<CandidateWork> candidates = prepareCandidates(processed, detected, sessionId);
        log.info("Session {} after filtering: {} candidates passed threshold", sessionId, candidates.size());
        if (candidates.isEmpty()) {
            log.warn("No candidates passed confidence threshold for session {}, falling back to single-stage", sessionId);
            RecognitionResult result;
            try {
                result = executeWithRetry(sessionId, "single-fallback", () -> visionClient.analyze(processed, contentType, region));
            } catch (Exception ex) {
                log.error("Single-stage fallback also failed for session {}, using fallback result", sessionId, ex);
                result = fallbackResult(originalFilename, sessionId);
            }
            completeRecognition(sessionId, result, originalFilename, imageSize, imageHash, userId, domestic, processed);
            return false;
        }

        int threshold = Math.max(2, properties.getRecognition().getMultiProductThreshold());
        if (candidates.size() >= threshold) {
            // Only show candidates whose crop was saved successfully
            List<RecognitionCandidate> visibleCandidates = new ArrayList<>();
            Map<String, String> cropFilePaths = new LinkedHashMap<>();
            for (CandidateWork cw : candidates) {
                try {
                    imageStorage.saveCandidateCrop(sessionId, cw.candidate().candidateId(), cw.cropBytes());
                    String safeSession = sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
                    String safeCandidate = cw.candidate().candidateId().replaceAll("[^A-Za-z0-9_-]", "_");
                    String relativePath = safeSession + "_crops/" + safeCandidate + ".jpg";
                    cropFilePaths.put(cw.candidate().candidateId(), relativePath);
                    visibleCandidates.add(cw.candidate());
                } catch (Exception e) {
                    log.warn("Failed to save candidate crop to disk, skipping candidate {}: {}",
                            cw.candidate().candidateId(), e.getMessage());
                }
            }
            if (visibleCandidates.isEmpty()) {
                log.warn("All candidate crops failed to save for session {}, falling back to single-stage", sessionId);
                RecognitionResult result;
                try {
                    result = executeWithRetry(sessionId, "single-fallback", () -> visionClient.analyze(processed, contentType, region));
                } catch (Exception ex) {
                    log.error("Single-stage fallback also failed for session {}, using fallback result", sessionId, ex);
                    result = fallbackResult(originalFilename, sessionId);
                }
                completeRecognition(sessionId, result, originalFilename, imageSize, imageHash, userId, domestic, processed);
                return false;
            }
            taskManager.markMultiProductPending(sessionId, visibleCandidates, cropFilePaths);
            messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                    sessionId, "MULTI_PRODUCT_PENDING", null, null, null, null, visibleCandidates
            ));
            return true;
        }

        CandidateWork selected = candidates.get(0);
        sendProgress(sessionId, "正在提取颜色/品牌/材质");
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
            sendProgress(sessionId, "正在提取颜色/品牌/材质");
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
            RecognitionResult result = executeWithRetry(sessionId, "attributes", () ->
                    visionClient.extractAttributes(crop, contentType, candidate.category(), candidate.brand(), "裁剪商品区域"));
            // 双模型交叉验证：Flash 和 Plus 品牌不一致时，降低 brand confidence
            return crossValidateBrand(result, candidate.brand());
        } catch (Exception error) {
            log.warn("Plus attribute extraction failed for session {}, using detection fallback: {}",
                    sessionId, error.getMessage());
            return fallbackFromCandidate(candidate, sessionId);
        }
    }

    private RecognitionResult crossValidateBrand(RecognitionResult result, String flashBrand) {
        Map<String, com.visioncart.api.dto.AttributeValue> attrs = new HashMap<>(result.attributes());
        com.visioncart.api.dto.AttributeValue brandAttr = attrs.get("品牌");
        if (brandAttr == null) return result;

        String plusBrand = brandAttr.value();
        if (StringUtils.isBlank(plusBrand) || "未知".equals(plusBrand)) return result;

        // 品牌字典校验：用已知品牌列表修正拼写错误
        String corrected = com.visioncart.service.search.BrandMatcher.findClosestBrand(plusBrand);
        if (corrected != null && !corrected.equalsIgnoreCase(plusBrand)) {
            log.info("Brand corrected by dictionary: [{}] -> [{}] for session {}",
                    plusBrand, corrected, result.sessionId());
            attrs.put("品牌", new com.visioncart.api.dto.AttributeValue(corrected, brandAttr.confidence(), true));
            return new com.visioncart.api.dto.RecognitionResult(
                    result.sessionId(), result.category(), attrs, result.keywords(),
                    result.overallConfidence(), result.platformStats());
        }

        // 未知品牌安全网：不在已知字典中的品牌设为"未知"，防止错误品牌污染搜索
        if (corrected == null && !com.visioncart.service.search.BrandMatcher.isKnownBrand(plusBrand)) {
            log.info("Unknown brand [{}] set to 未知 for session {}", plusBrand, result.sessionId());
            attrs.put("品牌", new com.visioncart.api.dto.AttributeValue("未知", 0.0, false));
            return new com.visioncart.api.dto.RecognitionResult(
                    result.sessionId(), result.category(), attrs, result.keywords(),
                    result.overallConfidence(), result.platformStats());
        }

        // 双模型交叉验证：Flash 和 Plus 品牌不一致时，降低 brand confidence
        if (StringUtils.isNotBlank(flashBrand) && !com.visioncart.service.search.BrandMatcher.sameBrand(flashBrand, plusBrand)) {
            log.info("Brand mismatch: Flash=[{}], Plus=[{}], reducing confidence for session {}",
                    flashBrand, plusBrand, result.sessionId());
            attrs.put("品牌", new com.visioncart.api.dto.AttributeValue(plusBrand, Math.min(brandAttr.confidence(), 0.5), false));
            return new com.visioncart.api.dto.RecognitionResult(
                    result.sessionId(), result.category(), attrs, result.keywords(),
                    result.overallConfidence(), result.platformStats());
        }

        return result;
    }

    private void completeRecognition(String sessionId,
                                     RecognitionResult result,
                                     String originalFilename,
                                     long imageSize,
                                     String imageHash,
                                     Long userId,
                                     boolean domestic,
                                     byte[] historyImageBytes) {
        if (taskManager.isTerminal(sessionId)) {
            log.info("Skipping completion for session {} because task is already terminal", sessionId);
            return;
        }
        RecognitionResult withSession = withSessionId(result, sessionId);
        saveHistory(withSession, originalFilename, imageSize, imageHash, userId, historyImageBytes);
        // 标记完成（不含平台价格），让客户端立即看到识别结果
        taskManager.markCompleted(sessionId, withSession);
        // 置信度驱动提示
        String confidenceHint = buildConfidenceHint(withSession.overallConfidence());
        // WebSocket 通知：失败不影响识别状态
        try {
            messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                    sessionId, "COMPLETED", withSession, null, null, Instant.now(), null, null, confidenceHint
            ));
        } catch (Exception wsError) {
            log.warn("WebSocket notification failed for session {} (recognition still COMPLETED): {}", sessionId, wsError.getMessage());
        }
        // 异步补充平台价格统计，完成后仅推送 WebSocket 更新（不再重复 markCompleted 避免竞态）
        CompletableFuture.runAsync(() -> {
            try {
                if (taskManager.isFailed(sessionId)) {
                    log.info("Skipping platform stats enrichment for failed session {}", sessionId);
                    return;
                }
                sendProgress(sessionId, "正在搜索全网商品");
                RecognitionResult enriched = enrichWithPlatformStats(withSession, domestic);
                if (taskManager.isFailed(sessionId)) {
                    log.info("Skipping enriched result update for failed session {}", sessionId);
                    return;
                }
                sendProgress(sessionId, "正在生成导购建议");
                if (enriched.platformStats() != null && !enriched.platformStats().isEmpty()) {
                    taskManager.updateResult(sessionId, enriched);
                    try {
                        String enrichedHint = buildConfidenceHint(enriched.overallConfidence());
                        messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                                sessionId, "COMPLETED", enriched, null, null, Instant.now(), null, null, enrichedHint
                        ));
                    } catch (Exception wsError) {
                        log.warn("WebSocket enrichment notification failed for session {}: {}", sessionId, wsError.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Async platform stats enrichment failed for session {}: {}", sessionId, e.getMessage());
            }
        }, searchExecutor).orTimeout(15, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("Async platform stats timed out for session {}", sessionId);
                    return null;
                });
    }

    private List<CandidateWork> prepareCandidates(byte[] imageBytes, List<RecognitionCandidate> detected, String sessionId) {
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
                // 不传 base64，传原图 URL，前端用 bbox 客户端裁剪
                String imageUrl = imageStorage.candidateCropUrl(sessionId, candidateId);
                log.info("Candidate {} bbox={} crop={}x{} sourceArea={} previewImageUrl={}",
                        candidateId, candidate.bbox(), crop.width(), crop.height(), crop.sourceArea(), imageUrl);
                RecognitionCandidate visible = new RecognitionCandidate(
                        candidateId,
                        candidate.bbox(),
                        candidate.category(),
                        candidate.brand(),
                        candidate.confidence(),
                        imageUrl
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
                    result.sessionId(), RecognitionSearchMapper.toSearchAttributes(result), null, 1, 50, "recognition"
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
            String imageUrl = null;
            try {
                if (historyImageBytes != null && historyImageBytes.length > 0) {
                    imageStorage.saveHistoryImage(result.sessionId(), historyImageBytes);
                    imageUrl = imageStorage.historyImageUrl(result.sessionId());
                } else if (imageStorage.loadHistoryImage(result.sessionId()).isPresent()) {
                    imageUrl = imageStorage.historyImageUrl(result.sessionId());
                }
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
        } catch (Exception e) {
            // MySQL save failure should NOT prevent recognition from being marked COMPLETED
            // The result is already in Redis via taskManager.markCompleted
            log.error("Failed to save recognition history for session {} (result still in Redis): {}", result.sessionId(), e.getMessage());
        }
    }

    private record CandidateWork(RecognitionCandidate candidate, byte[] cropBytes, long sourceArea) {
    }

    private void sendProgress(String sessionId, String step) {
        taskManager.markProgress(sessionId, step);
        try {
            messagingTemplate.convertAndSend(WS_TOPIC + sessionId, new RecognitionTaskResult(
                    sessionId, "PROCESSING", null, null, null, null, null, step
            ));
        } catch (Exception e) {
            log.debug("WebSocket progress notification failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 置信度驱动提示：
     * - >= 0.8：高置信度，直接搜索
     * - 0.5 ~ 0.8：中置信度，搜索但提示"可能是 xxx"
     * - < 0.5：低置信度，提示用户框选或重新拍照
     */
    private String buildConfidenceHint(double confidence) {
        if (confidence >= 0.8) {
            return null; // 高置信度不需要提示
        } else if (confidence >= 0.5) {
            return "识别置信度一般，搜索结果可能不完全匹配";
        } else {
            return "识别置信度较低，建议框选商品主体或重新拍照";
        }
    }

    @FunctionalInterface
    private interface RetryableOperation<T> {
        T run() throws Exception;
    }
}
