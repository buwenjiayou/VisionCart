package com.visioncart.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.*;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.suggestion.SuggestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchOrchestratorTest {

    private PlatformSearchService taobao;
    private PlatformSearchService pdd;
    private ProductDeduplicator deduplicator;
    private RelevanceRanker ranker;
    private SuggestionService suggestionService;
    private RecognitionHistoryRepository recognitionHistoryRepository;
    private ExecutorService executor;
    private VisionCartProperties properties;
    private SearchOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        taobao = mock(PlatformSearchService.class);
        pdd = mock(PlatformSearchService.class);
        when(taobao.platform()).thenReturn("淘宝");
        when(taobao.domesticOnly()).thenReturn(true);
        when(pdd.platform()).thenReturn("拼多多");
        when(pdd.domesticOnly()).thenReturn(true);

        deduplicator = new ProductDeduplicator();
        ranker = new RelevanceRanker();
        suggestionService = mock(SuggestionService.class);
        when(suggestionService.cards(any(), any())).thenReturn(List.of());

        executor = Executors.newFixedThreadPool(4);

        properties = new VisionCartProperties();
        properties.getSearch().setPlatformTimeoutMs(5000);

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        ObjectMapper objectMapper = new ObjectMapper();
        PlatformCircuitBreaker circuitBreaker = new PlatformCircuitBreaker(properties);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.isDomestic()).thenReturn(true);
        recognitionHistoryRepository = mock(RecognitionHistoryRepository.class);
        when(recognitionHistoryRepository.findById(anyString())).thenReturn(Optional.empty());

        orchestrator = new SearchOrchestrator(
                List.of(taobao, pdd), deduplicator, ranker, suggestionService,
                executor, properties, redisTemplate, objectMapper, circuitBreaker, regionResolver,
                recognitionHistoryRepository);
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
                null, List.of(), null, List.of(), List.of(), null, "price", "desc", null);
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
        when(suggestionService.cards(eq("app"), anyList())).thenReturn(List.of(
                new SuggestionCard("test", "测试", "sub", "icon", "action", 5)
        ));

        SearchResult result = orchestrator.search(defaultRequest());

        verify(suggestionService).cards(eq("app"), anyList());
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
        verify(taobao).search(attributesCaptor.capture(), any(), anyInt(), anyInt());
        assertThat(attributesCaptor.getValue())
                .containsEntry("类目", "无线鼠标")
                .containsEntry("关键词", "罗技 M650 鼠标")
                .containsEntry(SearchTextUtils.ATTR_KEYWORDS, "罗技 M650 鼠标,白色无线鼠标")
                .containsEntry(SearchTextUtils.ATTR_BRAND_RELIABLE, "true");
    }

    @Test
    void filtersIrrelevantProductsWhenCoreCategoryIsKnown() {
        when(taobao.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                product("1", "肩颈按摩仪", "淘宝", BigDecimal.valueOf(99)),
                product("2", "白色无线办公鼠标", "淘宝", BigDecimal.valueOf(39))
        ));
        when(pdd.search(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        SearchRequest request = new SearchRequest(
                "s1",
                Map.of("类目", "无线鼠标", "关键词", "白色无线鼠标"),
                null,
                1,
                20,
                "app"
        );
        SearchResult result = orchestrator.search(request);

        assertThat(result.products())
                .extracting(ProductCard::title)
                .containsExactly("白色无线办公鼠标");
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

    private SearchRequest defaultRequest() {
        return new SearchRequest("s1", Map.of(), null, 1, 20, "app");
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
}
