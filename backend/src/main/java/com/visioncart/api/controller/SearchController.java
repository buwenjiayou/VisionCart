package com.visioncart.api.controller;

import com.visioncart.api.dto.*;
import com.visioncart.config.SecurityUtils;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.recognition.AsyncRecognitionTaskManager;
import com.visioncart.service.recognition.SessionHistoryService;
import com.visioncart.service.search.SearchOrchestrator;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {
    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private final SearchOrchestrator searchOrchestrator;
    private final AsyncRecognitionTaskManager taskManager;
    private final RecognitionHistoryRepository historyRepository;
    private final SessionHistoryService sessionHistoryService;

    public SearchController(SearchOrchestrator searchOrchestrator, AsyncRecognitionTaskManager taskManager,
                            RecognitionHistoryRepository historyRepository,
                            SessionHistoryService sessionHistoryService) {
        this.searchOrchestrator = searchOrchestrator;
        this.taskManager = taskManager;
        this.historyRepository = historyRepository;
        this.sessionHistoryService = sessionHistoryService;
    }

    @PostMapping("/products")
    public ApiResponse<SearchResult> products(@Valid @RequestBody SearchRequest request) {
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            Long userId = SecurityUtils.currentUserId();
            if (!taskManager.belongsToUser(request.sessionId(), userId)
                    && historyRepository.findBySessionIdAndUserId(request.sessionId(), userId).isEmpty()) {
                return ApiResponse.fail(403, "无权访问该识别任务");
            }
        }
        SearchResult result = searchOrchestrator.search(request, SecurityUtils.currentUserId());
        // 搜索完成，立即归档展示给用户的商品列表到 MySQL
        if (request.sessionId() != null && !request.sessionId().isBlank() && result.products() != null) {
            sessionHistoryService.archiveDisplayedProducts(request.sessionId(), result.products());
        }
        return ApiResponse.ok(result);
    }
}
