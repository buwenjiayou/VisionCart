package com.visioncart.service.action;

import com.visioncart.api.dto.ActionResult;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.api.dto.UserAction;
import com.visioncart.service.context.SessionContextService;
import com.visioncart.service.filter.ActionCompiler;
import com.visioncart.service.filter.FilterClause;
import com.visioncart.service.filter.NlpUndoService;
import com.visioncart.service.filter.SafeActionExecutor;
import com.visioncart.service.filter.semantic.SemanticActionExecutor;
import com.visioncart.service.nlp.LlmSemanticPlanner;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.nlp.SemanticExecutionPolicy;
import com.visioncart.service.recognition.AttributeCorrectionService;
import com.visioncart.service.recognition.SessionHistoryService;
import com.visioncart.service.search.CandidateFilterService;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.search.ProductReputationService;
import com.visioncart.service.search.ProductSortService;
import com.visioncart.service.search.SearchRunService;
import com.visioncart.service.suggestion.SuggestionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActionExecutionServiceBoundaryTest {

    @Test
    void correctionExecutionDependsOnServiceNotController() {
        assertThat(Arrays.stream(ActionExecutionService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .contains(AttributeCorrectionService.class.getName())
                .noneMatch(name -> name.endsWith(".AttributeCorrectionController"));

        assertThat(Arrays.stream(ActionExecutionService.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .map(Class::getName))
                .contains(AttributeCorrectionService.class.getName())
                .noneMatch(name -> name.endsWith(".AttributeCorrectionController"));
    }

    @Test
    void reputationSortActionUsesSearchRunEvenWhenSafeActionCommits() {
        AttributeCorrectionService attributeCorrectionService = mock(AttributeCorrectionService.class);
        SafeActionExecutor safeActionExecutor = mock(SafeActionExecutor.class);
        ActionCompiler actionCompiler = new ActionCompiler();
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        CandidateFilterService filterService = mock(CandidateFilterService.class);
        NlpConversationManager conversationManager = mock(NlpConversationManager.class);
        NlpUndoService undoService = mock(NlpUndoService.class);
        SuggestionService suggestionService = mock(SuggestionService.class);
        SessionContextService sessionContextService = mock(SessionContextService.class);
        SessionHistoryService sessionHistoryService = mock(SessionHistoryService.class);
        LlmSemanticPlanner llmSemanticPlanner = mock(LlmSemanticPlanner.class);
        SemanticActionExecutor semanticActionExecutor = mock(SemanticActionExecutor.class);
        ProductSortService productSortService = mock(ProductSortService.class);
        ProductReputationService reputationService = mock(ProductReputationService.class);
        SemanticExecutionPolicy semanticExecutionPolicy = mock(SemanticExecutionPolicy.class);
        SearchRunService searchRunService = mock(SearchRunService.class);

        ActionExecutionService service = new ActionExecutionService(
                attributeCorrectionService,
                safeActionExecutor,
                actionCompiler,
                sessionCache,
                filterService,
                conversationManager,
                undoService,
                suggestionService,
                sessionContextService,
                sessionHistoryService,
                llmSemanticPlanner,
                semanticActionExecutor,
                productSortService,
                reputationService,
                semanticExecutionPolicy,
                searchRunService);

        ProductCard stale = product("old-safe", "SafeAction product");
        ProductCard fresh = product("new-run", "SearchRun product");
        SearchFilter empty = SearchFilter.empty();
        SearchFilter reputationFilter = new SearchFilter(
                empty.priceRange(), empty.platforms(), empty.selfOperated(), empty.colors(), empty.brands(),
                empty.ratingMin(), "review_quality", "desc", empty.keyword(), empty.attributes(),
                empty.excludeRoles(), empty.capabilities());

        when(conversationManager.getFilterState("sess-sort")).thenReturn(empty);
        when(sessionCache.getBestCandidates("sess-sort")).thenReturn(List.of(stale));
        when(filterService.filter(anyList(), any(), anyMap(), anyInt(), anyInt()))
                .thenReturn(new CandidateFilterService.FilterResult(List.of(stale), 1, 1, false, false));
        when(safeActionExecutor.compileSuggestion("sort_by_review_quality", empty))
                .thenReturn(List.of(FilterClause.rerank("sort-review-quality", "口碑优先", "sort_by", "eq", "review_quality")));
        when(sessionContextService.resolve("sess-sort", 7L))
                .thenReturn(new SessionContextService.SessionContext(
                        "sess-sort", "phone", "phone", "phone", "main",
                        Map.of(), Set.of("main", "unknown"), true, "zh-CN"));
        when(safeActionExecutor.executeAction(any(), anyList(), anyList(), anyList(), any(), any(), any(), anyString()))
                .thenReturn(ActionResult.filtered(List.of(stale), reputationFilter, List.of(), true,
                        "safe", List.of(), List.of()));
        when(searchRunService.recomputeDisplayPage(eq("sess-sort"), any(SearchFilter.class), eq(50)))
                .thenReturn(Optional.of(new SearchResult(1, List.of(fresh), List.of(), List.of(), false, "run-1")));
        when(suggestionService.cards(eq("app"), anyList(), anyMap(), any())).thenReturn(List.of());

        ActionResult result = service.execute(UserAction.sort("sess-sort", "rating_desc"), 7L);

        assertThat(result.products()).extracting(ProductCard::id).containsExactly("new-run");
        assertThat(result.appliedFilter().sortBy()).isEqualTo("review_quality");
        verify(searchRunService).recomputeDisplayPage(eq("sess-sort"), argThat(filter ->
                filter != null && "review_quality".equals(filter.sortBy())), eq(50));
        verify(sessionHistoryService).archiveDisplayedProducts("sess-sort", List.of(fresh));
    }

    private static ProductCard product(String id, String title) {
        return new ProductCard(
                id,
                title,
                "https://img.example/" + id + ".jpg",
                BigDecimal.valueOf(99),
                null,
                "淘宝",
                false,
                "测试店铺",
                4.8,
                10,
                0.9,
                List.of(),
                "https://detail.example/" + id,
                "BrandX",
                "shop_dsr",
                "10 sold");
    }
}
