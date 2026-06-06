package com.visioncart.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.*;
import com.visioncart.config.SecurityUtils;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.action.ActionExecutionService;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.filter.NlpUndoService;
import com.visioncart.service.search.CandidateFilterService;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.recognition.AsyncRecognitionTaskManager;
import com.visioncart.service.recognition.SessionHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unified undo endpoint for ALL user actions.
 * Replaces separate /suggestions/undo and /nlp/filter/{sessionId}/undo.
 */
@RestController
@RequestMapping("/api/v1/actions")
public class ActionUndoController {

    private static final Logger log = LoggerFactory.getLogger(ActionUndoController.class);
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final NlpUndoService undoService;
    private final NlpConversationManager conversationManager;
    private final CandidateSessionCache sessionCache;
    private final CandidateFilterService filterService;
    private final AsyncRecognitionTaskManager taskManager;
    private final RecognitionHistoryRepository historyRepository;
    private final SessionHistoryService sessionHistoryService;
    private final ActionExecutionService actionExecutionService;
    private final ObjectMapper objectMapper;

    public ActionUndoController(NlpUndoService undoService,
                                NlpConversationManager conversationManager,
                                CandidateSessionCache sessionCache,
                                CandidateFilterService filterService,
                                 AsyncRecognitionTaskManager taskManager,
                                 RecognitionHistoryRepository historyRepository,
                                 SessionHistoryService sessionHistoryService,
                                 ActionExecutionService actionExecutionService,
                                 ObjectMapper objectMapper) {
        this.undoService = undoService;
        this.conversationManager = conversationManager;
        this.sessionCache = sessionCache;
        this.filterService = filterService;
        this.taskManager = taskManager;
        this.historyRepository = historyRepository;
        this.sessionHistoryService = sessionHistoryService;
        this.actionExecutionService = actionExecutionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute any user action (NLP, suggestion, correction, tag delete, clear, sort, manual filter)
     * through the unified action pipeline.
     */
    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<ActionResult>> execute(
            @RequestBody UserAction action) {
        try {
            Long userId = SecurityUtils.currentUserId();
            if (action == null) {
                return ResponseEntity.ok(ApiResponse.fail(400, "操作参数不能为空"));
            }
            if (action.sessionId() != null && !action.sessionId().isBlank() && !ownsSession(action.sessionId(), userId)) {
                return ResponseEntity.ok(ApiResponse.fail(403, "无权访问该识别任务"));
            }
            return ResponseEntity.ok(ApiResponse.ok(actionExecutionService.execute(action, userId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.fail(400, e.getMessage()));
        } catch (Exception e) {
            log.error("Unified action execution failed: source={}, session={}",
                    action != null ? action.source() : null,
                    action != null ? action.sessionId() : null,
                    e);
            return ResponseEntity.ok(ApiResponse.fail(500, "操作执行失败，请稍后重试"));
        }
    }

    /**
     * Undo the last user action (NLP, Suggestion, AttributeCorrection, TagDelete).
     * Restores both filter state and re-filters the current candidate pool.
     * Returns unified ActionResult for all clients.
     */
    @PostMapping("/undo")
    public ResponseEntity<ApiResponse<ActionResult>> undo(
            @RequestParam(name = "session_id") String sessionId) {

        Long userId = SecurityUtils.currentUserId();
        if (!ownsSession(sessionId, userId)) {
            return ResponseEntity.ok(ApiResponse.fail(403, "无权访问该识别任务"));
        }

        if (!undoService.canUndo(sessionId)) {
            return ResponseEntity.ok(ApiResponse.fail(404, "没有可撤销的操作"));
        }

        NlpUndoService.UndoResult undoResult = undoService.undo(sessionId);
        if (undoResult == null) {
            return ResponseEntity.ok(ApiResponse.fail(404, "撤销失败"));
        }

        // Restore filter state
        SearchFilter restoredFilter = undoResult.filter();
        if (restoredFilter == null) {
            restoredFilter = SearchFilter.empty();
        }
        conversationManager.setFilterState(sessionId, restoredFilter);

        // Re-filter current candidates with restored filter
        List<ProductCard> candidates = sessionCache.getCandidates(sessionId);
        CandidateFilterService.FilterResult filterResult = filterService.filter(
                candidates, restoredFilter, Map.of(), DEFAULT_PAGE_SIZE, 1);

        boolean canUndo = undoService.canUndo(sessionId);
        String message = "已撤回" + (undoResult.undoneSource() != null ? undoResult.undoneSource() : "")
                + "「" + (undoResult.undoneQuery() != null ? undoResult.undoneQuery() : "") + "」";

        // Update MySQL product snapshot
        if (filterResult.products() != null && !filterResult.products().isEmpty()) {
            sessionHistoryService.archiveDisplayedProducts(sessionId, filterResult.products());
        }

        log.info("Unified undo for session {}: restored '{}', {} products, canUndo={}",
                sessionId, undoResult.undoneQuery(), filterResult.resultCount(), canUndo);

        // Generate structured filter tags from restored filter
        List<FilterTag> restoredTags = actionExecutionService.generateStructuredTags(restoredFilter);

        // Restore attributes if this was a correction undo
        Map<String, AttributeValue> restoredAttributeValues = toAttributeValues(undoResult.previousAttributes());
        Map<String, Object> restoredAttributes = restoredAttributeValues.isEmpty()
                ? null
                : new java.util.LinkedHashMap<>(restoredAttributeValues);
        if (restoredAttributes != null && !restoredAttributes.isEmpty()) {
            try {
                historyRepository.findBySessionIdAndUserId(sessionId, userId).ifPresent(history -> {
                    try {
                        String attrsJson = objectMapper.writeValueAsString(restoredAttributeValues);
                        history.setAttributesJson(attrsJson);
                        history.setUpdatedAt(java.time.Instant.now());
                        historyRepository.save(history);
                        log.info("Restored attributes for session {} on undo: {} keys", sessionId, restoredAttributes.size());
                    } catch (Exception e) {
                        log.warn("Failed to restore attributes on undo: {}", e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to lookup history for attribute restore: {}", e.getMessage());
            }
            restoreTaskAttributes(sessionId, restoredAttributeValues);
        }

        // Build unified ActionResult
        ActionResult actionResult = new ActionResult(
                filterResult.products(),
                restoredFilter,
                restoredTags,
                true,       // filterApplied (undo always applies)
                false,      // keptPrevious
                canUndo,
                message,
                List.of(),  // warnings
                List.of(),  // explanations
                null,       // uiAction
                candidates.size(),
                null,       // suggestionCards
                restoredAttributes != null ? restoredAttributes : null,  // updatedAttributes
                "undo:" + (undoResult.undoneSource() != null ? undoResult.undoneSource() : "unknown"),
                sessionId + ":undo:" + System.currentTimeMillis(),
                null,       // fallbackProducts
                "NORMAL",   // displayMode
                "filter.undone",
                restoredAttributes != null,  // attributesUpdated
                null        // productsUpdated
        );

        return ResponseEntity.ok(ApiResponse.ok(actionResult));
    }

    /**
     * Check if undo is available.
     */
    @GetMapping("/can-undo")
    public ResponseEntity<ApiResponse<Map<String, Object>>> canUndo(
            @RequestParam(name = "session_id") String sessionId) {
        Long userId = SecurityUtils.currentUserId();
        if (!ownsSession(sessionId, userId)) {
            return ResponseEntity.ok(ApiResponse.fail(403, "无权访问该识别任务"));
        }
        boolean available = undoService.canUndo(sessionId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("canUndo", available)));
    }

    private Map<String, AttributeValue> toAttributeValues(Map<String, Object> rawAttributes) {
        if (rawAttributes == null || rawAttributes.isEmpty()) {
            return Map.of();
        }
        Map<String, AttributeValue> converted = new java.util.LinkedHashMap<>();
        rawAttributes.forEach((key, value) -> {
            if (value instanceof AttributeValue attr) {
                converted.put(key, attr);
            } else if (value instanceof Map<?, ?>) {
                converted.put(key, objectMapper.convertValue(value, AttributeValue.class));
            } else if (value != null) {
                converted.put(key, new AttributeValue(String.valueOf(value), 1.0, true));
            }
        });
        return converted;
    }

    private void restoreTaskAttributes(String sessionId, Map<String, AttributeValue> restoredAttributes) {
        try {
            var task = taskManager.getStatus(sessionId);
            if (task == null || task.result() == null) {
                return;
            }
            RecognitionResult current = task.result();
            taskManager.updateResult(sessionId, new RecognitionResult(
                    current.sessionId(),
                    current.category(),
                    restoredAttributes,
                    current.keywords(),
                    current.overallConfidence(),
                    current.platformStats()));
        } catch (Exception e) {
            log.warn("Failed to restore active task attributes on undo: {}", e.getMessage());
        }
    }

    private boolean ownsSession(String sessionId, Long userId) {
        return taskManager.belongsToUser(sessionId, userId)
                || historyRepository.findBySessionIdAndUserId(sessionId, userId).isPresent();
    }

    public record UndoResponse(
            List<ProductCard> products,
            SearchFilter filter,
            int totalInPool,
            boolean canUndo,
            String undoneQuery,
            String undoneSource
    ) {}
}
