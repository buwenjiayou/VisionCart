package com.visioncart.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.*;
import com.visioncart.config.JwtAuthenticationFilter;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.action.ActionExecutionService;
import com.visioncart.service.filter.NlpUndoService;
import com.visioncart.service.nlp.NlpConversationManager;
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
                searchRunService);

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
