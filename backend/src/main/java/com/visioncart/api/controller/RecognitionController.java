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
import java.util.Set;

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

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024; // 10MB

    @PostMapping("/analyze")
    public ApiResponse<AsyncRecognitionResponse> analyze(@RequestParam("image") MultipartFile image,
                                                         @RequestParam(value = "region", required = false) String region) {
        if (image.isEmpty()) {
            return ApiResponse.fail(400, "图片文件不能为空");
        }
        if (image.getSize() > MAX_IMAGE_SIZE) {
            return ApiResponse.fail(400, "图片文件不能超过 10MB");
        }
        String contentType = image.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            return ApiResponse.fail(400, "仅支持 JPEG、PNG、WebP 格式的图片");
        }
        Long userId = getCurrentUserId();
        return ApiResponse.ok(orchestrator.submitAsync(image, region, userId));
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
                                                                   @RequestParam String attribute) {
        List<String> options = properties.getRecognition().getAttributeOptions()
                .getOrDefault(attribute, List.of());
        return ApiResponse.ok(Map.of("options", options));
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
