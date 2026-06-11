package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.AttributeCorrectionRequest;
import com.visioncart.api.dto.AttributeCorrectionResult;
import com.visioncart.api.dto.AsyncRecognitionResponse;
import com.visioncart.api.dto.CorrectionStat;
import com.visioncart.api.dto.ProductSelectionRequest;
import com.visioncart.config.SecurityUtils;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.repository.RecognitionFeedbackRepository;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.recognition.AsyncRecognitionTaskManager;
import com.visioncart.service.recognition.RecognitionImageStorage;
import com.visioncart.service.recognition.RecognitionOrchestrator;
import com.visioncart.service.recognition.RecognitionService;
import com.visioncart.service.recognition.RecognitionTaskResult;
import com.visioncart.service.recognition.SessionHistoryService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recognition")
public class RecognitionController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RecognitionController.class);
    private final RecognitionService recognitionService;
    private final RecognitionOrchestrator orchestrator;
    private final AsyncRecognitionTaskManager taskManager;
    private final RecognitionFeedbackRepository feedbackRepository;
    private final RecognitionHistoryRepository historyRepository;
    private final SessionHistoryService sessionHistoryService;
    private final VisionCartProperties properties;
    private final RecognitionImageStorage imageStorage;

    public RecognitionController(RecognitionService recognitionService,
                                 RecognitionOrchestrator orchestrator,
                                 AsyncRecognitionTaskManager taskManager,
                                 RecognitionFeedbackRepository feedbackRepository,
                                 RecognitionHistoryRepository historyRepository,
                                 SessionHistoryService sessionHistoryService,
                                 VisionCartProperties properties,
                                 RecognitionImageStorage imageStorage) {
        this.recognitionService = recognitionService;
        this.orchestrator = orchestrator;
        this.taskManager = taskManager;
        this.feedbackRepository = feedbackRepository;
        this.historyRepository = historyRepository;
        this.sessionHistoryService = sessionHistoryService;
        this.properties = properties;
        this.imageStorage = imageStorage;
    }

    /**
     * 归档会话商品到 MySQL。安卓在退出/切换会话时调用。
     */
    @PostMapping("/{sessionId}/archive")
    public ApiResponse<Void> archiveSession(@PathVariable String sessionId) {
        Long userId = SecurityUtils.currentUserId();
        if (!taskManager.belongsToUser(sessionId, userId)
                && historyRepository.findBySessionIdAndUserId(sessionId, userId).isEmpty()) {
            return ApiResponse.fail(403, "无权访问该识别任务");
        }
        try {
            sessionHistoryService.archiveSessionProducts(sessionId);
            return ApiResponse.ok(null);
        } catch (Exception e) {
            log.warn("Archive session failed {}: {}", sessionId, e.getMessage());
            return ApiResponse.fail(500, "归档失败");
        }
    }

    @PostMapping("/analyze")
    public ApiResponse<AsyncRecognitionResponse> analyze(@RequestParam("image") MultipartFile image,
                                                         @RequestParam(value = "region", required = false) String region,
                                                         @RequestParam(value = "region_mode", required = false) String regionMode,
                                                         @RequestParam(value = "previous_session_id", required = false) String previousSessionId) {
        if (image.isEmpty()) {
            return ApiResponse.fail(400, "图片文件不能为空");
        }
        long maxUploadBytes = properties.getRecognition().getMaxUploadBytes();
        if (image.getSize() > maxUploadBytes) {
            long maxMb = Math.max(1, maxUploadBytes / 1024 / 1024);
            return ApiResponse.fail(400, "图片文件不能超过 " + maxMb + "MB，请重新拍照或选择较小图片");
        }
        String contentType = image.getContentType();
        if (!looksLikeImageUpload(image, contentType)) {
            return ApiResponse.fail(400, "请选择图片文件上传");
        }
        Long userId = SecurityUtils.currentUserId();
        // 归档上一个会话的商品列表到 MySQL（需校验 ownership）
        if (previousSessionId != null && !previousSessionId.isBlank()) {
            if (taskManager.belongsToUser(previousSessionId, userId)
                    || historyRepository.findBySessionIdAndUserId(previousSessionId, userId).isPresent()) {
                try {
                    sessionHistoryService.archiveSessionProducts(previousSessionId);
                } catch (Exception e) {
                    log.warn("Failed to archive previous session {}: {}", previousSessionId, e.getMessage());
                }
            } else {
                log.warn("Skipping archive: previousSessionId {} does not belong to user {}", previousSessionId, userId);
            }
        }
        return ApiResponse.ok(orchestrator.submitAsync(image, region, userId, regionMode));
    }

    private boolean looksLikeImageUpload(MultipartFile image, String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase();
        boolean contentTypeAllowed = normalized.isBlank()
                || normalized.startsWith("image/")
                || normalized.equals("application/octet-stream");
        if (!contentTypeAllowed) {
            return false;
        }
        try (var is = image.getInputStream()) {
            byte[] header = is.readNBytes(12);
            return isJpeg(header) || isPng(header) || isWebp(header);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isJpeg(byte[] header) {
        return header.length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] header) {
        return header.length >= 4
                && (header[0] & 0xFF) == 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47;
    }

    private boolean isWebp(byte[] header) {
        return header.length >= 12
                && header[0] == 0x52
                && header[1] == 0x49
                && header[2] == 0x46
                && header[3] == 0x46
                && header[8] == 0x57
                && header[9] == 0x45
                && header[10] == 0x42
                && header[11] == 0x50;
    }

    @GetMapping("/status/{sessionId}")
    public ApiResponse<RecognitionTaskResult> status(@PathVariable String sessionId) {
        RecognitionTaskResult result = orchestrator.getStatus(sessionId, SecurityUtils.currentUserId());
        if (result == null) {
            return ApiResponse.fail(404, "任务不存在或已过期");
        }
        return ApiResponse.ok(result);
    }

    @PostMapping("/{sessionId}/select-product")
    public ApiResponse<AsyncRecognitionResponse> selectProduct(@PathVariable String sessionId,
                                                               @Valid @RequestBody ProductSelectionRequest request) {
        return ApiResponse.ok(orchestrator.selectProduct(sessionId, request, SecurityUtils.currentUserId()));
    }

    @GetMapping("/{sessionId}/candidates/{candidateId}/image")
    public ResponseEntity<Resource> candidateImage(@PathVariable String sessionId,
                                                   @PathVariable String candidateId) {
        Long userId = SecurityUtils.currentUserId();
        boolean ownsActiveTask = taskManager.belongsToUser(sessionId, userId);
        boolean ownsCompletedHistory = !ownsActiveTask
                && historyRepository.findBySessionIdAndUserId(sessionId, userId).isPresent();
        if (!ownsActiveTask && !ownsCompletedHistory) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recognition candidate not found");
        }

        Resource resource = imageStorage.loadCandidateCropResource(sessionId, candidateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "recognition candidate image not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(resource);
    }

    @PutMapping("/attributes")
    public ApiResponse<AttributeCorrectionResult> correct(@Valid @RequestBody AttributeCorrectionRequest request) {
        return ApiResponse.ok(recognitionService.correct(request, SecurityUtils.currentUserId()));
    }

    @GetMapping("/attribute-options")
    public ApiResponse<Map<String, List<String>>> attributeOptions(@RequestParam String category,
                                                                   @RequestParam String attribute,
                                                                   @RequestParam(value = "session_id", required = false) String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            Long userId = SecurityUtils.currentUserId();
            if (!taskManager.belongsToUser(sessionId, userId)
                    && historyRepository.findBySessionIdAndUserId(sessionId, userId).isEmpty()) {
                return ApiResponse.fail(403, "无权访问该识别任务");
            }
        }
        return ApiResponse.ok(Map.of("options", recognitionService.attributeOptions(
                category, attribute, sessionId, SecurityUtils.currentUserId())));
    }

    @GetMapping("/feedback/stats")
    public ApiResponse<List<CorrectionStat>> feedbackStats() {
        SecurityUtils.requireAdmin(properties, SecurityUtils.currentUserId());
        return ApiResponse.ok(feedbackRepository.findTopCorrections());
    }
}
