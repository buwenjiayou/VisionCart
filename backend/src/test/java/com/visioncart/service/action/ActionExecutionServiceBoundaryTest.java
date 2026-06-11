package com.visioncart.service.action;

import com.visioncart.api.dto.ActionResult;
import com.visioncart.api.dto.FilterTag;
import com.visioncart.api.dto.SemanticActionPlan;
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
import com.visioncart.service.nlp.NlpStateStackService;
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
    void nlpActionPassesRawTextHistoryAndPushesSuccessfulState() {
        Harness h = harness();
        ProductCard product = product("p1", "Product");
        SearchFilter empty = SearchFilter.empty();
        SearchFilter applied = new SearchFilter(
                new com.visioncart.api.dto.PriceRange(50.0, null),
                empty.platforms(), empty.selfOperated(), empty.colors(), empty.brands(),
                empty.ratingMin(), empty.sortBy(), empty.sortOrder(), empty.keyword(),
                empty.attributes(), empty.excludeRoles(), empty.capabilities());
        SemanticActionPlan rawPlan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "product",
                List.of(new SemanticActionPlan.HardFilter("price", ">=", 50.0)), null,
                null, null, null, "KEEP_PREVIOUS_RESULTS");
        ActionResult executed = ActionResult.filtered(
                List.of(product), applied, List.of(), true, "ok", List.of(), List.of());

        when(h.nlpStateStackService.limitReached("sess-nlp")).thenReturn(false);
        when(h.nlpStateStackService.historyText("sess-nlp")).thenReturn("1. 我要低于20的");
        when(h.conversationManager.getFilterState("sess-nlp")).thenReturn(empty);
        when(h.sessionCache.getBestCandidates("sess-nlp")).thenReturn(List.of(product));
        when(h.filterService.filter(anyList(), any(), anyMap(), anyInt(), anyInt()))
                .thenReturn(new CandidateFilterService.FilterResult(List.of(product), 1, 1, false, false));
        when(h.sessionContextService.resolve(eq("sess-nlp"), eq(7L), any()))
                .thenReturn(new SessionContextService.SessionContext(
                        "sess-nlp", "product", "product", "product", "main",
                        Map.of(), Set.of("main"), true, "zh-CN"));
        when(h.llmSemanticPlanner.plan(eq("我现在要大于50的"), any(), any(), eq("1. 我要低于20的")))
                .thenReturn(rawPlan);
        when(h.semanticExecutionPolicy.normalizeForSyncExecution(anyString(), same(rawPlan), anyInt()))
                .thenReturn(rawPlan);
        when(h.semanticActionExecutor.execute(eq("sess-nlp"), anyList(), eq(SearchFilter.empty()), same(rawPlan), anyList()))
                .thenReturn(executed);
        when(h.suggestionService.cards(eq("app"), anyList(), anyMap(), any())).thenReturn(List.of());

        ActionResult result = h.service.execute(UserAction.nlpFilter("sess-nlp", "我现在要大于50的"), 7L);

        assertThat(result.appliedFilter().priceRange().min()).isEqualTo(50.0);
        verify(h.llmSemanticPlanner).plan(eq("我现在要大于50的"), any(), any(), eq("1. 我要低于20的"));
        verify(h.semanticActionExecutor).execute(eq("sess-nlp"), anyList(), eq(SearchFilter.empty()), same(rawPlan), anyList());
        verify(h.nlpStateStackService).push(eq("sess-nlp"), anyString(), eq("我现在要大于50的"),
                same(applied), anyList(), eq(List.of(product)));
    }

    @Test
    void nlpLimitShortCircuitsPlannerAndKeepsCurrentState() {
        Harness h = harness();
        ProductCard product = product("p1", "Product");
        SearchFilter current = SearchFilter.empty();
        when(h.nlpStateStackService.limitReached("sess-limit")).thenReturn(true);
        when(h.conversationManager.getFilterState("sess-limit")).thenReturn(current);
        when(h.sessionCache.getBestCandidates("sess-limit")).thenReturn(List.of(product));
        when(h.filterService.filter(anyList(), any(), anyMap(), anyInt(), anyInt()))
                .thenReturn(new CandidateFilterService.FilterResult(List.of(product), 1, 1, false, false));
        when(h.suggestionService.cards(eq("app"), anyList(), anyMap(), any())).thenReturn(List.of());

        ActionResult result = h.service.execute(UserAction.nlpFilter("sess-limit", "再便宜点"), 7L);

        assertThat(result.filterApplied()).isFalse();
        assertThat(result.keptPreviousResults()).isTrue();
        assertThat(result.messageCode()).isEqualTo("nlp.limit_reached");
        verifyNoInteractions(h.llmSemanticPlanner);
        verify(h.nlpStateStackService, never()).push(anyString(), anyString(), anyString(), any(), anyList(), anyList());
    }

    @Test
    void emptyNlpPlanKeepsCurrentStateAndTags() {
        Harness h = harness();
        ProductCard product = product("p1", "Product");
        SearchFilter current = new SearchFilter(
                new com.visioncart.api.dto.PriceRange(null, 10.0),
                List.of(), null, List.of(), List.of(), null,
                null, "desc", null, Map.of(), List.of(), Map.of());
        List<FilterTag> currentTags = List.of(FilterTag.ofField("<=10", "price_range.max"));
        SemanticActionPlan emptyPlan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "product", null, null,
                null, null, null, "KEEP_PREVIOUS_RESULTS");

        when(h.nlpStateStackService.limitReached("sess-empty")).thenReturn(false);
        when(h.nlpStateStackService.historyText("sess-empty")).thenReturn("1. under 10");
        when(h.nlpStateStackService.peek("sess-empty")).thenReturn(Optional.of(
                new NlpStateStackService.NlpState(
                        "nlp-1",
                        "under 10",
                        current,
                        currentTags,
                        List.of(product),
                        "2026-06-10T00:00:00Z")));
        when(h.conversationManager.getFilterState("sess-empty")).thenReturn(current);
        when(h.sessionCache.getBestCandidates("sess-empty")).thenReturn(List.of(product));
        when(h.sessionContextService.resolve(eq("sess-empty"), eq(7L), any()))
                .thenReturn(new SessionContextService.SessionContext(
                        "sess-empty", "product", "product", "product", "main",
                        Map.of(), Set.of("main"), true, "zh-CN"));
        when(h.llmSemanticPlanner.plan(eq("for young people"), any(), any(), eq("1. under 10")))
                .thenReturn(emptyPlan);
        when(h.semanticExecutionPolicy.normalizeForSyncExecution(anyString(), same(emptyPlan), anyInt()))
                .thenReturn(emptyPlan);

        ActionResult result = h.service.execute(UserAction.nlpFilter("sess-empty", "for young people"), 7L);

        assertThat(result.filterApplied()).isFalse();
        assertThat(result.keptPreviousResults()).isTrue();
        assertThat(result.messageCode()).isEqualTo("nlp.no_action");
        assertThat(result.products()).extracting(ProductCard::id).containsExactly("p1");
        assertThat(result.appliedFilter()).isEqualTo(current);
        assertThat(result.filterTags()).isEqualTo(currentTags);
        verifyNoInteractions(h.semanticActionExecutor);
        verify(h.undoService, never()).saveUndoPoint(anyString(), any(), anyList(), anyString(), anyString(), any(), any(), any());
        verify(h.nlpStateStackService, never()).push(anyString(), anyString(), anyString(), any(), anyList(), anyList());
    }

    @Test
    void successfulNlpUsesCurrentPlanTagsWithoutMergingPreviousActiveTags() {
        Harness h = harness();
        ProductCard product = product("p1", "Power bank");
        SearchFilter priceFilter = new SearchFilter(
                new com.visioncart.api.dto.PriceRange(null, 50.0),
                List.of(), null, List.of(), List.of(), null,
                null, "desc", null, Map.of(), List.of(), Map.of());
        SearchFilter empty = SearchFilter.empty();
        SearchFilter applied = new SearchFilter(
                empty.priceRange(), empty.platforms(), empty.selfOperated(),
                empty.colors(), empty.brands(), empty.ratingMin(),
                empty.sortBy(), empty.sortOrder(), empty.keyword(),
                empty.attributes(), empty.excludeRoles(), Map.of("airplane_allowed", true));
        List<FilterTag> previousTags = List.of(
                FilterTag.ofField("<=50", "price_range.max"),
                FilterTag.ofPreference("young_user", "young style", "young style"));
        FilterTag airplaneTag = FilterTag.ofCapability(
                "airplane_allowed", "airplane allowed", "airplane allowed");
        SemanticActionPlan rawPlan = new SemanticActionPlan(
                "filter_current_results", "SEMANTIC_SCREENING", "power_bank",
                null,
                List.of(new SemanticActionPlan.SemanticFilter(
                        "airplane_allowed", "airplane allowed", "EVIDENCE_OR_SCORE",
                        List.of(), List.of(), "KEEP_AS_SECONDARY")),
                null, null, null, "KEEP_PREVIOUS_RESULTS");
        ActionResult executed = ActionResult.filtered(
                List.of(product), applied, List.of(airplaneTag), true,
                "ok", List.of(), List.of());

        when(h.nlpStateStackService.limitReached("sess-cumulative")).thenReturn(false);
        when(h.nlpStateStackService.historyText("sess-cumulative"))
                .thenReturn("1. under 50\n2. young style");
        when(h.nlpStateStackService.peek("sess-cumulative")).thenReturn(Optional.of(
                new NlpStateStackService.NlpState(
                        "nlp-2", "young style", priceFilter, previousTags,
                        List.of(product), "2026-06-10T00:00:00Z")));
        when(h.conversationManager.getFilterState("sess-cumulative")).thenReturn(priceFilter);
        when(h.sessionCache.getBestCandidates("sess-cumulative")).thenReturn(List.of(product));
        when(h.sessionContextService.resolve(eq("sess-cumulative"), eq(7L), any()))
                .thenReturn(new SessionContextService.SessionContext(
                        "sess-cumulative", "power_bank", "power_bank", "power_bank", "main",
                        Map.of(), Set.of("main"), true, "zh-CN"));
        when(h.llmSemanticPlanner.plan(eq("airplane allowed"), any(), any(), anyString()))
                .thenReturn(rawPlan);
        when(h.semanticExecutionPolicy.normalizeForSyncExecution(anyString(), same(rawPlan), anyInt()))
                .thenReturn(rawPlan);
        when(h.semanticActionExecutor.execute(eq("sess-cumulative"), anyList(), eq(SearchFilter.empty()),
                same(rawPlan), anyList())).thenReturn(executed);
        when(h.suggestionService.cards(eq("app"), anyList(), anyMap(), any())).thenReturn(List.of());

        ActionResult result = h.service.execute(UserAction.nlpFilter("sess-cumulative", "airplane allowed"), 7L);

        assertThat(result.filterTags()).extracting(FilterTag::filterPath)
                .containsExactly("capabilities.airplane_allowed");
        assertThat(result.filterTags()).extracting(FilterTag::label)
                .containsExactly("airplane allowed");
        assertThat(result.appliedFilter()).isEqualTo(applied);
        verify(h.conversationManager).setFilterState("sess-cumulative", applied);
        verify(h.nlpStateStackService).push(eq("sess-cumulative"), anyString(), eq("airplane allowed"),
                same(applied),
                argThat(tags -> tags != null
                        && tags.stream().map(FilterTag::filterPath).toList().equals(List.of(
                        "capabilities.airplane_allowed"))),
                eq(List.of(product)));
    }

    @Test
    void successfulNlpReplacesSameFilterPathInsteadOfDuplicatingTags() {
        Harness h = harness();
        ProductCard product = product("p1", "Product");
        SearchFilter previousFilter = new SearchFilter(
                new com.visioncart.api.dto.PriceRange(null, 50.0),
                List.of(), null, List.of(), List.of(), null,
                null, "desc", null, Map.of(), List.of(), Map.of());
        SearchFilter applied = new SearchFilter(
                new com.visioncart.api.dto.PriceRange(null, 30.0),
                List.of(), null, List.of(), List.of(), null,
                null, "desc", null, Map.of(), List.of(), Map.of());
        List<FilterTag> previousTags = List.of(FilterTag.ofField("<=50", "price_range.max"));
        SemanticActionPlan rawPlan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "product",
                List.of(new SemanticActionPlan.HardFilter("price", "<=", 30.0)),
                null, null, null, null, "KEEP_PREVIOUS_RESULTS");
        ActionResult executed = ActionResult.filtered(
                List.of(product), applied, List.of(FilterTag.ofField("<=30", "price_range.max")),
                true, "ok", List.of(), List.of());

        when(h.nlpStateStackService.limitReached("sess-replace")).thenReturn(false);
        when(h.nlpStateStackService.historyText("sess-replace")).thenReturn("1. under 50");
        when(h.nlpStateStackService.peek("sess-replace")).thenReturn(Optional.of(
                new NlpStateStackService.NlpState(
                        "nlp-1", "under 50", previousFilter, previousTags,
                        List.of(product), "2026-06-10T00:00:00Z")));
        when(h.conversationManager.getFilterState("sess-replace")).thenReturn(previousFilter);
        when(h.sessionCache.getBestCandidates("sess-replace")).thenReturn(List.of(product));
        when(h.sessionContextService.resolve(eq("sess-replace"), eq(7L), any()))
                .thenReturn(new SessionContextService.SessionContext(
                        "sess-replace", "product", "product", "product", "main",
                        Map.of(), Set.of("main"), true, "zh-CN"));
        when(h.llmSemanticPlanner.plan(eq("under 30"), any(), any(), anyString()))
                .thenReturn(rawPlan);
        when(h.semanticExecutionPolicy.normalizeForSyncExecution(anyString(), same(rawPlan), anyInt()))
                .thenReturn(rawPlan);
        when(h.semanticActionExecutor.execute(eq("sess-replace"), anyList(), eq(SearchFilter.empty()),
                same(rawPlan), anyList())).thenReturn(executed);
        when(h.suggestionService.cards(eq("app"), anyList(), anyMap(), any())).thenReturn(List.of());

        ActionResult result = h.service.execute(UserAction.nlpFilter("sess-replace", "under 30"), 7L);

        assertThat(result.filterTags()).extracting(FilterTag::filterPath)
                .containsExactly("price_range.max");
        assertThat(result.filterTags()).extracting(FilterTag::label)
                .containsExactly("<=30");
        assertThat(result.filterTags()).extracting(FilterTag::label)
                .doesNotContain("<=50");
    }

    @Test
    void successfulNlpStoresFullExecutorStateWithDifferentPriceAttributeAndPreference() {
        Harness h = harness();
        ProductCard product = product("p1", "Wireless mouse");
        SearchFilter applied = new SearchFilter(
                new com.visioncart.api.dto.PriceRange(200.0, null),
                List.of(), null, List.of(), List.of(), null,
                null, "desc", null,
                Map.of("\u8fde\u63a5\u65b9\u5f0f", "\u65e0\u7ebf"),
                List.of(), Map.of());
        List<FilterTag> fullStateTags = List.of(
                FilterTag.ofField(">=200", "price_range.min"),
                FilterTag.ofField("\u65e0\u7ebf", "attributes.\u8fde\u63a5\u65b9\u5f0f"),
                FilterTag.ofPreference("commuting_friendly", "\u901a\u52e4\u8f7b\u4fbf", "\u901a\u52e4\u8f7b\u4fbf"));
        SemanticActionPlan rawPlan = new SemanticActionPlan(
                "filter_current_results", "COMBINED", "mouse",
                List.of(
                        new SemanticActionPlan.HardFilter("price", ">=", 200.0),
                        new SemanticActionPlan.HardFilter(
                                "attribute:\u8fde\u63a5\u65b9\u5f0f", "equals", "\u65e0\u7ebf")),
                null,
                List.of(new SemanticActionPlan.PreferenceRule(
                        "commuting_friendly", "\u901a\u52e4\u8f7b\u4fbf", 0.8)),
                null, null, "KEEP_PREVIOUS_RESULTS");
        ActionResult executed = ActionResult.filtered(
                List.of(product), applied, fullStateTags, true, "ok", List.of(), List.of());

        when(h.nlpStateStackService.limitReached("sess-full-state")).thenReturn(false);
        when(h.nlpStateStackService.historyText("sess-full-state"))
                .thenReturn("1. budget above 200");
        when(h.conversationManager.getFilterState("sess-full-state")).thenReturn(SearchFilter.empty());
        when(h.sessionCache.getBestCandidates("sess-full-state")).thenReturn(List.of(product));
        when(h.filterService.filter(anyList(), any(), anyMap(), anyInt(), anyInt()))
                .thenReturn(new CandidateFilterService.FilterResult(List.of(product), 1, 1, false, false));
        when(h.sessionContextService.resolve(eq("sess-full-state"), eq(7L), any()))
                .thenReturn(new SessionContextService.SessionContext(
                        "sess-full-state", "mouse", "mouse", "mouse", "main",
                        Map.of(), Set.of("main"), true, "zh-CN"));
        when(h.llmSemanticPlanner.plan(eq("wireless commute mouse"), any(), any(), anyString()))
                .thenReturn(rawPlan);
        when(h.semanticExecutionPolicy.normalizeForSyncExecution(anyString(), same(rawPlan), anyInt()))
                .thenReturn(rawPlan);
        when(h.semanticActionExecutor.execute(eq("sess-full-state"), anyList(), eq(SearchFilter.empty()),
                same(rawPlan), anyList())).thenReturn(executed);
        when(h.suggestionService.cards(eq("app"), anyList(), anyMap(), any())).thenReturn(List.of());

        ActionResult result = h.service.execute(
                UserAction.nlpFilter("sess-full-state", "wireless commute mouse"), 7L);

        assertThat(result.appliedFilter()).isEqualTo(applied);
        assertThat(result.filterTags()).extracting(FilterTag::filterPath)
                .containsExactly("price_range.min",
                        "attributes.\u8fde\u63a5\u65b9\u5f0f",
                        "preferences.commuting_friendly");
        verify(h.nlpStateStackService).push(eq("sess-full-state"), anyString(), eq("wireless commute mouse"),
                same(applied), eq(fullStateTags), eq(List.of(product)));
    }

    @Test
    void clearFilterClearsNlpStateStackAndUndoStack() {
        Harness h = harness();
        ProductCard product = product("p1", "Product");
        when(h.conversationManager.getFilterState("sess-clear")).thenReturn(SearchFilter.empty());
        when(h.sessionCache.getBestCandidates("sess-clear")).thenReturn(List.of(product));
        when(h.filterService.filter(anyList(), any(), anyMap(), anyInt(), anyInt()))
                .thenReturn(new CandidateFilterService.FilterResult(List.of(product), 1, 1, false, false));
        when(h.searchRunService.recomputeDisplayPage(eq("sess-clear"), eq(SearchFilter.empty()), eq(50)))
                .thenReturn(Optional.empty());
        when(h.suggestionService.cards(eq("app"), anyList(), anyMap(), any())).thenReturn(List.of());

        ActionResult result = h.service.execute(new UserAction(
                "clear-1", "clear_filter", "sess-clear", "clear filters",
                new UserAction.ActionPayload("clear_all", null, null, null, null, null, null, null),
                null,
                null), 7L);

        assertThat(result.appliedFilter()).isEqualTo(SearchFilter.empty());
        verify(h.nlpStateStackService).clear("sess-clear");
        verify(h.undoService).clearUndoStack("sess-clear");
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
        NlpStateStackService nlpStateStackService = mock(NlpStateStackService.class);

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
                searchRunService,
                nlpStateStackService);

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

    private static Harness harness() {
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
        NlpStateStackService nlpStateStackService = mock(NlpStateStackService.class);

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
                searchRunService,
                nlpStateStackService);
        return new Harness(service, sessionCache, filterService, conversationManager, undoService,
                suggestionService, sessionContextService, llmSemanticPlanner, semanticActionExecutor,
                semanticExecutionPolicy, searchRunService, nlpStateStackService);
    }

    private record Harness(
            ActionExecutionService service,
            CandidateSessionCache sessionCache,
            CandidateFilterService filterService,
            NlpConversationManager conversationManager,
            NlpUndoService undoService,
            SuggestionService suggestionService,
            SessionContextService sessionContextService,
            LlmSemanticPlanner llmSemanticPlanner,
            SemanticActionExecutor semanticActionExecutor,
            SemanticExecutionPolicy semanticExecutionPolicy,
            SearchRunService searchRunService,
            NlpStateStackService nlpStateStackService
    ) {}
}
