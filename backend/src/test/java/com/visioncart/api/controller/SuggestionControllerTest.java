package com.visioncart.api.controller;

import com.visioncart.api.dto.*;
import com.visioncart.config.JwtAuthenticationFilter;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.filter.ActionCompiler;
import com.visioncart.service.filter.SafeActionExecutor;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.recognition.AsyncRecognitionTaskManager;
import com.visioncart.service.context.SessionContextService;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.search.CandidateFilterService;
import com.visioncart.service.metrics.PerformanceMetricsService;
import com.visioncart.service.suggestion.DeepSuggestionService;
import com.visioncart.service.suggestion.SuggestionCardCache;
import com.visioncart.service.suggestion.SuggestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuggestionControllerTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SuggestionController createController(SuggestionService suggestionService,
                                                    DeepSuggestionService deepSuggestionService,
                                                    RecognitionHistoryRepository historyRepository,
                                                    CandidateSessionCache sessionCache,
                                                    AsyncRecognitionTaskManager taskManager,
                                                    PerformanceMetricsService metricsService) {
        SafeActionExecutor safeActionExecutor = mock(SafeActionExecutor.class);
        ActionCompiler actionCompiler = mock(ActionCompiler.class);
        NlpConversationManager conversationManager = mock(NlpConversationManager.class);
        SessionContextService sessionContextService = mock(SessionContextService.class);
        SuggestionCardCache suggestionCardCache = mock(SuggestionCardCache.class);
        CandidateFilterService candidateFilterService = mock(CandidateFilterService.class);
        // Default: resolve returns empty context (no category)
        when(sessionContextService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(SessionContextService.SessionContext.empty());
        when(suggestionCardCache.get(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        java.util.concurrent.ExecutorService searchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        return new SuggestionController(suggestionService, deepSuggestionService, historyRepository,
                sessionCache, taskManager, metricsService, safeActionExecutor, actionCompiler,
                conversationManager, sessionContextService, suggestionCardCache, candidateFilterService, searchExecutor);
    }

    @Test
    void executeAllowedWhenActiveTaskExistsButHistoryNotPersisted() {
        // Scenario: recognition just completed, taskManager has session, but history not yet written to DB
        SuggestionService suggestionService = mock(SuggestionService.class);
        DeepSuggestionService deepSuggestionService = mock(DeepSuggestionService.class);
        RecognitionHistoryRepository historyRepository = mock(RecognitionHistoryRepository.class);
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        AsyncRecognitionTaskManager taskManager = mock(AsyncRecognitionTaskManager.class);
        PerformanceMetricsService metricsService = mock(PerformanceMetricsService.class);
        SafeActionExecutor safeActionExecutor = mock(SafeActionExecutor.class);
        ActionCompiler actionCompiler = mock(ActionCompiler.class);
        NlpConversationManager conversationManager = mock(NlpConversationManager.class);
        SessionContextService sessionContextService = mock(SessionContextService.class);
        SuggestionCardCache suggestionCardCache = mock(SuggestionCardCache.class);
        CandidateFilterService candidateFilterService = mock(CandidateFilterService.class);
        when(sessionContextService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(SessionContextService.SessionContext.empty());
        when(suggestionCardCache.get(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        java.util.concurrent.ExecutorService searchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        SuggestionController controller = new SuggestionController(suggestionService, deepSuggestionService,
                historyRepository, sessionCache, taskManager, metricsService, safeActionExecutor, actionCompiler,
                conversationManager, sessionContextService, suggestionCardCache, candidateFilterService, searchExecutor);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtAuthenticationFilter.AuthPrincipal(7L, "a@example.com"),
                        null
                )
        );

        // taskManager says user owns the session
        when(taskManager.belongsToUser("activeSession", 7L)).thenReturn(true);
        // but history table has no record yet
        when(historyRepository.findBySessionIdAndUserId("activeSession", 7L)).thenReturn(Optional.empty());
        when(sessionCache.getCandidates("activeSession")).thenReturn(List.of());

        // Mock the unified action chain
        when(safeActionExecutor.compileSuggestion(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(actionCompiler.applyActionToFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(SearchFilter.empty());
        when(candidateFilterService.filter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.visioncart.service.search.CandidateFilterService.FilterResult(List.of(), 0, 0, false, false));
        ProductCard product = new ProductCard(
                "p1", "Shaver", "https://img.example/p1.jpg",
                BigDecimal.valueOf(199), null, "淘宝", false,
                "Shop", 4.8, 100, 0.9, List.of(), "https://detail.example/p1"
        );
        ActionResult actionResult = new ActionResult(
                List.of(product), SearchFilter.empty(), List.of(),
                true, false, false, "已应用", List.of(), List.of(),
                null, null, null, null, "suggestion", null, null, "NORMAL",
                    null
                );
        when(safeActionExecutor.executeAction(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(actionResult);

        SuggestionExecuteRequest request = new SuggestionExecuteRequest(
                "activeSession", "sort_by_price_asc", List.of(), SearchFilter.empty(), null);
        var response = controller.execute(request);

        // Should NOT be 403 — taskManager ownership is sufficient
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.data().products()).hasSize(1);
    }

    @Test
    void executeDeniedWhenNeitherTaskNorHistoryExists() {
        SuggestionService suggestionService = mock(SuggestionService.class);
        DeepSuggestionService deepSuggestionService = mock(DeepSuggestionService.class);
        RecognitionHistoryRepository historyRepository = mock(RecognitionHistoryRepository.class);
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        AsyncRecognitionTaskManager taskManager = mock(AsyncRecognitionTaskManager.class);
        PerformanceMetricsService metricsService = mock(PerformanceMetricsService.class);
        SuggestionController controller = createController(suggestionService, deepSuggestionService, historyRepository, sessionCache, taskManager, metricsService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtAuthenticationFilter.AuthPrincipal(7L, "a@example.com"),
                        null
                )
        );

        when(taskManager.belongsToUser("foreignSession", 7L)).thenReturn(false);
        when(historyRepository.findBySessionIdAndUserId("foreignSession", 7L)).thenReturn(Optional.empty());

        SuggestionExecuteRequest request = new SuggestionExecuteRequest(
                "foreignSession", "sort_by_price_asc", List.of(), SearchFilter.empty(), null);
        var response = controller.execute(request);

        assertThat(response.code()).isEqualTo(403);
    }

    @Test
    void cardsUseSessionCandidates() {
        SuggestionService suggestionService = mock(SuggestionService.class);
        DeepSuggestionService deepSuggestionService = mock(DeepSuggestionService.class);
        RecognitionHistoryRepository historyRepository = mock(RecognitionHistoryRepository.class);
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        AsyncRecognitionTaskManager taskManager = mock(AsyncRecognitionTaskManager.class);
        PerformanceMetricsService metricsService = mock(PerformanceMetricsService.class);
        SuggestionController controller = createController(suggestionService, deepSuggestionService, historyRepository, sessionCache, taskManager, metricsService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtAuthenticationFilter.AuthPrincipal(7L, "a@example.com"),
                        null
                )
        );

        RecognitionHistory history = new RecognitionHistory();
        history.setSessionId("sess1");
        history.setUserId(7L);
        ProductCard product = new ProductCard(
                "p1", "Shaver", "https://img.example/p1.jpg",
                BigDecimal.valueOf(199), null, "淘宝", false,
                "Shop", 4.8, 100, 0.9, List.of(), "https://detail.example/p1"
        );
        SuggestionCard card = new SuggestionCard(
                "sort_price", "价格从低到高", "快速找低价", "money",
                "sort_by_price_asc", 90
        );

        when(historyRepository.findBySessionIdAndUserId("sess1", 7L)).thenReturn(Optional.of(history));
        when(sessionCache.getCandidates("sess1")).thenReturn(List.of(product));
        when(suggestionService.cards(eq("app"), eq(List.of(product)))).thenReturn(List.of(card));

        var response = controller.cards("app", null, "sess1");

        assertThat(response.code()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var cards = (java.util.List<com.visioncart.api.dto.SuggestionCard>) response.data().get("cards");
        assertThat(cards).hasSize(2); // original card + general insight fallback
        assertThat(cards.get(0)).isEqualTo(card);
        assertThat(cards.get(1).id()).isEqualTo("insight_general");
        assertThat(response.data().get("insight_status")).isEqualTo("EMPTY");
    }

    @Test
    void cardsReturnFailedWithGeneralInsightWhenDeepSuggestionThrows() {
        SuggestionService suggestionService = mock(SuggestionService.class);
        DeepSuggestionService deepSuggestionService = mock(DeepSuggestionService.class);
        RecognitionHistoryRepository historyRepository = mock(RecognitionHistoryRepository.class);
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        AsyncRecognitionTaskManager taskManager = mock(AsyncRecognitionTaskManager.class);
        PerformanceMetricsService metricsService = mock(PerformanceMetricsService.class);
        SuggestionController controller = createController(suggestionService, deepSuggestionService, historyRepository, sessionCache, taskManager, metricsService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtAuthenticationFilter.AuthPrincipal(7L, "a@example.com"),
                        null
                )
        );

        RecognitionHistory history = new RecognitionHistory();
        history.setSessionId("sess1");
        history.setUserId(7L);
        ProductCard product = product("p1");
        SuggestionCard baseCard = new SuggestionCard(
                "sort_price", "价格从低到高", "快速找低价", "money",
                "sort_by_price_asc", 90
        );

        when(historyRepository.findBySessionIdAndUserId("sess1", 7L)).thenReturn(Optional.of(history));
        when(sessionCache.getCandidates("sess1")).thenReturn(List.of(product));
        when(suggestionService.cards(eq("app"), eq(List.of(product)))).thenReturn(List.of(baseCard));
        when(deepSuggestionService.insightCards(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("boom"));

        var response = controller.cards("app", null, "sess1");

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.data().get("insight_status")).isEqualTo("FAILED");
        @SuppressWarnings("unchecked")
        var cards = (java.util.List<SuggestionCard>) response.data().get("cards");
        assertThat(cards).extracting(SuggestionCard::id).contains("sort_price", "insight_general");
    }

    @Test
    void cardsReturnPendingWithoutDroppingBaseCardsWhenDeepSuggestionTimesOut() {
        SuggestionService suggestionService = mock(SuggestionService.class);
        DeepSuggestionService deepSuggestionService = mock(DeepSuggestionService.class);
        RecognitionHistoryRepository historyRepository = mock(RecognitionHistoryRepository.class);
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        AsyncRecognitionTaskManager taskManager = mock(AsyncRecognitionTaskManager.class);
        PerformanceMetricsService metricsService = mock(PerformanceMetricsService.class);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        SuggestionController controller = createController(suggestionService, deepSuggestionService, historyRepository, sessionCache, taskManager, metricsService,
                mock(SuggestionCardCache.class), executor);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtAuthenticationFilter.AuthPrincipal(7L, "a@example.com"),
                        null
                )
        );

        RecognitionHistory history = new RecognitionHistory();
        history.setSessionId("sess1");
        history.setUserId(7L);
        ProductCard product = product("p1");
        SuggestionCard baseCard = new SuggestionCard(
                "sort_price", "价格从低到高", "快速找低价", "money",
                "sort_by_price_asc", 90
        );
        SuggestionCard insightCard = new SuggestionCard(
                "insight_price", "AI 导购分析", "价格集中", "insight",
                "filter_price_band:80:130", 60
        );

        when(historyRepository.findBySessionIdAndUserId("sess1", 7L)).thenReturn(Optional.of(history));
        when(sessionCache.getCandidates("sess1")).thenReturn(List.of(product));
        when(suggestionService.cards(eq("app"), eq(List.of(product)))).thenReturn(List.of(baseCard));
        when(deepSuggestionService.insightCards(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(1500);
                    return List.of(insightCard);
                });

        try {
            var response = controller.cards("app", null, "sess1");

            assertThat(response.code()).isEqualTo(200);
            assertThat(response.data().get("insight_status")).isEqualTo("PENDING");
            @SuppressWarnings("unchecked")
            var cards = (java.util.List<SuggestionCard>) response.data().get("cards");
            assertThat(cards).extracting(SuggestionCard::id).containsExactly("sort_price");
        } finally {
            executor.shutdownNow();
        }
    }

    private SuggestionController createController(SuggestionService suggestionService,
                                                  DeepSuggestionService deepSuggestionService,
                                                  RecognitionHistoryRepository historyRepository,
                                                  CandidateSessionCache sessionCache,
                                                  AsyncRecognitionTaskManager taskManager,
                                                  PerformanceMetricsService metricsService,
                                                  SuggestionCardCache suggestionCardCache,
                                                  java.util.concurrent.ExecutorService searchExecutor) {
        SafeActionExecutor safeActionExecutor = mock(SafeActionExecutor.class);
        ActionCompiler actionCompiler = mock(ActionCompiler.class);
        NlpConversationManager conversationManager = mock(NlpConversationManager.class);
        SessionContextService sessionContextService = mock(SessionContextService.class);
        CandidateFilterService candidateFilterService = mock(CandidateFilterService.class);
        when(sessionContextService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(SessionContextService.SessionContext.empty());
        when(suggestionCardCache.get(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        return new SuggestionController(suggestionService, deepSuggestionService, historyRepository,
                sessionCache, taskManager, metricsService, safeActionExecutor, actionCompiler,
                conversationManager, sessionContextService, suggestionCardCache, candidateFilterService, searchExecutor);
    }

    private ProductCard product(String id) {
        return new ProductCard(
                id, "Shaver", "https://img.example/" + id + ".jpg",
                BigDecimal.valueOf(199), null, "淘宝", false,
                "Shop", 4.8, 100, 0.9, List.of(), "https://detail.example/" + id
        );
    }
}
