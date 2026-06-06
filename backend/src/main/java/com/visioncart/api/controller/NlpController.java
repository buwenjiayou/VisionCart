package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.NlpParseRequest;
import com.visioncart.api.dto.NlpParseResult;
import com.visioncart.config.SecurityUtils;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.nlp.NlpModelService;
import com.visioncart.service.recognition.AsyncRecognitionTaskManager;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/nlp")
public class NlpController {
    private final NlpModelService nlpService;
    private final AsyncRecognitionTaskManager taskManager;
    private final RecognitionHistoryRepository historyRepository;

    public NlpController(NlpModelService nlpService, AsyncRecognitionTaskManager taskManager, RecognitionHistoryRepository historyRepository) {
        this.nlpService = nlpService;
        this.taskManager = taskManager;
        this.historyRepository = historyRepository;
    }

    @PostMapping("/parse")
    public ApiResponse<NlpParseResult> parse(@Valid @RequestBody NlpParseRequest request) {
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            Long userId = SecurityUtils.currentUserId();
            if (!taskManager.belongsToUser(request.sessionId(), userId)
                    && historyRepository.findBySessionIdAndUserId(request.sessionId(), userId).isEmpty()) {
                return ApiResponse.fail(403, "无权访问该识别任务");
            }
        }
        return ApiResponse.ok(nlpService.parse(request));
    }
}
