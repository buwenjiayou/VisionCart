package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.SearchRequest;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.api.dto.SuggestionExecuteRequest;
import com.visioncart.api.dto.SuggestionExecuteResult;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.search.SearchOrchestrator;
import com.visioncart.service.suggestion.SuggestionService;
import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/suggestions")
public class SuggestionController {
    private final SuggestionService suggestionService;
    private final RecognitionHistoryRepository recognitionHistoryRepository;
    private final SearchOrchestrator searchOrchestrator;
    private final ObjectMapper objectMapper;

    public SuggestionController(SuggestionService suggestionService,
                                RecognitionHistoryRepository recognitionHistoryRepository,
                                SearchOrchestrator searchOrchestrator,
                                ObjectMapper objectMapper) {
        this.suggestionService = suggestionService;
        this.recognitionHistoryRepository = recognitionHistoryRepository;
        this.searchOrchestrator = searchOrchestrator;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/cards")
    public ApiResponse<Map<String, List<SuggestionCard>>> cards(@RequestParam(required = false) String sessionId,
                                                                @RequestParam(name = "session_id", required = false) String snakeSessionId,
                                                                @RequestParam(defaultValue = "app") String clientType,
                                                                @RequestParam(name = "client_type", required = false) String snakeClientType) {
        String resolvedSessionId = sessionId != null ? sessionId : snakeSessionId;
        String resolvedClientType = snakeClientType != null ? snakeClientType : clientType;
        List<SuggestionCard> cards = recognitionHistoryRepository.findById(resolvedSessionId)
                .map(history -> searchOrchestrator.search(new SearchRequest(
                        resolvedSessionId,
                        attributes(history),
                        null,
                        1,
                        20,
                        resolvedClientType
                )))
                .map(SearchResult::suggestionCards)
                .orElseGet(() -> suggestionService.cards(resolvedSessionId, resolvedClientType, List.of()));
        return ApiResponse.ok(Map.of("cards", cards));
    }

    @PostMapping("/execute")
    public ApiResponse<SuggestionExecuteResult> execute(@Valid @RequestBody SuggestionExecuteRequest request) {
        return ApiResponse.ok(suggestionService.execute(request.action(), request.currentProducts()));
    }

    private Map<String, String> attributes(RecognitionHistory history) {
        try {
            Map<String, AttributeValue> values = objectMapper.readValue(
                    history.getAttributesJson(),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, AttributeValue.class)
            );
            Map<String, String> attributes = new LinkedHashMap<>();
            values.forEach((key, value) -> attributes.put(key, value.value()));
            return attributes;
        } catch (Exception error) {
            return Map.of();
        }
    }
}
