package com.visioncart.service.filter.semantic;

import com.visioncart.api.dto.*;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.search.CandidateFilterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SemanticActionExecutorTest {

    private CandidateFilterService filterService;
    private NlpConversationManager conversationManager;
    private LlmProductJudge productJudge;
    private SemanticActionExecutor executor;

    @BeforeEach
    void setUp() {
        filterService = mock(CandidateFilterService.class);
        conversationManager = mock(NlpConversationManager.class);
        productJudge = mock(LlmProductJudge.class);
        executor = new SemanticActionExecutor(
                filterService,
                new EvidenceScorer(new com.visioncart.service.filter.capability.ProductFeatureExtractor()),
                conversationManager,
                new PreferenceScorer(),
                productJudge);

        when(filterService.filter(anyList(), any(), anyMap(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<ProductCard> products = invocation.getArgument(0, List.class);
                    SearchFilter filter = invocation.getArgument(1);
                    List<ProductCard> filtered = applySimpleFilter(products, filter);
                    return new CandidateFilterService.FilterResult(
                            filtered, filtered.size(), filtered.size(), false, false);
                });
        when(productJudge.minScore(any(SemanticActionPlan.JudgePlan.class))).thenReturn(0.35);
        when(productJudge.returnLimit(any(SemanticActionPlan.JudgePlan.class))).thenReturn(50);
    }

    @Test
    void combinedPlanAppliesHardFilterExclusionThenJudge() {
        ProductCard gift = product("p1", "高颜值香薰礼盒 女生生日礼物", 199.0, "main");
        ProductCard accessory = product("p2", "礼盒包装袋 配件", 29.0, "accessory");
        ProductCard expensive = product("p3", "高端香薰礼盒", 599.0, "main");

        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results",
                "COMBINED",
                "香薰",
                List.of(new SemanticActionPlan.HardFilter("price", "<=", 300)),
                null,
                null,
                List.of(new SemanticActionPlan.ExclusionRule(
                        "accessory", "不要配件", List.of("配件"), List.of("accessory"))),
                null,
                "KEEP_PREVIOUS_RESULTS",
                null,
                null,
                new SemanticActionPlan.JudgePlan(
                        true,
                        "适合女生送礼",
                        List.of("礼盒", "高颜值", "价格适中"),
                        List.of("配件", "廉价感"),
                        80,
                        50,
                        0.35),
                "优先展示适合作为女生礼物且不是配件的商品",
                null);

        when(productJudge.judge(anyString(), any(), any(), anyList()))
                .thenReturn(List.of(new LlmProductJudge.JudgeScore(
                        "p1", 0.92, "GOOD_MATCH", List.of("礼盒装", "价格适中"), List.of())));

        ActionResult result = executor.execute(
                "session-1",
                List.of(gift, accessory, expensive),
                SearchFilter.empty(),
                plan,
                List.of(gift, accessory, expensive));

        assertThat(result.filterApplied()).isTrue();
        assertThat(result.products()).extracting(ProductCard::id).containsExactly("p1");
        assertThat(result.appliedFilter().priceRange().max()).isEqualTo(300);
        assertThat(result.message()).contains("适合女生送礼");
        assertThat(result.messageCode()).isNull();
        assertThat(result.explanations()).anyMatch(text -> text.contains("礼盒装"));
        verify(productJudge).judge(eq("适合女生送礼"), contains("女生礼物"), any(), anyList());
        verify(conversationManager).setFilterState(eq("session-1"), any(SearchFilter.class));
    }

    @Test
    void judgeFailureFallsBackWithoutClearingProducts() {
        ProductCard first = product("p1", "普通背包", 99.0, "main");
        ProductCard second = product("p2", "商务通勤背包", 129.0, "main");
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results",
                "LLM_RERANK",
                "背包",
                null,
                null,
                List.of(new SemanticActionPlan.PreferenceRule("commute", "通勤方便", 0.8)),
                null,
                null,
                "KEEP_PREVIOUS_RESULTS",
                null,
                null,
                new SemanticActionPlan.JudgePlan(
                        true, "办公室不突兀", List.of("商务", "简约"), List.of("夸张"), 80, 50, 0.35),
                "优先展示办公室使用不突兀的商品",
                null);

        when(productJudge.judge(anyString(), any(), any(), anyList())).thenReturn(List.of());

        ActionResult result = executor.execute(
                "session-1",
                List.of(first, second),
                SearchFilter.empty(),
                plan,
                List.of(first, second));

        assertThat(result.filterApplied()).isTrue();
        assertThat(result.products()).hasSize(2);
        assertThat(result.warnings()).contains("AI语义评分不可用，已使用本地排序规则");
        assertThat(result.messageCode()).isNull();
    }

    @Test
    void judgeExceptionFallsBackWithoutClearingProducts() {
        ProductCard first = product("p1", "普通背包", 99.0, "main");
        ProductCard second = product("p2", "商务通勤背包", 129.0, "main");
        SemanticActionPlan plan = judgePlan("办公室不突兀");

        when(productJudge.judge(anyString(), any(), any(), anyList()))
                .thenThrow(new RuntimeException("timeout"));

        ActionResult result = executor.execute(
                "session-1",
                List.of(first, second),
                SearchFilter.empty(),
                plan,
                List.of(first, second));

        assertThat(result.filterApplied()).isTrue();
        assertThat(result.products()).hasSize(2);
        assertThat(result.products()).extracting(ProductCard::id).containsExactlyInAnyOrder("p1", "p2");
        assertThat(result.warnings()).contains("AI语义评分不可用，已使用本地排序规则");
    }

    @Test
    void allRejectJudgeScoresFallbackWithoutClearingProducts() {
        ProductCard first = product("p1", "普通背包", 99.0, "main");
        ProductCard second = product("p2", "商务通勤背包", 129.0, "main");
        SemanticActionPlan plan = judgePlan("办公室不突兀");

        when(productJudge.judge(anyString(), any(), any(), anyList()))
                .thenReturn(List.of(
                        new LlmProductJudge.JudgeScore("p1", 0.05, "REJECT", List.of("太张扬"), List.of()),
                        new LlmProductJudge.JudgeScore("p2", 0.10, "REJECT", List.of("信息不足"), List.of())
                ));

        ActionResult result = executor.execute(
                "session-1",
                List.of(first, second),
                SearchFilter.empty(),
                plan,
                List.of(first, second));

        assertThat(result.filterApplied()).isTrue();
        assertThat(result.products()).hasSize(2);
        assertThat(result.products()).extracting(ProductCard::id).containsExactlyInAnyOrder("p1", "p2");
        assertThat(result.warnings()).contains("AI语义评分不可用，已使用本地排序规则");
    }

    private SemanticActionPlan judgePlan(String userMeaning) {
        return new SemanticActionPlan(
                "filter_current_results",
                "LLM_RERANK",
                "背包",
                null,
                null,
                List.of(new SemanticActionPlan.PreferenceRule("commute", "通勤方便", 0.8)),
                null,
                null,
                "KEEP_PREVIOUS_RESULTS",
                null,
                null,
                new SemanticActionPlan.JudgePlan(
                        true, userMeaning, List.of("商务", "简约"), List.of("夸张"), 12, 12, 0.35),
                "优先展示办公室使用不突兀的商品",
                null);
    }

    private List<ProductCard> applySimpleFilter(List<ProductCard> products, SearchFilter filter) {
        if (filter == null) return products;
        return products.stream()
                .filter(product -> filter.priceRange() == null
                        || filter.priceRange().max() == null
                        || product.price().doubleValue() <= filter.priceRange().max())
                .filter(product -> matchesAttributes(product, filter.attributes()))
                .filter(product -> filter.excludeRoles() == null
                        || filter.excludeRoles().isEmpty()
                        || !filter.excludeRoles().contains(product.productRole()))
                .toList();
    }

    private boolean matchesAttributes(ProductCard product, Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return true;
        String text = String.join(" ",
                product.title() != null ? product.title() : "",
                product.brand() != null ? product.brand() : "",
                product.shopName() != null ? product.shopName() : "",
                String.join(" ", product.tags() == null ? List.of() : product.tags())
        ).toLowerCase();
        return attributes.values().stream()
                .filter(value -> value != null && !value.isBlank())
                .allMatch(value -> text.contains(value.toLowerCase()));
    }

    // ==================== Sort tests ====================

    @Test
    void strictFilterSortByPriceAscApplied() {
        ProductCard cheap = product("p1", "便宜充电宝", 39.0, "main");
        ProductCard mid = product("p2", "中档充电宝", 99.0, "main");
        ProductCard expensive = product("p3", "高端充电宝", 199.0, "main");

        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "充电宝",
                null, null, null, null,
                new SemanticActionPlan.SortRule("price", "asc"),
                "KEEP_PREVIOUS_RESULTS");

        ActionResult result = executor.execute(
                "session-1", List.of(expensive, cheap, mid),
                SearchFilter.empty(), plan, List.of(expensive, cheap, mid));

        assertThat(result.filterApplied()).isTrue();
        assertThat(result.appliedFilter().sortBy()).isEqualTo("price");
        assertThat(result.appliedFilter().sortOrder()).isEqualTo("asc");
    }

    @Test
    void strictFilterSortByRatingDescApplied() {
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "耳机",
                null, null, null, null,
                new SemanticActionPlan.SortRule("rating", "desc"),
                "KEEP_PREVIOUS_RESULTS");

        ActionResult result = executor.execute(
                "session-1", List.of(product("p1", "耳机", 99.0, "main")),
                SearchFilter.empty(), plan, List.of(product("p1", "耳机", 99.0, "main")));

        assertThat(result.appliedFilter().sortBy()).isEqualTo("rating");
        assertThat(result.appliedFilter().sortOrder()).isEqualTo("desc");
    }

    // ==================== Hard filter edge cases ====================

    @Test
    void hardFilterWithNullOperatorDoesNotCrash() {
        // LLM might output field=platform, value=淘宝, operator=null
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "充电宝",
                List.of(new SemanticActionPlan.HardFilter("platform", null, "淘宝")),
                null, null, null, null, "KEEP_PREVIOUS_RESULTS");

        ProductCard taobao = product("p1", "充电宝", 50.0, "main");
        ActionResult result = executor.execute(
                "session-1", List.of(taobao),
                SearchFilter.empty(), plan, List.of(taobao));

        // Should not throw NPE
        assertThat(result).isNotNull();
    }

    @Test
    void hardFilterPriceLessThan50() {
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "充电宝",
                List.of(new SemanticActionPlan.HardFilter("price", "<", 50)),
                null, null, null, null, "KEEP_PREVIOUS_RESULTS");

        ProductCard cheap = product("p1", "便宜充电宝", 39.0, "main");
        ProductCard mid = product("p2", "中档充电宝", 99.0, "main");

        ActionResult result = executor.execute(
                "session-1", List.of(cheap, mid),
                SearchFilter.empty(), plan, List.of(cheap, mid));

        assertThat(result.filterApplied()).isTrue();
        assertThat(result.appliedFilter().priceRange().max()).isEqualTo(50.0);
    }

    @Test
    void hardFilterAttributeTypeWiredMouse() {
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "mouse",
                List.of(new SemanticActionPlan.HardFilter(
                        "attribute:\u7c7b\u578b", "equals", "\u6709\u7ebf")),
                null, null, null, null, "KEEP_PREVIOUS_RESULTS");

        ProductCard wired = product("p1", "USB \u6709\u7ebf\u9f20\u6807 office", 49.0, "main");
        ProductCard wireless = product("p2", "Bluetooth \u65e0\u7ebf\u9f20\u6807 office", 59.0, "main");

        ActionResult result = executor.execute(
                "session-attr", List.of(wired, wireless),
                SearchFilter.empty(), plan, List.of(wired, wireless));

        assertThat(result.filterApplied()).isTrue();
        assertThat(result.products()).extracting(ProductCard::id).containsExactly("p1");
        assertThat(result.appliedFilter().attributes())
                .containsEntry("\u7c7b\u578b", "\u6709\u7ebf");
        assertThat(result.filterTags()).extracting(FilterTag::filterPath)
                .contains("attributes.\u7c7b\u578b");
        assertThat(result.filterTags()).extracting(FilterTag::label)
                .contains("\u6709\u7ebf");
    }

    @Test
    void hardFilterAttributeSupportsWirelessBluetoothInterfaceAndMaterial() {
        SemanticActionPlan wirelessPlan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "mouse",
                List.of(new SemanticActionPlan.HardFilter(
                        "attribute:\u8fde\u63a5\u65b9\u5f0f", "equals", "\u84dd\u7259")),
                null, null, null, null, "KEEP_PREVIOUS_RESULTS");
        ProductCard bluetoothMouse = product("p1", "\u84dd\u7259 \u65e0\u7ebf\u9f20\u6807 2.4G", 79.0, "main");
        ProductCard usbMouse = product("p2", "USB \u6709\u7ebf\u9f20\u6807", 39.0, "main");

        ActionResult wirelessResult = executor.execute(
                "session-attr-1", List.of(bluetoothMouse, usbMouse),
                SearchFilter.empty(), wirelessPlan, List.of(bluetoothMouse, usbMouse));

        assertThat(wirelessResult.products()).extracting(ProductCard::id).containsExactly("p1");
        assertThat(wirelessResult.appliedFilter().attributes())
                .containsEntry("\u8fde\u63a5\u65b9\u5f0f", "\u84dd\u7259");

        SemanticActionPlan interfacePlan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "charger",
                List.of(new SemanticActionPlan.HardFilter(
                        "attribute:\u63a5\u53e3", "equals", "Type-C")),
                null, null, null, null, "KEEP_PREVIOUS_RESULTS");
        ProductCard typeC = product("p3", "Type-C \u63a5\u53e3 \u5feb\u5145\u5145\u7535\u5668", 49.0, "main");
        ProductCard lightning = product("p4", "Lightning \u63a5\u53e3 \u5145\u7535\u5668", 59.0, "main");

        ActionResult interfaceResult = executor.execute(
                "session-attr-2", List.of(typeC, lightning),
                SearchFilter.empty(), interfacePlan, List.of(typeC, lightning));

        assertThat(interfaceResult.products()).extracting(ProductCard::id).containsExactly("p3");
        assertThat(interfaceResult.appliedFilter().attributes())
                .containsEntry("\u63a5\u53e3", "Type-C");

        SemanticActionPlan materialPlan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "case",
                List.of(new SemanticActionPlan.HardFilter(
                        "attribute:\u6750\u8d28", "equals", "\u7845\u80f6")),
                null, null, null, null, "KEEP_PREVIOUS_RESULTS");
        ProductCard silicone = product("p5", "iPhone15 \u7845\u80f6 \u624b\u673a\u58f3", 29.0, "main");
        ProductCard metal = product("p6", "iPhone15 \u91d1\u5c5e \u624b\u673a\u58f3", 39.0, "main");

        ActionResult materialResult = executor.execute(
                "session-attr-3", List.of(silicone, metal),
                SearchFilter.empty(), materialPlan, List.of(silicone, metal));

        assertThat(materialResult.products()).extracting(ProductCard::id).containsExactly("p5");
        assertThat(materialResult.appliedFilter().attributes())
                .containsEntry("\u6750\u8d28", "\u7845\u80f6");
    }

    @Test
    void hardFilterAttributesRequireAllObjectiveAttributesToMatch() {
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", "phone case",
                List.of(
                        new SemanticActionPlan.HardFilter(
                                "attribute:\u6750\u8d28", "equals", "\u7845\u80f6"),
                        new SemanticActionPlan.HardFilter(
                                "attribute:\u9002\u914d\u578b\u53f7", "equals", "iPhone15")),
                null, null, null, null, "KEEP_PREVIOUS_RESULTS");

        ProductCard both = product("p1", "iPhone15 \u7845\u80f6 \u624b\u673a\u58f3", 29.0, "main");
        ProductCard onlyModel = product("p2", "iPhone15 \u900f\u660e PC \u624b\u673a\u58f3", 19.0, "main");
        ProductCard onlyMaterial = product("p3", "\u5c0f\u7c73 \u7845\u80f6 \u624b\u673a\u58f3", 19.0, "main");

        ActionResult result = executor.execute(
                "session-attr-all", List.of(both, onlyModel, onlyMaterial),
                SearchFilter.empty(), plan, List.of(both, onlyModel, onlyMaterial));

        assertThat(result.products()).extracting(ProductCard::id).containsExactly("p1");
        assertThat(result.appliedFilter().attributes())
                .containsEntry("\u6750\u8d28", "\u7845\u80f6")
                .containsEntry("\u9002\u914d\u578b\u53f7", "iPhone15");
        assertThat(result.filterTags()).extracting(FilterTag::filterPath)
                .contains("attributes.\u6750\u8d28", "attributes.\u9002\u914d\u578b\u53f7");
    }

    @Test
    void combinedPlanAppliesHardFilterThenPreferenceRerankAndKeepsBothTags() {
        ProductCard youthStyle = product("p1", "young style accessory", 8.0, "main");
        ProductCard basic = product("p2", "basic accessory", 9.0, "main");
        ProductCard expensive = product("p3", "young premium accessory", 19.0, "main");
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results", "COMBINED", "accessory",
                List.of(new SemanticActionPlan.HardFilter("price", "<=", 10)),
                null,
                List.of(new SemanticActionPlan.PreferenceRule("young_user", "young people", 0.8)),
                null, null, "KEEP_PREVIOUS_RESULTS");

        ActionResult result = executor.execute(
                "session-1", List.of(basic, expensive, youthStyle),
                SearchFilter.empty(), plan, List.of(basic, expensive, youthStyle));

        assertThat(result.filterApplied()).isTrue();
        assertThat(result.products()).extracting(ProductCard::id)
                .containsExactlyInAnyOrder("p1", "p2")
                .doesNotContain("p3");
        assertThat(result.appliedFilter().priceRange().max()).isEqualTo(10.0);
        assertThat(result.filterTags())
                .extracting(FilterTag::filterPath)
                .contains("price_range.max", "preferences.young_user");
        assertThat(result.filterTags())
                .extracting(FilterTag::label)
                .contains("young people");
    }

    // ==================== Preference rerank ====================

    @Test
    void preferenceRerankActuallySorts() {
        ProductCard lowRating = product("p1", "普通充电宝", 50.0, "main");
        ProductCard highRating = product("p2", "高评分充电宝", 80.0, "main");
        // Set different ratings
        ProductCard lowRated = new ProductCard("p1", "普通充电宝", "https://img.example/p1.jpg",
                BigDecimal.valueOf(50), null, "淘宝", false, "Shop", 3.0, 100, 0.9,
                List.of(), "https://example.com/p1", "品牌", "none", null, "", "main");
        ProductCard highRated = new ProductCard("p2", "高评分充电宝", "https://img.example/p2.jpg",
                BigDecimal.valueOf(80), null, "淘宝", false, "Shop", 4.9, 5000, 0.9,
                List.of(), "https://example.com/p2", "品牌", "none", null, "", "main");

        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results", "PREFERENCE_RERANK", "充电宝",
                null, null,
                List.of(new SemanticActionPlan.PreferenceRule("cost_effective", "性价比高", 0.8)),
                null, null, "KEEP_PREVIOUS_RESULTS");

        ActionResult result = executor.execute(
                "session-1", List.of(lowRated, highRated),
                SearchFilter.empty(), plan, List.of(lowRated, highRated));

        assertThat(result.filterApplied()).isTrue();
        assertThat(result.products()).hasSize(2);
        // PreferenceScorer should rank highRated first (better rating + more sales)
        assertThat(result.products().get(0).id()).isEqualTo("p2");
    }

    @Test
    void preferenceRerankOnlyReturnsCurrentPlanPreferenceTags() {
        ProductCard youthStyle = product("p1", "young style accessory", 8.0, "main");
        ProductCard basic = product("p2", "basic accessory", 9.0, "main");
        SearchFilter existingFilter = new SearchFilter(
                new PriceRange(null, 10.0),
                List.of(), null, List.of(), List.of(), null,
                null, null, null);
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results", "PREFERENCE_RERANK", "accessory",
                null, null,
                List.of(new SemanticActionPlan.PreferenceRule("young_user", "年轻人", 0.8)),
                null, null, "KEEP_PREVIOUS_RESULTS");

        ActionResult result = executor.execute(
                "session-1", List.of(youthStyle, basic),
                existingFilter, plan, List.of(youthStyle, basic));

        assertThat(result.appliedFilter().priceRange().max()).isEqualTo(10.0);
        assertThat(result.filterTags())
                .extracting(FilterTag::filterPath)
                .doesNotContain("price_range.max")
                .contains("preferences.young_user");
        assertThat(result.filterTags())
                .extracting(FilterTag::label)
                .contains("年轻人");
    }

    // ==================== Exclusion ====================

    @Test
    void exclusionFiltersAccessoriesAndGeneratesTags() {
        ProductCard main = product("p1", "充电宝", 99.0, "main");
        ProductCard accessory = product("p2", "充电宝保护套", 19.0, "accessory");

        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results", "EXCLUSION", "充电宝",
                null, null, null,
                List.of(new SemanticActionPlan.ExclusionRule(
                        "accessory", "不要配件", List.of("保护套"), List.of("accessory"))),
                null, "KEEP_PREVIOUS_RESULTS");

        ActionResult result = executor.execute(
                "session-1", List.of(main, accessory),
                SearchFilter.empty(), plan, List.of(main, accessory));

        assertThat(result.filterApplied()).isTrue();
        assertThat(result.products()).extracting(ProductCard::id).containsExactly("p1");
        assertThat(result.filterTags()).isNotEmpty();
        assertThat(result.filterTags().get(0).source()).isEqualTo("exclusion");
    }

    private ProductCard product(String id, String title, double price, String role) {
        return new ProductCard(
                id,
                title,
                "https://example.com/" + id + ".jpg",
                BigDecimal.valueOf(price),
                null,
                "淘宝",
                false,
                "旗舰店",
                4.8,
                1000,
                0.9,
                List.of("礼盒"),
                "https://example.com/" + id,
                "品牌",
                "none",
                null,
                "礼品",
                role);
    }
}
