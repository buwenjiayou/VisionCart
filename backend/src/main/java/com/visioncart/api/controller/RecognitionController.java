package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.AttributeCorrectionRequest;
import com.visioncart.api.dto.AttributeCorrectionResult;
import com.visioncart.api.dto.AsyncRecognitionResponse;
import com.visioncart.api.dto.CorrectionStat;
import com.visioncart.config.JwtAuthenticationFilter.AuthPrincipal;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.repository.RecognitionFeedbackRepository;
import com.visioncart.service.recognition.RecognitionOrchestrator;
import com.visioncart.service.recognition.RecognitionService;
import com.visioncart.service.recognition.RecognitionTaskResult;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
        if (!looksLikeImageUpload(contentType)) {
            return ApiResponse.fail(400, "请选择图片文件上传");
        }
        Long userId = getCurrentUserId();
        return ApiResponse.ok(orchestrator.submitAsync(image, region, userId));
    }

    private boolean looksLikeImageUpload(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String normalized = contentType.toLowerCase();
        return normalized.startsWith("image/") || normalized.equals("application/octet-stream");
    }

    @GetMapping("/status/{sessionId}")
    public ApiResponse<RecognitionTaskResult> status(@PathVariable String sessionId) {
        RecognitionTaskResult result = orchestrator.getStatus(sessionId);
        if (result == null) {
            return ApiResponse.fail(404, "任务不存在或已过期");
        }
        return ApiResponse.ok(result);
    }

    @PutMapping("/attributes")
    public ApiResponse<AttributeCorrectionResult> correct(@Valid @RequestBody AttributeCorrectionRequest request) {
        return ApiResponse.ok(recognitionService.correct(request));
    }

    @GetMapping("/attribute-options")
    public ApiResponse<Map<String, List<String>>> attributeOptions(@RequestParam String category,
                                                                   @RequestParam String attribute,
                                                                   @RequestParam(value = "session_id", required = false) String sessionId) {
        List<String> options = properties.getRecognition().getAttributeOptions()
                .getOrDefault(attribute, List.of());
        return ApiResponse.ok(Map.of("options", recognitionService.attributeOptions(category, attribute, sessionId, options)));
    }

    @GetMapping("/feedback/stats")
    public ApiResponse<List<CorrectionStat>> feedbackStats() {
        return ApiResponse.ok(feedbackRepository.findTopCorrections());
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.getUserId();
        }
        throw new SecurityException("未登录");
    }
}
