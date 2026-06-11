package com.visioncart.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.*;
import com.visioncart.config.JwtAuthenticationFilter;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.action.ActionExecutionService;
import com.visioncart.service.filter.NlpUndoService;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.nlp.NlpStateStackService;
import com.visioncart.service.recognition.AsyncRecognitionTaskManager;
import com.visioncart.service.recognition.SessionHistoryService;
import com.visioncart.service.search.CandidateFilterService;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.search.SearchRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActionUndoControllerTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void correctionUndoRestoresPreviousSnapshotWithoutRefiltering() {
        NlpUndoService undoService = mock(NlpUndoService.class);
        NlpConversationManager conversationManager = mock(NlpConversationManager.class);
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        CandidateFilterService filterService = mock(CandidateFilterService.class);
        AsyncRecognitionTaskManager taskManager = mock(AsyncRecognitionTaskManager.class);
        RecognitionHistoryRepository historyRepository = mock(RecognitionHistoryRepository.class);
        SessionHistoryService sessionHistoryService = mock(SessionHistoryService.class);
        ActionExecutionService actionExecutionService = mock(ActionExecutionService.class);
        SearchRunService searchRunService = mock(SearchRunService.class);
        NlpStateStackService nlpStateStackService = mock(NlpStateStackService.class);

        ActionUndoController controller = new ActionUndoController(
                undoService,
                conversationManager,
                sessionCache,
                filterService,
                taskManager,
                historyRepository,
                sessionHistoryService,
                actionExecutionService,
                new ObjectMapper(),
                searchRunService,
                nlpStateStackService);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtAuthenticationFilter.AuthPrincipal(7L, "u@example.com"), null, List.of()));

        SearchFilter restoredFilter = SearchFilter.empty();
        List<ProductCard> previousCandidatePool = List.of(product("old-1"), product("old-2"));

        when(taskManager.belongsToUser("sess-undo", 7L)).thenReturn(true);
        when(undoService.canUndo("sess-undo")).thenReturn(true, false);
        when(undoService.undo("sess-undo")).thenReturn(new NlpUndoService.UndoResult(
                restoredFilter,
                previousCandidatePool,
                "颜色=黑色",
                "correction",
                Map.of("颜色", new AttributeValue("白色", 0.82, false)),
                null, null, null));
        when(actionExecutionService.generateStructuredTags(restoredFilter)).thenReturn(List.of());
        when(historyRepository.findBySessionIdAndUserId("sess-undo", 7L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<ActionResult>> response = controller.undo("sess-undo");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(200);
        ActionResult result = response.getBody().data();
        assertThat(result.products()).extracting(ProductCard::id).containsExactly("old-1", "old-2");
        assertThat(result.productsUpdated()).isTrue();
        assertThat(result.updatedAttributes()).containsKey("颜色");

        verify(sessionCache).saveDefaultClassifiedPool("sess-undo", previousCandidatePool, restoredFilter, null);
        verify(filterService, never()).filter(anyList(), any(), anyMap(), anyInt(), anyInt());
        verify(sessionHistoryService).archiveDisplayedProducts("sess-undo", previousCandidatePool);
    }

    @Test
    void nlpUndoPopsStateStackAndRestoresPreviousNlpState() {
        NlpUndoService undoService = mock(NlpUndoService.class);
        NlpConversationManager conversationManager = mock(NlpConversationManager.class);
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        CandidateFilterService filterService = mock(CandidateFilterService.class);
        AsyncRecognitionTaskManager taskManager = mock(AsyncRecognitionTaskManager.class);
        RecognitionHistoryRepository historyRepository = mock(RecognitionHistoryRepository.class);
        SessionHistoryService sessionHistoryService = mock(SessionHistoryService.class);
        ActionExecutionService actionExecutionService = mock(ActionExecutionService.class);
        SearchRunService searchRunService = mock(SearchRunService.class);
        NlpStateStackService nlpStateStackService = mock(NlpStateStackService.class);

        ActionUndoController controller = new ActionUndoController(
                undoService,
                conversationManager,
                sessionCache,
                filterService,
                taskManager,
                historyRepository,
                sessionHistoryService,
                actionExecutionService,
                new ObjectMapper(),
                searchRunService,
                nlpStateStackService);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtAuthenticationFilter.AuthPrincipal(7L, "u@example.com"), null, List.of()));

        SearchFilter firstFilter = new SearchFilter(
                new PriceRange(null, 20.0),
                List.of(), null, List.of(), List.of(), null,
                null, "desc", null, Map.of(), List.of(), Map.of());
        List<ProductCard> firstProducts = List.of(product("first-1"));
        List<FilterTag> firstTags = List.of(FilterTag.ofField("20元以内", "price_range.max"));

        when(taskManager.belongsToUser("sess-nlp-undo", 7L)).thenReturn(true);
        when(undoService.canUndo("sess-nlp-undo")).thenReturn(true, false);
        when(undoService.undo("sess-nlp-undo")).thenReturn(new NlpUndoService.UndoResult(
                SearchFilter.empty(),
                List.of(product("initial-1")),
                "我现在要大于50的",
                "nlp",
                null,
                null, null, null));
        when(nlpStateStackService.peek("sess-nlp-undo")).thenReturn(Optional.of(
                new NlpStateStackService.NlpState(
                        "nlp-1",
                        "我要低于20的",
                        firstFilter,
                        firstTags,
                        firstProducts,
                        "2026-06-09T00:00:00Z")));
        when(sessionCache.getBestCandidates("sess-nlp-undo")).thenReturn(List.of(product("candidate")));

        ResponseEntity<ApiResponse<ActionResult>> response = controller.undo("sess-nlp-undo");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(200);
        ActionResult result = response.getBody().data();
        assertThat(result.appliedFilter()).isEqualTo(firstFilter);
        assertThat(result.products()).extracting(ProductCard::id).containsExactly("first-1");
        assertThat(result.filterTags()).extracting(FilterTag::label).containsExactly("20元以内");

        verify(nlpStateStackService).pop("sess-nlp-undo");
        verify(conversationManager).setFilterState("sess-nlp-undo", firstFilter);
        verify(sessionHistoryService).archiveDisplayedProducts("sess-nlp-undo", firstProducts);
    }

    @Test
    void suggestionUndoKeepsCurrentNlpTagsWithoutPoppingNlpState() {
        NlpUndoService undoService = mock(NlpUndoService.class);
        NlpConversationManager conversationManager = mock(NlpConversationManager.class);
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        CandidateFilterService filterService = mock(CandidateFilterService.class);
        AsyncRecognitionTaskManager taskManager = mock(AsyncRecognitionTaskManager.class);
        RecognitionHistoryRepository historyRepository = mock(RecognitionHistoryRepository.class);
        SessionHistoryService sessionHistoryService = mock(SessionHistoryService.class);
        ActionExecutionService actionExecutionService = mock(ActionExecutionService.class);
        SearchRunService searchRunService = mock(SearchRunService.class);
        NlpStateStackService nlpStateStackService = mock(NlpStateStackService.class);

        ActionUndoController controller = new ActionUndoController(
                undoService,
                conversationManager,
                sessionCache,
                filterService,
                taskManager,
                historyRepository,
                sessionHistoryService,
                actionExecutionService,
                new ObjectMapper(),
                searchRunService,
                nlpStateStackService);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtAuthenticationFilter.AuthPrincipal(7L, "u@example.com"), null, List.of()));

        SearchFilter restoredFilter = new SearchFilter(
                null, List.of(), null, List.of(), List.of("Apple"), null,
                null, "desc", null, Map.of(), List.of(), Map.of());
        List<ProductCard> restoredProducts = List.of(product("after-nlp-2"));
        List<FilterTag> currentNlpTags = List.of(
                FilterTag.ofField("brand Apple", "brands.Apple"),
                FilterTag.ofField("under 100", "price_range.max"));

        when(taskManager.belongsToUser("sess-suggestion-undo", 7L)).thenReturn(true);
        when(undoService.canUndo("sess-suggestion-undo")).thenReturn(true, true);
        when(undoService.undo("sess-suggestion-undo")).thenReturn(new NlpUndoService.UndoResult(
                restoredFilter,
                restoredProducts,
                "best value",
                "suggestion",
                null,
                null, null, null));
        when(nlpStateStackService.peek("sess-suggestion-undo")).thenReturn(Optional.of(
                new NlpStateStackService.NlpState(
                        "nlp-2",
                        "under 100",
                        SearchFilter.empty(),
                        currentNlpTags,
                        List.of(product("nlp-state-product")),
                        "2026-06-09T00:00:00Z")));
        when(sessionCache.getBestCandidates("sess-suggestion-undo")).thenReturn(List.of(product("candidate")));

        ResponseEntity<ApiResponse<ActionResult>> response = controller.undo("sess-suggestion-undo");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(200);
        ActionResult result = response.getBody().data();
        assertThat(result.actionSource()).isEqualTo("undo:suggestion");
        assertThat(result.products()).extracting(ProductCard::id).containsExactly("after-nlp-2");
        assertThat(result.filterTags()).extracting(FilterTag::label)
                .containsExactly("brand Apple", "under 100");

        verify(nlpStateStackService, never()).pop("sess-suggestion-undo");
        verify(nlpStateStackService).peek("sess-suggestion-undo");
        verify(actionExecutionService, never()).generateStructuredTags(any());
        verify(conversationManager).setFilterState("sess-suggestion-undo", restoredFilter);
    }

    private ProductCard product(String id) {
        return new ProductCard(
                id,
                "旧商品 " + id,
                "https://img.example/" + id + ".jpg",
                BigDecimal.valueOf(99),
                null,
                "淘宝",
                false,
                "旧店铺",
                4.8,
                100,
                0.9,
                List.of(),
                "https://detail.example/" + id,
                "BrandX",
                "item_rating",
                "100 sold"
        );
    }
}
