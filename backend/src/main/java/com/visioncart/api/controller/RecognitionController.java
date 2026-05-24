package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.AttributeCorrectionRequest;
import com.visioncart.api.dto.AttributeCorrectionResult;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.service.recognition.RecognitionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
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

    public RecognitionController(RecognitionService recognitionService) {
        this.recognitionService = recognitionService;
    }

    @PostMapping("/analyze")
    public ApiResponse<RecognitionResult> analyze(@RequestParam("image") MultipartFile image,
                                                  @RequestParam(value = "region", required = false) String region) {
        return ApiResponse.ok(recognitionService.analyze(image, region));
    }

    @PutMapping("/attributes")
    public ApiResponse<AttributeCorrectionResult> correct(@Valid @RequestBody AttributeCorrectionRequest request) {
        return ApiResponse.ok(recognitionService.correct(request));
    }

    @GetMapping("/attribute-options")
    public ApiResponse<Map<String, List<String>>> attributeOptions(@RequestParam String category,
                                                                   @RequestParam String attribute) {
        List<String> options = switch (attribute) {
            case "颜色" -> List.of("黑色", "白色", "红色", "蓝色", "深蓝色", "藏青", "灰色", "绿色", "黄色", "粉色", "棕色", "米白色");
            case "品牌" -> List.of("耐克", "阿迪达斯", "安踏", "李宁", "彪马", "新百伦", "亚瑟士");
            case "款式" -> List.of("跑鞋", "篮球鞋", "板鞋", "训练鞋", "休闲鞋");
            default -> List.of();
        };
        return ApiResponse.ok(Map.of("options", options));
    }
}
