package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.api.dto.SuggestionExecuteRequest;
import com.visioncart.api.dto.SuggestionExecuteResult;
import com.visioncart.service.suggestion.SuggestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/suggestions")
public class SuggestionController {
    private final SuggestionService suggestionService;

    public SuggestionController(SuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @GetMapping("/cards")
    public ApiResponse<Map<String, List<SuggestionCard>>> cards(@RequestParam(defaultValue = "app") String clientType,
                                                                @RequestParam(name = "client_type", required = false) String snakeClientType) {
        String resolvedClientType = snakeClientType != null ? snakeClientType : clientType;
        List<SuggestionCard> cards = suggestionService.cards(resolvedClientType, List.of());
        return ApiResponse.ok(Map.of("cards", cards));
    }

    @PostMapping("/execute")
    public ApiResponse<SuggestionExecuteResult> execute(@Valid @RequestBody SuggestionExecuteRequest request) {
        return ApiResponse.ok(suggestionService.execute(
                request.sessionId(), request.action(), request.currentProducts(), request.currentFilter()));
    }

    @PostMapping("/undo")
    public ApiResponse<SuggestionExecuteResult> undo(@RequestParam String sessionId,
                                                     @RequestBody(required = false) List<com.visioncart.api.dto.ProductCard> currentProducts) {
        return ApiResponse.ok(suggestionService.undo(sessionId, currentProducts != null ? currentProducts : List.of()));
    }
}
