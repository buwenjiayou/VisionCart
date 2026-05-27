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
import com.visioncart.service.recognition.RecognitionOrchestrator;
import com.visioncart.service.recognition.RecognitionService;
import com.visioncart.service.recognition.RecognitionTaskResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recognition")
public class RecognitionController {
    private final RecognitionService recognitionService;
    private final RecognitionOrchestrator orchestrator;
    private final RecognitionFeedbackRepository feedbackRepository;
    private final VisionCartProperties properties;

    public RecognitionController(RecognitionService recognitionService,
                                 RecognitionOrchestrator orchestrator,
                                 RecognitionFeedbackRepository feedbackRepository,
                                 VisionCartProperties properties) {
        this.recognitionService = recognitionService;
        this.orchestrator = orchestrator;
        this.feedbackRepository = feedbackRepository;
        this.properties = properties;
    }

    @PostMapping("/analyze")
    public ApiResponse<AsyncRecognitionResponse> analyze(@RequestParam("image") MultipartFile image,
                                                         @RequestParam(value = "region", required = false) String region) {
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
        return ApiResponse.ok(orchestrator.submitAsync(image, region, userId));
    }

    private boolean looksLikeImageUpload(MultipartFile image, String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase();
        boolean contentTypeAllowed = normalized.isBlank()
                || normalized.startsWith("image/")
                || normalized.equals("application/octet-stream");
        if (!contentTypeAllowed) {
            return false;
        }
        try {
            byte[] header = image.getInputStream().readNBytes(12);
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

    @PutMapping("/attributes")
    public ApiResponse<AttributeCorrectionResult> correct(@Valid @RequestBody AttributeCorrectionRequest request) {
        return ApiResponse.ok(recognitionService.correct(request, SecurityUtils.currentUserId()));
    }

    @GetMapping("/attribute-options")
    public ApiResponse<Map<String, List<String>>> attributeOptions(@RequestParam String category,
                                                                   @RequestParam String attribute,
                                                                   @RequestParam(value = "session_id", required = false) String sessionId) {
        List<String> options = properties.getRecognition().getAttributeOptions()
                .getOrDefault(attribute, List.of());
        return ApiResponse.ok(Map.of("options", recognitionService.attributeOptions(
                category, attribute, sessionId, SecurityUtils.currentUserId(), options)));
    }

    @GetMapping("/feedback/stats")
    public ApiResponse<List<CorrectionStat>> feedbackStats() {
        SecurityUtils.requireAdmin(properties, SecurityUtils.currentUserId());
        return ApiResponse.ok(feedbackRepository.findTopCorrections());
    }
}
