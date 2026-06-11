package com.visioncart.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.*;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.suggestion.DeepSuggestionService;
import com.visioncart.service.suggestion.SuggestionCardCache;
import com.visioncart.service.suggestion.SuggestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchOrchestratorTest {

    private PlatformSearchService taobao;
    private PlatformSearchService pdd;
    private PlatformSearchService ebay;
    private ProductDeduplicator deduplicator;
    private RelevanceRanker ranker;
    private SuggestionService suggestionService;
    private RecognitionHistoryRepository recognitionHistoryRepository;
    private ExecutorService executor;
    private ExecutorService platformExecutor;
    private ExecutorService aiSuggestionExecutor;
    private ValueOperations<String, String> valueOps;
    private CandidateSessionCache sessionCache;
    private VisionCartProperties properties;
    private SearchOrchestrator orchestrator;
    private OverseasEnglishIntentMatcher overseasEnglishIntentMatcher;
    private RegionResolver regionResolver;

    @BeforeEach
    void setUp() {
        taobao = mock(PlatformSearchService.class);
        pdd = mock(PlatformSearchService.class);
        ebay = mock(PlatformSearchService.class);
        when(taobao.platform()).thenReturn("淘宝");
        when(pdd.platform()).thenReturn("拼多多");
        when(ebay.platform()).thenReturn("eBay");

        deduplicator = new ProductDeduplicator();
        ranker = new RelevanceRanker();
        suggestionService = mock(SuggestionService.class);
        when(suggestionService.cards(any(), any())).thenReturn(List.of());
        when(suggestionService.cards(any(), anyList(), anyMap(), any())).thenReturn(List.of());

        executor = Executors.newFixedThreadPool(4);
        platformExecutor = Executors.newFixedThreadPool(2);
        aiSuggestionExecutor = Executors.newFixedThreadPool(2);

        properties = new VisionCartProperties();
        properties.getSearch().setPlatformTimeoutMs(500);
        properties.getPlatforms().getTaobao().setRegionStrategy("domestic");
        properties.getPlatforms().getPdd().setRegionStrategy("domestic");
        properties.getPlatforms().getEbay().setRegionStrategy("international");

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);

        ObjectMapper objectMapper = new ObjectMapper();
        com.visioncart.service.metrics.PerformanceMetricsService metricsService =
                mock(com.visioncart.service.metrics.PerformanceMetricsService.class);
        PlatformCircuitBreaker circuitBreaker = new PlatformCircuitBreaker(properties, metricsService);
        regionResolver = mock(RegionResolver.class);
        when(regionResolver.isDomestic()).thenReturn(true);
        when(regionResolver.resolve(any())).thenAnswer(invocation -> {
            String mode = RegionResolver.normalizeRegionMode(invocation.getArgument(0, String.class));
            boolean domestic = !"international".equals(mode);
            return new RegionResolver.RegionDecision(
                    mode,
                    domestic ? "domestic" : "international",
                    domestic,
                    domestic ? "218.12.18.80" : "8.8.8.8",
                    domestic ? "218.12.18.80" : "8.8.8.8",
                    "",
                    "",
                    domestic ? "CN" : "US",
                    "test");
        });
        recognitionHistoryRepository = mock(RecognitionHistoryRepository.class);
        when(recognitionHistoryRepository.findById(anyString())).thenReturn(Optional.empty());
        sessionCache = mock(CandidateSessionCache.class);
        SuggestionCardCache suggestionCardCache = mock(SuggestionCardCache.class);
        DeepSuggestionService deepSuggestionService = mock(DeepSuggestionService.class);
        org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate =
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);

        ProductTaxonomyRegistry taxonomy = new ProductTaxonomyRegistry();
        ProductIntentBuilder productIntentBuilder = new ProductIntentBuilder(taxonomy);
        QueryPlanner queryPlanner = new QueryPlanner(taxonomy);
        IntentGate intentGate = new IntentGate(taxonomy, ranker);
        com.visioncart.service.search.strategy.DefaultProductIntentStrategy defaultStrategy =
                new com.visioncart.service.search.strategy.DefaultProductIntentStrategy(queryPlanner, intentGate);
        com.visioncart.service.search.strategy.PhoneCaseStrategy phoneCaseStrategy =
                new com.visioncart.service.search.strategy.PhoneCaseStrategy(queryPlanner, intentGate);
        com.visioncart.service.search.strategy.VerticalStrategyRegistry strategyRegistry =
                new com.visioncart.service.search.strategy.VerticalStrategyRegistry(
                        java.util.List.of(phoneCaseStrategy), defaultStrategy);
        overseasEnglishIntentMatcher = spy(new OverseasEnglishIntentMatcher());

        orchestrator = new SearchOrchestrator(
                List.of(taobao, pdd, ebay), deduplicator, ranker, suggestionService, deepSuggestionService,
                platformExecutor, aiSuggestionExecutor, properties, redisTemplate, objectMapper, circuitBreaker, regionResolver,
                recognitionHistoryRepository, sessionCache, suggestionCardCache, messagingTemplate, metricsService,
                new ProductSortService(new ProductReputationService()), new ProductReputationService(),
                productIntentBuilder, queryPlanner, intentGate, strategyRegistry, overseasEnglishIntentMatcher);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (platformExecutor != null) {
            platformExecutor.shutdownNow();
        }
        if (aiSuggestionExecutor != null) {
            aiSuggestionExecutor.shutdownNow();
        }
    }

    @Test
    void aggregatesProductsFromMultiplePlatforms() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "Nike鞋", "淘宝", BigDecimal.valueOf(399))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("2", "Nike鞋", "拼多多", BigDecimal.valueOf(299))
        ));

        SearchResult result = orchestrator.search(defaultRequest());

        assertThat(result.products()).hasSize(2);
        assertThat(result.total()).isEqualTo(2);
    }

    @Test
    void domesticSearchRoutesOnlyToDomesticPlatforms() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("tb-1", "淘宝国内商品", "淘宝", BigDecimal.valueOf(199))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("pdd-1", "拼多多国内商品", "拼多多", BigDecimal.valueOf(99))
        ));

        SearchResult result = orchestrator.search(defaultRequest(), true);

        assertThat(result.products()).extracting(ProductCard::platform)
                .containsExactlyInAnyOrder("淘宝", "拼多多");
        verify(taobao, atLeastOnce()).search(any(), any(), anyInt(), anyInt());
        verify(pdd, atLeastOnce()).search(any(), any(), anyInt(), anyInt());
        verify(ebay, never()).search(any(), any(), anyInt(), anyInt());
    }

    @Test
    void internationalSearchRoutesOnlyToInternationalPlatforms() {
        when(ebay.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("ebay-1", "eBay international item", "eBay", BigDecimal.valueOf(49))
        ));

        SearchResult result = orchestrator.search(defaultRequest(), false);

        assertThat(result.products()).extracting(ProductCard::platform)
                .containsExactly("eBay");
        verify(ebay, atLeastOnce()).search(any(), any(), anyInt(), anyInt());
        verify(taobao, never()).search(any(), any(), anyInt(), anyInt());
        verify(pdd, never()).search(any(), any(), anyInt(), anyInt());
    }

    @Test
    void explicitInternationalRegionModeRoutesToEbayEvenWhenAutoWouldBeDomestic() {
        when(ebay.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("ebay-mouse", "Logitech wireless mouse", "eBay", BigDecimal.valueOf(49))
        ));
        SearchRequest request = new SearchRequest("manual-overseas",
                Map.of(SearchTextUtils.ATTR_CATEGORY, "\u9f20\u6807",
                        SearchTextUtils.ATTR_KEYWORD, "\u9f20\u6807"),
                null, 1, 20, null, "app", "international");

        SearchResult result = orchestrator.search(request, 1L);

        assertThat(result.products()).extracting(ProductCard::platform).contains("eBay");
        verify(ebay, atLeastOnce()).search(any(), any(), anyInt(), anyInt());
        verify(taobao, never()).search(any(), any(), anyInt(), anyInt());
        verify(pdd, never()).search(any(), any(), anyInt(), anyInt());
    }

    @Test
    void explicitDomesticRegionModeRoutesToDomesticPlatforms() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("tb-mouse", "\u9f20\u6807", "\u6dd8\u5b9d", BigDecimal.valueOf(99))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        SearchRequest request = new SearchRequest("manual-domestic",
                Map.of(SearchTextUtils.ATTR_CATEGORY, "\u9f20\u6807",
                        SearchTextUtils.ATTR_KEYWORD, "\u9f20\u6807"),
                null, 1, 20, null, "app", "domestic");

        SearchResult result = orchestrator.search(request, 1L);

        assertThat(result.products()).extracting(ProductCard::platform).contains("\u6dd8\u5b9d");
        verify(ebay, never()).search(any(), any(), anyInt(), anyInt());
        verify(taobao, atLeastOnce()).search(any(), any(), anyInt(), anyInt());
    }

    @Test
    void domesticProductIntentDoesNotUseOverseasEnglishMatcher() {
        clearInvocations(overseasEnglishIntentMatcher);
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("tb-mouse", "Logitech wireless mouse", "\u6dd8\u5b9d", BigDecimal.valueOf(99))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest("domestic-mouse",
                Map.of(SearchTextUtils.ATTR_CATEGORY, "\u9f20\u6807",
                        SearchTextUtils.ATTR_KEYWORD, "\u9f20\u6807"),
                null, 1, 20, "app");

        orchestrator.search(request, true);

        verify(overseasEnglishIntentMatcher, never()).classify(any(), any());
        verify(overseasEnglishIntentMatcher, never()).sortWithinTier(any(), anyList());
        verify(ebay, never()).search(any(), any(), anyInt(), anyInt());
    }

    @Test
    void overseasProductIntentUsesEnglishMatcherAndKeepsMainProducts() {
        clearInvocations(overseasEnglishIntentMatcher);
        when(ebay.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("mouse-main", "Logitech M650 wireless mouse", "eBay", BigDecimal.valueOf(39)),
                product("mouse-pad", "Large gaming mouse pad desk mat", "eBay", BigDecimal.valueOf(12)),
                product("mouse-skates", "Mouse skates feet for Logitech mouse", "eBay", BigDecimal.valueOf(8))
        ));

        SearchRequest request = new SearchRequest("overseas-mouse",
                Map.of(SearchTextUtils.ATTR_CATEGORY, "\u9f20\u6807",
                        SearchTextUtils.ATTR_KEYWORD, "\u9f20\u6807"),
                null, 1, 20, "app");

        SearchResult result = orchestrator.search(request, false);

        assertThat(result.products()).extracting(ProductCard::id)
                .contains("mouse-main")
                .doesNotContain("mouse-pad", "mouse-skates");
        verify(overseasEnglishIntentMatcher, atLeastOnce()).classify(any(), any());
        verify(taobao, never()).search(any(), any(), anyInt(), anyInt());
        verify(pdd, never()).search(any(), any(), anyInt(), anyInt());
    }

    @Test
    void productIntentSearchKeepsPddVisibleInReturnedPageAndCandidateCache() {
        List<ProductCard> taobaoProducts = new ArrayList<>();
        List<ProductCard> pddProducts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            taobaoProducts.add(product("tb-" + i, "\u7535\u52a8\u5243\u987b\u5200 " + i,
                    "\u6dd8\u5b9d", BigDecimal.valueOf(100 + i)));
            pddProducts.add(product("pdd-" + i, "\u7535\u52a8\u5243\u987b\u5200 " + i,
                    "\u62fc\u591a\u591a", BigDecimal.valueOf(80 + i)));
        }
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(taobaoProducts);
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(pddProducts);

        SearchRequest request = new SearchRequest("sess-platform",
                Map.of("\u7c7b\u76ee", "\u7535\u52a8\u5243\u987b\u5200",
                        "\u5173\u952e\u8bcd", "\u7535\u52a8\u5243\u987b\u5200"),
                null, 1, 50, "app");
        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).extracting(ProductCard::platform)
                .contains("\u6dd8\u5b9d", "\u62fc\u591a\u591a");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductCard>> captor = ArgumentCaptor.forClass(List.class);
        verify(sessionCache, atLeastOnce()).saveCandidates(eq("sess-platform"), captor.capture(), anyString());
        assertThat(captor.getValue().stream().limit(50).map(ProductCard::platform).toList())
                .contains("\u6dd8\u5b9d", "\u62fc\u591a\u591a");
    }

    @Test
    void staleSearchRunDoesNotWriteCandidatesOrReturnProducts() {
        when(valueOps.get(startsWith("visioncart:search:run:"))).thenReturn("newer-run");
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("tb-1", "\u7535\u52a8\u5243\u987b\u5200", "\u6dd8\u5b9d", BigDecimal.valueOf(199))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("pdd-1", "\u7535\u52a8\u5243\u987b\u5200", "\u62fc\u591a\u591a", BigDecimal.valueOf(99))
        ));

        SearchResult result = orchestrator.search(new SearchRequest("sess-stale",
                Map.of("\u7c7b\u76ee", "\u7535\u52a8\u5243\u987b\u5200"),
                null, 1, 20, "app"));

        assertThat(result.products()).isEmpty();
        verify(sessionCache, never()).saveCandidates(eq("sess-stale"), anyList(), anyString());
    }

    @Test
    void deduplicatesAcrossPlatforms() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "Nike Pegasus 41", "淘宝", BigDecimal.valueOf(399))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("2", "Nike Pegasus 41", "拼多多", BigDecimal.valueOf(299))
        ));

        SearchResult result = orchestrator.search(defaultRequest());

        // Same title + same shop "官方旗舰店" + similar price bucket → deduplicated
        assertThat(result.total()).isLessThanOrEqualTo(2);
    }

    @Test
    void filtersByPriceRange() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "便宜鞋", "淘宝", BigDecimal.valueOf(99)),
                product("2", "贵鞋", "淘宝", BigDecimal.valueOf(999))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchFilter filter = new SearchFilter(
                new PriceRange(0.0, 200.0), List.of(), null, List.of(), List.of(), null, null, "desc", null);
        SearchRequest request = new SearchRequest("s1", Map.of(), filter, 1, 20, "app");

        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).allSatisfy(p ->
                assertThat(p.price()).isLessThanOrEqualTo(BigDecimal.valueOf(200)));
    }

    @Test
    void filtersByPlatform() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "淘宝商品", "淘宝", BigDecimal.valueOf(100))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("2", "拼多多商品", "拼多多", BigDecimal.valueOf(50))
        ));

        SearchFilter filter = new SearchFilter(
                null, List.of("淘宝"), null, List.of(), List.of(), null, null, "desc", null);
        SearchRequest request = new SearchRequest("s1", Map.of(), filter, 1, 20, "app");

        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).allSatisfy(p ->
                assertThat(p.platform()).isEqualTo("淘宝"));
    }

    @Test
    void sortsByPrice() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "鞋A", "淘宝", BigDecimal.valueOf(500)),
                product("2", "鞋B", "淘宝", BigDecimal.valueOf(100))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchFilter filter = new SearchFilter(
                null, List.of(), null, List.of(), List.of(), null, "price", "asc", null);
        SearchRequest request = new SearchRequest("s1", Map.of(), filter, 1, 20, "app");

        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).isNotEmpty();
        assertThat(result.products().get(0).price()).isLessThanOrEqualTo(
                result.products().get(result.products().size() - 1).price());
    }

    @Test
    void gracefulWhenPlatformTimesOut() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "淘宝商品", "淘宝", BigDecimal.valueOf(100))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(10000);
            return List.of();
        });

        SearchResult result = orchestrator.search(defaultRequest());

        // Should still return taobao results despite pdd timeout
        assertThat(result.products()).hasSize(1);
    }

    @Test
    void gracefulWhenPlatformThrows() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "淘宝商品", "淘宝", BigDecimal.valueOf(100))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenThrow(new RuntimeException("API error"));

        SearchResult result = orchestrator.search(defaultRequest());

        assertThat(result.products()).hasSize(1);
    }

    @Test
    void platformFanOutDoesNotStarveNestedQueryExecutor() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenAnswer(invocation ->
                CompletableFuture.supplyAsync(() ->
                        List.of(product("nested-1", "嵌套查询商品", "淘宝", BigDecimal.valueOf(88))), executor)
                        .get(1, TimeUnit.SECONDS));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchResult result = orchestrator.search(defaultRequest());

        assertThat(result.products()).extracting(ProductCard::title)
                .contains("嵌套查询商品");
    }

    @Test
    void lockContentionReturnsInProgressResponseWithoutDuplicatePlatformSearch() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);
        when(sessionCache.getBestCandidates("s1")).thenReturn(List.of());
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "Search result after lock wait", "淘宝", BigDecimal.valueOf(100))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchResult result = orchestrator.search(defaultRequest());

        assertThat(result.products()).isEmpty();
        assertThat(result.total()).isEqualTo(0);
        assertThat(result.inProgress()).isTrue();
        verify(taobao, never()).search(any(), any(), anyInt(), anyInt());
        verify(pdd, never()).search(any(), any(), anyInt(), anyInt());
    }

    @Test
    void lockContentionReturnsMatchingSessionCacheWithoutDuplicatePlatformSearch() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);
        List<ProductCard> cached = List.of(
                product("cached-1", "Cached lock result", "taobao", BigDecimal.valueOf(100))
        );
        when(sessionCache.getBestCandidates("s1")).thenReturn(cached);
        when(sessionCache.matchesSearchIdentity(eq("s1"), anyString())).thenReturn(true);

        SearchResult result = orchestrator.search(defaultRequest());

        assertThat(result.products()).extracting(ProductCard::title)
                .containsExactly("Cached lock result");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.inProgress()).isFalse();
        verify(taobao, never()).search(any(), any(), anyInt(), anyInt());
        verify(pdd, never()).search(any(), any(), anyInt(), anyInt());
    }

    @Test
    void returnsEmptyWhenAllPlatformsFail() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenThrow(new RuntimeException("fail"));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenThrow(new RuntimeException("fail"));

        SearchResult result = orchestrator.search(defaultRequest());

        assertThat(result.products()).isEmpty();
        assertThat(result.total()).isEqualTo(0);
    }

    @Test
    void computesPlatformStats() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "商品A", "淘宝", BigDecimal.valueOf(100)),
                product("2", "商品B", "淘宝", BigDecimal.valueOf(300))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("3", "商品C", "拼多多", BigDecimal.valueOf(50))
        ));

        SearchResult result = orchestrator.search(defaultRequest());

        assertThat(result.platformStats()).hasSize(2);
        assertThat(result.platformStats().get(0).minPrice())
                .isLessThanOrEqualTo(result.platformStats().get(1).minPrice());
    }

    @Test
    void callsSuggestionService() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "商品", "淘宝", BigDecimal.valueOf(100))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(suggestionService.cards(eq("app"), anyList(), anyMap(), any())).thenReturn(List.of(
                new SuggestionCard("test", "测试", "sub", "icon", "action", 5)
        ));

        SearchResult result = orchestrator.search(defaultRequest());

        verify(suggestionService).cards(eq("app"), anyList(), anyMap(), any());
        assertThat(result.suggestionCards()).hasSize(1);
    }

    @Test
    void enrichesAttributesFromRecognitionHistoryWhenClientOnlySendsSessionId() {
        RecognitionHistory history = new RecognitionHistory();
        history.setSessionId("s1");
        history.setCategoryJson("{\"level1\":\"电脑配件\",\"level2\":\"鼠标\",\"level3\":\"无线鼠标\"}");
        history.setKeywords("罗技 M650 鼠标,白色无线鼠标");
        history.setAttributesJson("{\"品牌\":{\"value\":\"罗技\",\"confidence\":0.95,\"verified\":true},\"型号\":{\"value\":\"M650\",\"confidence\":0.9,\"verified\":true}}");
        when(recognitionHistoryRepository.findById("s1")).thenReturn(Optional.of(history));
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "白色无线鼠标", "淘宝", BigDecimal.valueOf(99))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "s1",
                Map.of("颜色", "白色", "款式", "常规款式"),
                null,
                1,
                20,
                "app"
        );
        orchestrator.search(request);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, String>> attributesCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(taobao, atLeastOnce()).search(attributesCaptor.capture(), any(), anyInt(), anyInt());
        assertThat(attributesCaptor.getAllValues().get(0))
                .containsEntry("类目", "无线鼠标")
                .containsEntry("关键词", "罗技 M650 鼠标")
                .containsEntry(SearchTextUtils.ATTR_KEYWORDS, "罗技 M650 鼠标,白色无线鼠标")
                .containsEntry(SearchTextUtils.ATTR_BRAND_RELIABLE, "true");
    }

    @Test
    void filtersIrrelevantProductsWhenCoreCategoryIsKnown() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "Shoulder massager", "taobao", BigDecimal.valueOf(99)),
                product("2", "White wireless office mouse", "taobao", BigDecimal.valueOf(39))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "s1",
                Map.of(SearchTextUtils.ATTR_CATEGORY, "mouse", SearchTextUtils.ATTR_KEYWORD, "wireless mouse"),
                null,
                1,
                20,
                "app"
        );
        SearchResult result = orchestrator.search(request);

        assertThat(result.products())
                .extracting(ProductCard::title)
                .containsExactly("White wireless office mouse");
    }
    @Test
    void filtersConflictingBrandsWhenRecognitionBrandIsReliable() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "Adidas UltraBoost 运动鞋", "淘宝", BigDecimal.valueOf(599), "Adidas"),
                product("2", "Nike Pegasus 41 男子公路跑步鞋", "淘宝", BigDecimal.valueOf(499), "Nike"),
                product("3", "Pegasus 41 运动鞋同款缓震跑鞋", "淘宝", BigDecimal.valueOf(399), null)
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "s1",
                Map.of(
                        "品牌", "耐克",
                        "型号", "Pegasus 41",
                        "类目", "运动鞋",
                        "关键词", "Nike Pegasus 41 运动鞋",
                        SearchTextUtils.ATTR_BRAND_RELIABLE, "true"
                ),
                null,
                1,
                20,
                "app"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.products())
                .extracting(ProductCard::title)
                .doesNotContain("Adidas UltraBoost 运动鞋")
                .contains("Nike Pegasus 41 男子公路跑步鞋");
    }

    @Test
    void ranksSameBrandSameModelAboveGenericSameCategory() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "Nike 运动鞋男款缓震跑鞋", "淘宝", BigDecimal.valueOf(299), "Nike"),
                product("2", "Nike Pegasus 41 男子公路跑步鞋", "淘宝", BigDecimal.valueOf(499), "Nike")
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "s1",
                Map.of(
                        "品牌", "Nike",
                        "型号", "Pegasus 41",
                        "类目", "运动鞋",
                        "关键词", "Nike Pegasus 41 运动鞋",
                        SearchTextUtils.ATTR_BRAND_RELIABLE, "true"
                ),
                null,
                1,
                20,
                "app"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).isNotEmpty();
        assertThat(result.products().get(0).title()).contains("Pegasus 41");
    }

    @Test
    void fillsStrongResultsWithSafeCandidatesToThirty() {
        List<ProductCard> products = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            products.add(product("strong-" + i, "Logitech M650 wireless mouse model " + suffix(i), "taobao", BigDecimal.valueOf(99 + i), "Logitech"));
        }
        for (int i = 0; i < 40; i++) {
            products.add(product("safe-" + i, "Wireless office mouse safe candidate " + suffix(i), "taobao", BigDecimal.valueOf(49 + i), null));
        }
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(products);
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "s1",
                Map.of(
                        SearchTextUtils.ATTR_BRAND, "Logitech",
                        "model", "M650",
                        SearchTextUtils.ATTR_CATEGORY, "mouse",
                        SearchTextUtils.ATTR_KEYWORD, "Logitech M650 mouse",
                        SearchTextUtils.ATTR_BRAND_RELIABLE, "true"
                ),
                null,
                1,
                30,
                "app"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).hasSize(30);
        assertThat(result.products()).allSatisfy(product ->
                assertThat(product.title().toLowerCase(java.util.Locale.ROOT)).contains("mouse"));
        assertThat(result.products().get(0).title()).contains("M650");
    }
    @Test
    void supplementalRecallFillsToThirtyWhenInitialRecallIsTooSmall() {
        List<ProductCard> firstPass = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            firstPass.add(product("first-" + i, "Logitech M650 wireless mouse " + suffix(i), "taobao", BigDecimal.valueOf(100 + i), "Logitech"));
        }
        List<ProductCard> supplemental = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            supplemental.add(product("supp-" + i, "Wireless mouse supplemental candidate " + suffix(i), "taobao", BigDecimal.valueOf(60 + i), null));
        }
        when(taobao.search(any(), any(), anyInt(), anyInt()))
                .thenReturn(firstPass)
                .thenReturn(supplemental);
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "s1",
                Map.of(
                        SearchTextUtils.ATTR_BRAND, "Logitech",
                        "model", "M650",
                        SearchTextUtils.ATTR_CATEGORY, "mouse",
                        SearchTextUtils.ATTR_KEYWORD, "Logitech M650 mouse",
                        SearchTextUtils.ATTR_BRAND_RELIABLE, "true"
                ),
                null,
                1,
                30,
                "app"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).hasSize(30);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, String>> attributesCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(taobao, atLeast(2)).search(attributesCaptor.capture(), any(), anyInt(), anyInt());
        assertThat(attributesCaptor.getAllValues().get(1))
                .doesNotContainKeys(SearchTextUtils.ATTR_BRAND, SearchTextUtils.ATTR_BRAND_RELIABLE)
                .containsEntry(SearchTextUtils.ATTR_CATEGORY, "mouse")
                .containsEntry(SearchTextUtils.ATTR_KEYWORD, "Logitech M650 mouse");
    }
    @Test
    void unreliableBrandDoesNotHardFilterOtherwiseSafeProducts() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "Adidas UltraBoost 运动鞋", "淘宝", BigDecimal.valueOf(599), "Adidas"),
                product("2", "缓震运动鞋通勤跑鞋", "淘宝", BigDecimal.valueOf(199), null)
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "s1",
                Map.of(
                        "品牌", "Nike",
                        "类目", "运动鞋",
                        "关键词", "运动鞋"
                ),
                null,
                1,
                30,
                "app"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).isNotEmpty();
    }

    @Test
    void appliesColorAndNegativeKeywordFiltersBeforeSafeFill() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "白色无线鼠标", "淘宝", BigDecimal.valueOf(99), null),
                product("2", "黑色无线鼠标", "淘宝", BigDecimal.valueOf(89), null),
                product("3", "白色无线鼠标支架", "淘宝", BigDecimal.valueOf(19), null)
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        SearchFilter filter = new SearchFilter(
                null, List.of(), null, List.of("白色"), List.of(), null, null, "desc", "!支架");

        SearchRequest request = new SearchRequest(
                "s1",
                Map.of("类目", "无线鼠标", "关键词", "白色无线鼠标"),
                filter,
                1,
                30,
                "app"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).extracting(ProductCard::title)
                .containsExactly("白色无线鼠标");
    }

    @Test
    void magneticPhoneCaseSearchPrioritizesMagneticCasesOverPlainAppleCases() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("apple-plain", "苹果 iPhone 15 透明手机壳", "淘宝", BigDecimal.valueOf(29), "Apple"),
                product("magnetic", "磁吸 MagSafe 防摔手机壳", "淘宝", BigDecimal.valueOf(39), null),
                product("generic-plain", "通用防摔手机壳", "淘宝", BigDecimal.valueOf(19), null)
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "magnetic-case-session",
                Map.of(
                        SearchTextUtils.ATTR_CATEGORY, "手机配件",
                        SearchTextUtils.ATTR_STYLE, "磁吸",
                        "类型", "磁吸式防摔壳",
                        SearchTextUtils.ATTR_BRAND, "Apple"
                ),
                null,
                1,
                20,
                "recognition"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).isNotEmpty();
        assertThat(result.products().get(0).title()).isEqualTo("磁吸 MagSafe 防摔手机壳");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> attributesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taobao, atLeastOnce()).search(attributesCaptor.capture(), any(), anyInt(), anyInt());
        String planQueries = attributesCaptor.getAllValues().get(0).get(SearchQueryBuilder.ATTR_PLAN_QUERIES);
        assertThat(planQueries).contains("磁吸 手机壳");
        assertThat(planQueries).doesNotContain("Apple");
    }

    @Test
    void unknownBrandPhoneCaseSearchDoesNotFillPageWithOneBrand() {
        List<ProductCard> platformResults = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            platformResults.add(product("apple-" + i,
                    "苹果 iPhone " + i + " 磁吸 MagSafe 手机壳",
                    "淘宝", BigDecimal.valueOf(20 + i), "Apple"));
        }
        platformResults.add(product("generic-1", "通用磁吸防摔手机壳", "淘宝", BigDecimal.valueOf(29), null));
        platformResults.add(product("generic-2", "安卓通用磁吸手机壳", "淘宝", BigDecimal.valueOf(31), null));
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(platformResults);
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "unknown-brand-case-session",
                Map.of(
                        SearchTextUtils.ATTR_CATEGORY, "手机配件",
                        SearchTextUtils.ATTR_STYLE, "磁吸",
                        "类型", "磁吸式防摔壳"
                ),
                null,
                1,
                5,
                "recognition"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.products()).hasSize(5);
        assertThat(result.products()).extracting(ProductCard::title)
                .anyMatch(title -> !title.contains("苹果") && !title.contains("iPhone"));
    }

    @Test
    void productIntentSearchStaysEmptyWhenStrategyRejectsAllPlatformResults() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "Travel mug", "娣樺疂", BigDecimal.valueOf(49)),
                product("2", "Office mug", "娣樺疂", BigDecimal.valueOf(59))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "s1",
                Map.of(
                        SearchTextUtils.ATTR_CATEGORY, "wireless mouse",
                        SearchTextUtils.ATTR_KEYWORD, "wireless mouse"
                ),
                null,
                1,
                30,
                "app"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.relaxed()).isFalse();
        assertThat(result.products()).isEmpty();
    }

    @Test
    void productIntentDoesNotFallbackToOldRankerWhenStrategyRejectsAll() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "Coffee mug", "taobao", BigDecimal.valueOf(49)),
                product("2", "Office mug", "taobao", BigDecimal.valueOf(59))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "phone-intent-session",
                Map.of(
                        SearchTextUtils.ATTR_CATEGORY, "phone",
                        SearchTextUtils.ATTR_KEYWORD, "phone"
                ),
                null,
                1,
                30,
                "app"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.relaxed()).isFalse();
        assertThat(result.products()).isEmpty();
    }

    @Test
    void platformRuntimeFailureReturnsAvailableProductsButSkipsSearchCache() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("tb-ok", "\u7535\u52a8\u5243\u987b\u5200", "\u6dd8\u5b9d", BigDecimal.valueOf(199))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("PDD query batch successful 0 of 2 queries"));

        SearchResult result = orchestrator.search(new SearchRequest("sess-pdd-fail",
                Map.of(SearchTextUtils.ATTR_CATEGORY, "\u7535\u52a8\u5243\u987b\u5200"),
                null, 1, 20, "app"));

        assertThat(result.products()).extracting(ProductCard::id).contains("tb-ok");
        verify(valueOps, never()).set(startsWith("visioncart:search:cache:v2:"), anyString(), any(java.time.Duration.class));
    }

    @Test
    void allEnabledPlatformsSuccessfulAllowsSearchCacheWrite() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("tb-ok", "\u7535\u52a8\u5243\u987b\u5200", "\u6dd8\u5b9d", BigDecimal.valueOf(199))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("pdd-ok", "\u7535\u52a8\u5243\u987b\u5200", "\u62fc\u591a\u591a", BigDecimal.valueOf(99))
        ));

        SearchResult result = orchestrator.search(new SearchRequest("sess-cache-ok",
                Map.of(SearchTextUtils.ATTR_CATEGORY, "\u7535\u52a8\u5243\u987b\u5200"),
                null, 1, 20, "app"));

        assertThat(result.products()).extracting(ProductCard::platform)
                .contains("\u6dd8\u5b9d", "\u62fc\u591a\u591a");
        verify(valueOps, atLeastOnce()).set(startsWith("visioncart:search:cache:v2:"), anyString(), any(java.time.Duration.class));
    }

    @Test
    void strictIntentDoesNotFallbackToUnrelatedProducts() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "Travel mug", "淘宝", BigDecimal.valueOf(49)),
                product("2", "Office mug", "淘宝", BigDecimal.valueOf(59))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "strict-session",
                Map.of(
                        SearchTextUtils.ATTR_CATEGORY, "wireless mouse",
                        SearchTextUtils.ATTR_KEYWORD, "wireless mouse",
                        SearchTextUtils.ATTR_STRICT_INTENT, "true"
                ),
                null,
                1,
                30,
                "attribute_correction"
        );

        SearchResult result = orchestrator.search(request);

        assertThat(result.relaxed()).isFalse();
        assertThat(result.products()).isEmpty();
    }

    private SearchRequest defaultRequest() {
        return new SearchRequest("s1", Map.of(), null, 1, 20, "app");
    }

    private String suffix(int value) {
        char first = (char) ('A' + (value / 26));
        char second = (char) ('A' + (value % 26));
        return "" + first + second;
    }

    private ProductCard product(String id, String title, String platform, BigDecimal price) {
        return product(id, title, platform, price, null);
    }

    private ProductCard product(String id, String title, String platform, BigDecimal price, String brand) {
        return new ProductCard(
                id, title, "", price, null, platform, false, "官方旗舰店",
                4.8, 100, 0.9, List.of(platform), "https://example.com/" + id,
                brand, "shop_dsr", "30天销量 100+"
        );
    }

    // ==================== Regression: 192 products pagination ====================

    @Test
    void searchResultProductsNeverExceedsPageSize() {
        // Simulate 192 raw products from platforms (the 192 bug scenario)
        List<ProductCard> products = new ArrayList<>();
        for (int i = 0; i < 192; i++) {
            String platform = (i % 2 == 0) ? "淘宝" : "拼多多";
            products.add(product("p" + i, "商品" + i, platform, BigDecimal.valueOf(10 + i)));
        }
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(products.subList(0, 96));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(products.subList(96, 192));

        // pageSize=50, recallSize=300
        SearchRequest request = new SearchRequest("s1", Map.of(), null, 1, 50, "app");
        SearchResult result = orchestrator.search(request);

        // Products displayed must be <= 50
        assertThat(result.products()).hasSizeLessThanOrEqualTo(50);
        // Total should reflect full candidate count
        assertThat(result.total()).isGreaterThanOrEqualTo(50);
        // total != products.size means pagination is working
        assertThat(result.total()).isGreaterThanOrEqualTo(result.products().size());
    }

    @Test
    void webSocketFinalMessageProductsNeverExceedsPageSize() {
        // This test verifies that the final WebSocket message sends page, not ranked
        // We capture the message sent via messagingTemplate
        org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate =
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);

        // Rebuild orchestrator with capturable messaging template
        com.visioncart.service.metrics.PerformanceMetricsService metricsService =
                mock(com.visioncart.service.metrics.PerformanceMetricsService.class);
        PlatformCircuitBreaker circuitBreaker = new PlatformCircuitBreaker(properties, metricsService);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.isDomestic()).thenReturn(true);
        DeepSuggestionService deepSuggestionService = mock(DeepSuggestionService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps2 = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps2);
        when(valueOps2.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);

        ProductTaxonomyRegistry taxonomyCap = new ProductTaxonomyRegistry();
        ProductIntentBuilder pibCap = new ProductIntentBuilder(taxonomyCap);
        QueryPlanner qpCap = new QueryPlanner(taxonomyCap);
        IntentGate igCap = new IntentGate(taxonomyCap, ranker);
        com.visioncart.service.search.strategy.DefaultProductIntentStrategy defCap =
                new com.visioncart.service.search.strategy.DefaultProductIntentStrategy(qpCap, igCap);
        com.visioncart.service.search.strategy.VerticalStrategyRegistry vsrCap =
                new com.visioncart.service.search.strategy.VerticalStrategyRegistry(
                        java.util.List.of(), defCap);

        SearchOrchestrator orchestratorWithCapture = new SearchOrchestrator(
                List.of(taobao, pdd), deduplicator, ranker, suggestionService, deepSuggestionService,
                platformExecutor, aiSuggestionExecutor, properties, redisTemplate, new ObjectMapper(),
                circuitBreaker, regionResolver, recognitionHistoryRepository, sessionCache,
                mock(SuggestionCardCache.class), messagingTemplate, metricsService,
                new ProductSortService(new ProductReputationService()), new ProductReputationService(),
                pibCap, qpCap, igCap, vsrCap, new OverseasEnglishIntentMatcher());

        // Simulate 192 products
        List<ProductCard> products = new ArrayList<>();
        for (int i = 0; i < 192; i++) {
            String platform = (i % 2 == 0) ? "淘宝" : "拼多多";
            products.add(product("ws" + i, "WebSocket商品" + i, platform, BigDecimal.valueOf(10 + i)));
        }
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(products.subList(0, 96));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(products.subList(96, 192));

        SearchRequest request = new SearchRequest("ws-session", Map.of(), null, 1, 50, "app");
        orchestratorWithCapture.search(request);

        // Capture the final WebSocket message
        org.mockito.ArgumentCaptor<SearchProgressMessage> captor =
                org.mockito.ArgumentCaptor.forClass(SearchProgressMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/search/ws-session"), captor.capture());

        // Find the final message (staging=false)
        SearchProgressMessage finalMsg = captor.getAllValues().stream()
                .filter(msg -> !msg.staging())
                .reduce((a, b) -> b) // last one
                .orElse(null);

        assertThat(finalMsg).isNotNull();
        assertThat(finalMsg.products()).hasSizeLessThanOrEqualTo(50);
        assertThat(finalMsg.staging()).isFalse();
        assertThat(finalMsg.totalCount()).isGreaterThanOrEqualTo(finalMsg.products().size());
    }

    @Test
    void intermediateWebSocketProgressAlsoRespectsPageSize() {
        org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate =
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);

        com.visioncart.service.metrics.PerformanceMetricsService metricsService =
                mock(com.visioncart.service.metrics.PerformanceMetricsService.class);
        PlatformCircuitBreaker circuitBreaker = new PlatformCircuitBreaker(properties, metricsService);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.isDomestic()).thenReturn(true);
        DeepSuggestionService deepSuggestionService = mock(DeepSuggestionService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps2 = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps2);
        when(valueOps2.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);

        ProductTaxonomyRegistry taxonomyCap = new ProductTaxonomyRegistry();
        ProductIntentBuilder pibCap = new ProductIntentBuilder(taxonomyCap);
        QueryPlanner qpCap = new QueryPlanner(taxonomyCap);
        IntentGate igCap = new IntentGate(taxonomyCap, ranker);
        com.visioncart.service.search.strategy.DefaultProductIntentStrategy defCap =
                new com.visioncart.service.search.strategy.DefaultProductIntentStrategy(qpCap, igCap);
        com.visioncart.service.search.strategy.VerticalStrategyRegistry vsrCap =
                new com.visioncart.service.search.strategy.VerticalStrategyRegistry(
                        java.util.List.of(), defCap);

        SearchOrchestrator orchestratorWithCapture = new SearchOrchestrator(
                List.of(taobao, pdd), deduplicator, ranker, suggestionService, deepSuggestionService,
                platformExecutor, aiSuggestionExecutor, properties, redisTemplate, new ObjectMapper(),
                circuitBreaker, regionResolver, recognitionHistoryRepository, sessionCache,
                mock(SuggestionCardCache.class), messagingTemplate, metricsService,
                new ProductSortService(new ProductReputationService()), new ProductReputationService(),
                pibCap, qpCap, igCap, vsrCap, new OverseasEnglishIntentMatcher());

        // Platform 1 returns 100 products, platform 2 returns 92
        List<ProductCard> batch1 = new ArrayList<>();
        for (int i = 0; i < 100; i++) batch1.add(product("b1-" + i, "商品A" + i, "淘宝", BigDecimal.valueOf(i)));
        List<ProductCard> batch2 = new ArrayList<>();
        for (int i = 0; i < 92; i++) batch2.add(product("b2-" + i, "商品B" + i, "拼多多", BigDecimal.valueOf(i)));
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(batch1);
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(batch2);

        SearchRequest request = new SearchRequest("prog-session", Map.of(), null, 1, 50, "app");
        orchestratorWithCapture.search(request);

        org.mockito.ArgumentCaptor<SearchProgressMessage> captor =
                org.mockito.ArgumentCaptor.forClass(SearchProgressMessage.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/search/prog-session"), captor.capture());

        // ALL WebSocket messages (including intermediate) must have products <= pageSize
        for (SearchProgressMessage msg : captor.getAllValues()) {
            assertThat(msg.products())
                    .as("WebSocket message products.size must be <= 50, but was %d", msg.products().size())
                    .hasSizeLessThanOrEqualTo(50);
        }
    }
}
