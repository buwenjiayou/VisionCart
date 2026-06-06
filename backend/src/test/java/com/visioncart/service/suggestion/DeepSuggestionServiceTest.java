package com.visioncart.service.suggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.service.ai.AiTraceService;
import com.visioncart.service.ai.PromptLoader;
import com.visioncart.service.metrics.PerformanceMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeepSuggestionServiceTest {
    private DeepSuggestionService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // No ChatClient — AI will always be unavailable, testing rule-based path
        ObjectProvider<com.visioncart.service.ai.PromptLoader> promptLoaderProvider = mock(ObjectProvider.class);
        AiTraceService traceService = mock(AiTraceService.class);
        PromptLoader promptLoader = mock(PromptLoader.class);
        PerformanceMetricsService metricsService = mock(PerformanceMetricsService.class);
        // Mock timer methods to avoid NPE
        io.micrometer.core.instrument.Timer.Sample mockSample = mock(io.micrometer.core.instrument.Timer.Sample.class);
        org.mockito.Mockito.when(metricsService.startDeepSuggestionTimer()).thenReturn(mockSample);

        service = new DeepSuggestionService(
                mock(ObjectProvider.class),
                objectMapper,
                traceService,
                promptLoader,
                metricsService
        );
    }

    @Test
    void generatesPriceBandFactWhenEnoughPricedProducts() {
        List<ProductCard> products = List.of(
                product("1", "商品A", "淘宝", 80),
                product("2", "商品B", "淘宝", 90),
                product("3", "商品C", "淘宝", 100),
                product("4", "商品D", "淘宝", 110),
                product("5", "商品E", "淘宝", 120),
                product("6", "商品F", "淘宝", 130)
        );

        var facts = service.generateFacts(products, Map.of(), SearchFilter.empty());

        assertThat(facts).anyMatch(f -> f.id().equals("insight_price_band"));
        var fact = facts.stream().filter(f -> f.id().equals("insight_price_band")).findFirst().orElseThrow();
        assertThat(fact.action()).startsWith("filter_price_band:");
        assertThat(fact.metrics()).containsKey("min");
        assertThat(fact.metrics()).containsKey("max");
    }

    @Test
    void doesNotGeneratePriceBandFactWithFewerThanSixProducts() {
        List<ProductCard> products = List.of(
                product("1", "商品A", "淘宝", 80),
                product("2", "商品B", "淘宝", 90),
                product("3", "商品C", "淘宝", 100)
        );

        var facts = service.generateFacts(products, Map.of(), SearchFilter.empty());

        assertThat(facts).noneMatch(f -> f.id().equals("insight_price_band"));
    }

    @Test
    void generatesAccessoryRiskFactWhenMultipleAccessories() {
        List<ProductCard> products = List.of(
                productWithRole("1", "主体商品", "main", "淘宝", 100),
                productWithRole("2", "杯套", "accessory", "淘宝", 20),
                productWithRole("3", "杯盖", "case", "淘宝", 15),
                productWithRole("4", "保护壳", "part", "淘宝", 25)
        );

        var facts = service.generateFacts(products, Map.of(), SearchFilter.empty());

        assertThat(facts).anyMatch(f -> f.id().equals("insight_accessory_risk"));
        var fact = facts.stream().filter(f -> f.id().equals("insight_accessory_risk")).findFirst().orElseThrow();
        assertThat(fact.action()).isEqualTo("filter_main_product");
    }

    @Test
    void doesNotGenerateAccessoryRiskFactWhenSearchingForAccessories() {
        // User is searching for "手机壳" — all results are cases, which is expected
        List<ProductCard> products = List.of(
                productWithRole("1", "iPhone 15 手机壳", "case", "淘宝", 30),
                productWithRole("2", "iPhone 15 保护壳", "case", "淘宝", 25),
                productWithRole("3", "iPhone 15 硅胶壳", "case", "淘宝", 20)
        );

        Map<String, String> attributes = Map.of("关键词", "手机壳");
        var facts = service.generateFacts(products, attributes, SearchFilter.empty());

        assertThat(facts).noneMatch(f -> f.id().equals("insight_accessory_risk"));
    }

    @Test
    void doesNotGenerateAccessoryRiskFactWithFewerThanThreeAccessories() {
        List<ProductCard> products = List.of(
                productWithRole("1", "主体商品", "main", "淘宝", 100),
                productWithRole("2", "杯套", "accessory", "淘宝", 20)
        );

        var facts = service.generateFacts(products, Map.of(), SearchFilter.empty());

        assertThat(facts).noneMatch(f -> f.id().equals("insight_accessory_risk"));
    }

    @Test
    void generatesPlatformValueFactWhenSignificantSpread() {
        List<ProductCard> products = List.of(
                product("1", "商品A", "拼多多", 80),
                product("2", "商品B", "拼多多", 90),
                product("3", "商品C", "拼多多", 85),
                product("4", "商品D", "淘宝", 120),
                product("5", "商品E", "淘宝", 130),
                product("6", "商品F", "淘宝", 125)
        );

        var facts = service.generateFacts(products, Map.of(), SearchFilter.empty());

        assertThat(facts).anyMatch(f -> f.id().equals("insight_platform_value"));
        var fact = facts.stream().filter(f -> f.id().equals("insight_platform_value")).findFirst().orElseThrow();
        assertThat(fact.action()).startsWith("filter_platform:");
    }

    @Test
    void doesNotGeneratePlatformValueFactWithSinglePlatform() {
        List<ProductCard> products = List.of(
                product("1", "商品A", "淘宝", 100),
                product("2", "商品B", "淘宝", 200)
        );

        var facts = service.generateFacts(products, Map.of(), SearchFilter.empty());

        assertThat(facts).noneMatch(f -> f.id().equals("insight_platform_value"));
    }

    @Test
    void fallbackCardsGeneratedWhenAiUnavailable() {
        List<GuideInsightFact> facts = List.of(
                new GuideInsightFact("insight_price_band", "price_band", "主流价格区间",
                        "36 件商品集中在 ¥80～¥130", "filter_price_band:80:130", "只看该区间",
                        Map.of("min", 80, "max", 130, "count", 36))
        );

        List<SuggestionCard> cards = service.fallbackCards(facts, 2);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).id()).isEqualTo("insight_price_band");
        assertThat(cards.get(0).action()).isEqualTo("filter_price_band:80:130");
        assertThat(cards.get(0).tone()).isEqualTo("insight");
        assertThat(cards.get(0).badge()).isEqualTo("AI 导购分析");
    }

    @Test
    void aiOutputWithInvalidIdIsIgnored() {
        List<GuideInsightFact> facts = List.of(
                new GuideInsightFact("insight_price_band", "price_band", "主流价格区间",
                        "36 件商品集中在 ¥80～¥130", "filter_price_band:80:130", "只看该区间", Map.of())
        );

        String aiJson = "{\"cards\": [{\"id\": \"invalid_id\", \"title\": \"test\", \"reason\": \"test\", \"actionLabel\": \"test\"}]}";
        List<SuggestionCard> result = service.validateAiOutput(aiJson, facts, 2);

        assertThat(result).isNull();
    }

    @Test
    void aiTextWithFabricatedNumberFallsBackToFactText() {
        // fact says 8 accessories, AI writes 20 — should fallback to fact text
        List<GuideInsightFact> facts = List.of(
                new GuideInsightFact("insight_accessory_risk", "accessory_risk", "疑似配件提醒",
                        "检测到 8 件疑似配件商品", "filter_main_product", "排除配件",
                        Map.of("suspectCount", 8))
        );

        String aiJson = "{\"cards\": [{\"id\": \"insight_accessory_risk\", \"title\": \"检测到 20 件配件\", \"reason\": \"有 20 件配件需要排除\", \"actionLabel\": \"排除\"}]}";
        List<SuggestionCard> result = service.validateAiOutput(aiJson, facts, 2);

        assertThat(result).hasSize(1);
        // title and reason should fall back to fact text because 20 is not in fact metrics
        assertThat(result.get(0).title()).isEqualTo("疑似配件提醒");
        assertThat(result.get(0).reason()).isEqualTo("检测到 8 件疑似配件商品");
        // action should still come from fact
        assertThat(result.get(0).action()).isEqualTo("filter_main_product");
    }

    @Test
    void aiTextWithCorrectNumberPassesValidation() {
        List<GuideInsightFact> facts = List.of(
                new GuideInsightFact("insight_accessory_risk", "accessory_risk", "疑似配件提醒",
                        "检测到 8 件疑似配件商品", "filter_main_product", "排除配件",
                        Map.of("suspectCount", 8))
        );

        String aiJson = "{\"cards\": [{\"id\": \"insight_accessory_risk\", \"title\": \"发现 8 件配件\", \"reason\": \"共 8 件疑似配件\", \"actionLabel\": \"排除\"}]}";
        List<SuggestionCard> result = service.validateAiOutput(aiJson, facts, 2);

        assertThat(result).hasSize(1);
        // 8 is in fact metrics, so AI text should be preserved
        assertThat(result.get(0).title()).isEqualTo("发现 8 件配件");
        assertThat(result.get(0).reason()).isEqualTo("共 8 件疑似配件");
    }

    @Test
    void aiTextWithFabricatedPlatformFallsBackToFactText() {
        List<GuideInsightFact> facts = List.of(
                new GuideInsightFact("insight_platform_value", "platform_value", "平台价差明显",
                        "拼多多当前最低价比淘宝低约20%", "filter_platform:拼多多", "只看该平台",
                        Map.of("cheapestPlatform", "拼多多", "diffPercent", 20))
        );

        // AI mentions eBay which is not in fact metrics
        String aiJson = "{\"cards\": [{\"id\": \"insight_platform_value\", \"title\": \"eBay 价格更低\", \"reason\": \"eBay 比其他平台便宜\", \"actionLabel\": \"看 eBay\"}]}";
        List<SuggestionCard> result = service.validateAiOutput(aiJson, facts, 2);

        assertThat(result).hasSize(1);
        // Should fallback because eBay is not in fact metrics
        assertThat(result.get(0).title()).isEqualTo("平台价差明显");
        assertThat(result.get(0).reason()).isEqualTo("拼多多当前最低价比淘宝低约20%");
    }

    @Test
    void aiOutputUsesFactActionNotAiAction() {
        List<GuideInsightFact> facts = List.of(
                new GuideInsightFact("insight_price_band", "price_band", "主流价格区间",
                        "36 件商品集中在 ¥80～¥130", "filter_price_band:80:130", "只看该区间", Map.of())
        );

        String aiJson = "{\"cards\": [{\"id\": \"insight_price_band\", \"title\": \"先看主流价位\", \"reason\": \"这个区间最多\", \"actionLabel\": \"筛选\", \"action\": \"evil_action\"}]}";
        List<SuggestionCard> result = service.validateAiOutput(aiJson, facts, 2);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).action()).isEqualTo("filter_price_band:80:130");
        assertThat(result.get(0).title()).isEqualTo("先看主流价位");
    }

    @Test
    void insightCardsReturnsEmptyForSmallProductList() {
        List<ProductCard> products = List.of(
                product("1", "商品A", "淘宝", 100),
                product("2", "商品B", "淘宝", 200)
        );

        List<SuggestionCard> cards = service.insightCards("app", products, Map.of(), SearchFilter.empty());

        assertThat(cards).isEmpty();
    }

    @Test
    void insightCardsGeneratedWithFourProductsIncludingAccessories() {
        // 4 products total, 3 are accessories — should trigger accessory risk insight
        List<ProductCard> products = List.of(
                productWithRole("1", "保温杯", "main", "淘宝", 100),
                productWithRole("2", "杯套", "accessory", "淘宝", 20),
                productWithRole("3", "杯盖", "case", "淘宝", 15),
                productWithRole("4", "滤网", "part", "拼多多", 10)
        );

        List<SuggestionCard> cards = service.insightCards("app", products, Map.of(), SearchFilter.empty());

        assertThat(cards).isNotEmpty();
        assertThat(cards).anyMatch(c -> c.id().equals("insight_accessory_risk"));
    }

    @Test
    void insightCardsMaxTwoForAppClient() {
        // Build products that trigger all 3 fact types
        List<ProductCard> products = new java.util.ArrayList<>();
        // Price spread: 12 products at different prices
        for (int i = 0; i < 6; i++) {
            products.add(product("p" + i, "商品" + i, "拼多多", 50 + i * 10));
        }
        for (int i = 6; i < 12; i++) {
            products.add(product("p" + i, "商品" + i, "淘宝", 150 + i * 10));
        }
        // Accessory risk
        products.add(productWithRole("a1", "杯套", "accessory", "拼多多", 20));
        products.add(productWithRole("a2", "杯盖", "case", "淘宝", 15));
        products.add(productWithRole("a3", "保护壳", "part", "拼多多", 25));

        List<SuggestionCard> cards = service.insightCards("app", products, Map.of(), SearchFilter.empty());

        assertThat(cards).hasSizeLessThanOrEqualTo(2);
        assertThat(cards).allMatch(c -> c.id().startsWith("insight_"));
    }

    @Test
    void overlayClientReturnsAtMostOneInsightCard() {
        List<ProductCard> products = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            products.add(product("p" + i, "商品" + i, i % 2 == 0 ? "拼多多" : "淘宝", 50 + i * 20));
        }
        products.add(productWithRole("a1", "杯套", "accessory", "拼多多", 20));
        products.add(productWithRole("a2", "杯盖", "case", "淘宝", 15));
        products.add(productWithRole("a3", "保护壳", "part", "拼多多", 25));

        List<SuggestionCard> cards = service.insightCards("overlay", products, Map.of(), SearchFilter.empty());

        assertThat(cards).hasSizeLessThanOrEqualTo(1);
    }

    // ==================== Helpers ====================

    private ProductCard product(String id, String title, String platform, int price) {
        return new ProductCard(
                id, title, "", BigDecimal.valueOf(price), BigDecimal.valueOf(price + 50L),
                platform, false, "精选店铺", 4.5, 100, 0.9,
                List.of(), "https://example.com/" + id, null, "none", null, "", "unknown"
        );
    }

    private ProductCard productWithRole(String id, String title, String role, String platform, int price) {
        return new ProductCard(
                id, title, "", BigDecimal.valueOf(price), BigDecimal.valueOf(price + 50L),
                platform, false, "精选店铺", 4.5, 100, 0.9,
                List.of(), "https://example.com/" + id, null, "none", null, "", role
        );
    }
}
