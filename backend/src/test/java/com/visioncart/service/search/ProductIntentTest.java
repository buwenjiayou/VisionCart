package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProductIntent 体系测试：ProductIntentBuilder + QueryPlanner + IntentGate
 */
class ProductIntentTest {

    private ProductTaxonomyRegistry taxonomy;
    private ProductIntentBuilder builder;
    private QueryPlanner planner;
    private IntentGate gate;
    private RelevanceRanker ranker;

    @BeforeEach
    void setUp() {
        taxonomy = new ProductTaxonomyRegistry();
        builder = new ProductIntentBuilder(taxonomy);
        planner = new QueryPlanner(taxonomy);
        ranker = new RelevanceRanker();
        gate = new IntentGate(taxonomy, ranker);
    }

    // ====== ProductIntentBuilder 测试 ======

    @Test
    void phoneCaseWithIqooBrand() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(SearchTextUtils.ATTR_CATEGORY, "手机壳");
        attrs.put(SearchTextUtils.ATTR_KEYWORDS, "磁吸金属环,手机壳,铝合金");
        attrs.put(SearchTextUtils.ATTR_BRAND, "iQOO");
        attrs.put(SearchTextUtils.ATTR_BRAND_RELIABLE, "true");

        ProductIntent intent = builder.build(attrs, "session-1");

        assertEquals("手机壳", intent.canonicalProduct());
        assertEquals(ProductIntent.ProductRole.ACCESSORY_MAIN, intent.productRole());
        assertEquals("iQOO", intent.compatibleBrand());
        assertEquals("", intent.productBrand());
        // "磁吸金属环" 整体被分类为 relatedOnly（因为包含 "金属环"）
        assertTrue(intent.relatedOnlyTerms().contains("磁吸金属环") || intent.featureTerms().contains("磁吸金属环"),
                "磁吸金属环 should be classified as feature or relatedOnly, got relatedOnly=" +
                        intent.relatedOnlyTerms() + ", feature=" + intent.featureTerms());
    }

    @Test
    void phoneMainProduct() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(SearchTextUtils.ATTR_CATEGORY, "手机");
        attrs.put(SearchTextUtils.ATTR_KEYWORDS, "iPhone 15,智能手机");
        attrs.put(SearchTextUtils.ATTR_BRAND, "Apple");

        ProductIntent intent = builder.build(attrs, "session-2");

        // "智能手机" 比 "手机" 更长，优先匹配
        assertEquals("智能手机", intent.canonicalProduct());
        assertEquals(ProductIntent.ProductRole.MAIN_PRODUCT, intent.productRole());
        assertEquals("Apple", intent.productBrand());
        assertEquals("", intent.compatibleBrand());
    }

    @Test
    void earbudsMainProduct() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(SearchTextUtils.ATTR_CATEGORY, "耳机");
        attrs.put(SearchTextUtils.ATTR_KEYWORDS, "蓝牙耳机,降噪");
        attrs.put(SearchTextUtils.ATTR_BRAND, "Sony");

        ProductIntent intent = builder.build(attrs, "session-3");

        // "蓝牙耳机" 比 "耳机" 更长，优先匹配
        assertEquals("蓝牙耳机", intent.canonicalProduct());
        assertEquals(ProductIntent.ProductRole.MAIN_PRODUCT, intent.productRole());
    }

    @Test
    void magneticPhoneCaseTypeAndStyleBecomePhoneCaseFeatures() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(SearchTextUtils.ATTR_CATEGORY, "手机配件");
        attrs.put("类型", "磁吸式防摔壳");
        attrs.put(SearchTextUtils.ATTR_STYLE, "磁吸");
        attrs.put(SearchTextUtils.ATTR_BRAND, "Apple");

        ProductIntent intent = builder.build(attrs, "session-magnetic");

        assertEquals("手机壳", intent.canonicalProduct());
        assertEquals("phone_case", intent.productFamily());
        assertEquals(ProductIntent.ProductRole.ACCESSORY_MAIN, intent.productRole());
        assertTrue(intent.featureTerms().contains("磁吸"),
                "featureTerms should include 磁吸: " + intent.featureTerms());
        assertTrue(intent.featureTerms().contains("防摔"),
                "featureTerms should include 防摔: " + intent.featureTerms());
    }

    // ====== QueryPlanner 测试 ======

    @Test
    void phoneCaseQueryPlan() {
        ProductIntent intent = new ProductIntent(
                "s1", "手机壳", "phone_case",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "", "iQOO", "",
                true,
                Map.of(), Map.of(),
                List.of("磁吸", "铝合金"),
                List.of("金属环"),
                List.of(), 1.0, "test"
        );

        QueryPlan plan = planner.plan(intent);

        // primary 查询必须含 "手机壳"
        assertTrue(plan.primaryQueries().stream().anyMatch(q -> q.contains("手机壳")),
                "primary queries must contain 手机壳: " + plan.primaryQueries());
        // primary 查询应含 iQOO
        assertTrue(plan.primaryQueries().stream().anyMatch(q -> q.contains("iQOO")),
                "primary queries must contain iQOO: " + plan.primaryQueries());
        // "金属环" 不能单独成为查询
        assertFalse(plan.isQueryAllowed("金属环"),
                "金属环 should not be allowed as standalone query");
        // "手机壳 金属环" 是合法查询
        assertTrue(plan.isQueryAllowed("手机壳 金属环"),
                "手机壳 金属环 should be allowed");
    }

    @Test
    void forbiddenStandaloneTerms() {
        ProductIntent intent = new ProductIntent(
                "s1", "手机壳", "phone_case",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "", "iQOO", "",
                true,
                Map.of(), Map.of(),
                List.of("磁吸"),
                List.of("金属环", "引磁片"),
                List.of(), 1.0, "test"
        );

        QueryPlan plan = planner.plan(intent);

        assertFalse(plan.isQueryAllowed("金属环"));
        assertFalse(plan.isQueryAllowed("引磁片"));
        assertFalse(plan.isQueryAllowed("磁吸")); // short feature term
        assertTrue(plan.isQueryAllowed("iQOO 手机壳"));
    }

    @Test
    void fallbackQueriesDoNotUseBareBrand() {
        ProductIntent intent = new ProductIntent(
                "s-brand", "phone case", "phone_case",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "", "iQOO", "",
                true,
                Map.of(), Map.of(),
                List.of("magnetic"),
                List.of("metal ring"),
                List.of(), 1.0, "test"
        );

        QueryPlan plan = planner.plan(intent);

        assertTrue(plan.fallbackQueries().contains("phone case"),
                "fallback should keep the main product: " + plan.fallbackQueries());
        assertFalse(plan.fallbackQueries().stream().anyMatch("iQOO"::equalsIgnoreCase),
                "fallback must not contain bare brand queries: " + plan.fallbackQueries());
    }

    @Test
    void queryPlannerDoesNotPutUnreliableAppleIntoMagneticPhoneCasePrimaryQueries() {
        ProductIntent intent = new ProductIntent(
                "s-apple", "手机壳", "phone_case",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "", "Apple", "",
                false,
                Map.of(), Map.of("款式", "磁吸"),
                List.of("磁吸"),
                List.of(),
                List.of(), 0.7, "test"
        );

        QueryPlan plan = planner.plan(intent);

        assertTrue(plan.primaryQueries().contains("磁吸 手机壳"),
                "primary queries should prioritize 磁吸 手机壳: " + plan.primaryQueries());
        assertFalse(plan.primaryQueries().stream().anyMatch(q -> q.contains("Apple")),
                "unreliable Apple should not enter primary queries: " + plan.primaryQueries());
    }

    @Test
    void phoneCaseStrategyRanksMagneticCasesBeforePlainAppleCases() {
        com.visioncart.service.search.strategy.PhoneCaseStrategy strategy =
                new com.visioncart.service.search.strategy.PhoneCaseStrategy(planner, gate);
        ProductIntent intent = new ProductIntent(
                "s-magnetic", "手机壳", "phone_case",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "", "Apple", "",
                false,
                Map.of(), Map.of("款式", "磁吸"),
                List.of("磁吸"),
                List.of(),
                List.of(), 0.7, "test"
        );
        ProductCard applePlain = product("苹果 iPhone 15 透明手机壳", "Apple");
        ProductCard genericPlain = product("通用防摔手机壳", "");
        ProductCard magnetic = product("磁吸 MagSafe 防摔手机壳", "");

        var classified = List.of(
                new com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct(
                        applePlain, strategy.classify(intent, applePlain)),
                new com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct(
                        genericPlain, strategy.classify(intent, genericPlain)),
                new com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct(
                        magnetic, strategy.classify(intent, magnetic))
        );

        List<ProductCard> result = strategy.mix(intent, classified, 3);

        assertEquals(IntentGate.IntentTier.EXACT_MAIN, strategy.classify(intent, magnetic));
        assertEquals(IntentGate.IntentTier.SAME_FAMILY, strategy.classify(intent, applePlain));
        assertEquals("磁吸 MagSafe 防摔手机壳", result.get(0).title());
    }

    // ====== IntentGate 测试 ======

    @Test
    void accessoryMainExactMatch() {
        ProductIntent intent = new ProductIntent(
                "s1", "手机壳", "phone_case",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "", "iQOO", "",
                true,
                Map.of(), Map.of(),
                List.of("磁吸"), List.of("金属环"),
                List.of(), 1.0, "test"
        );

        // iQOO 手机壳 → EXACT_MAIN
        ProductCard iqooCase = product("iQOO 磁吸手机壳", "vivo");
        assertEquals(IntentGate.IntentTier.EXACT_MAIN, gate.classify(intent, iqooCase));

        // 金属环 → RELATED_ACCESSORY
        ProductCard metalRing = product("磁吸金属环 引磁片", "");
        assertEquals(IntentGate.IntentTier.RELATED_ACCESSORY, gate.classify(intent, metalRing));

        // iQOO 手机 → REJECT（搜手机壳不要手机）
        ProductCard phone = product("iQOO 手机", "iQOO");
        assertEquals(IntentGate.IntentTier.REJECT, gate.classify(intent, phone),
                "iQOO 手机 should be REJECTED when searching 手机壳");
    }

    @Test
    void accessoryMainWithoutBrand() {
        ProductIntent intent = new ProductIntent(
                "s1", "手机壳", "phone_case",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "", "", "",
                false,
                Map.of(), Map.of(),
                List.of(), List.of("金属环"),
                List.of(), 0.7, "test"
        );

        // 通用手机壳 → EXACT_MAIN（无品牌要求）
        ProductCard genericCase = product("通用透明手机壳", "");
        assertEquals(IntentGate.IntentTier.EXACT_MAIN, gate.classify(intent, genericCase));
    }

    @Test
    void mainProductRejectsAccessory() {
        ProductIntent intent = new ProductIntent(
                "s1", "手机", "phone",
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "", "", "",
                false,
                Map.of(), Map.of(),
                List.of(), List.of(),
                List.of(), 0.7, "test"
        );

        // 含"手机"的标题 → EXACT_MAIN（无品牌要求）
        ProductCard xiaomi = product("小米14 Pro 5G智能手机", "Xiaomi");
        assertEquals(IntentGate.IntentTier.EXACT_MAIN, gate.classify(intent, xiaomi));

        // 手机壳 → REJECT（搜手机不要手机壳）
        ProductCard phoneCase = product("苹果手机壳", "");
        assertEquals(IntentGate.IntentTier.REJECT, gate.classify(intent, phoneCase),
                "苹果手机壳 should be REJECTED when searching 手机");
    }

    @Test
    void iqooBrandNotInBrandGroups_matchesTitle() {
        // iQOO 已加入 BRAND_GROUPS，应该能匹配标题
        ProductCard p = new ProductCard(
                "id-1", "iQOO 磁吸手机壳", "", java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                "test", false, "shop", 4.5, 100, 0.8, List.of(), "", "vivo", "none", null
        );
        assertTrue(BrandMatcher.productMatchesExpectedBrand(p, "iQOO"),
                "iQOO in title should match expected brand iQOO");
        assertFalse(BrandMatcher.hasConflictingBrand(p, "iQOO"),
                "vivo brand + iQOO in title should NOT conflict");
    }

    @Test
    void nikeBrandMatchesChineseBrandInIntent() {
        // 品牌"耐克"应该匹配 brand="Nike" 的商品
        ProductIntent intent = new ProductIntent(
                "s1", "运动鞋", "shoe",
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "耐克", "", "",
                true,
                Map.of(), Map.of(),
                List.of(), List.of(),
                List.of(), 1.0, "test"
        );

        // Nike 品牌商品 → 应该匹配
        ProductCard nikeShoe = new ProductCard(
                "1", "Nike Pegasus 41 男子公路跑步鞋", "", new java.math.BigDecimal(499), null,
                "淘宝", false, "shop", 4.5, 100, 0.8, List.of(), "", "Nike", "none", null
        );
        assertEquals(IntentGate.IntentTier.EXACT_MAIN, gate.classify(intent, nikeShoe),
                "Nike brand should match 耐克 intent");

        // Adidas 品牌商品 → 应该 REJECT
        ProductCard adidasShoe = new ProductCard(
                "2", "Adidas UltraBoost 运动鞋", "", new java.math.BigDecimal(599), null,
                "淘宝", false, "shop", 4.5, 100, 0.8, List.of(), "", "Adidas", "none", null
        );
        assertEquals(IntentGate.IntentTier.REJECT, gate.classify(intent, adidasShoe),
                "Adidas brand should be REJECTED for 耐克 intent");

        // 无品牌但标题含运动鞋 → SAME_FAMILY
        ProductCard genericShoe = new ProductCard(
                "3", "Pegasus 41 运动鞋同款缓震跑鞋", "", new java.math.BigDecimal(399), null,
                "淘宝", false, "shop", 4.5, 100, 0.8, List.of(), "", null, "none", null
        );
        assertEquals(IntentGate.IntentTier.SAME_FAMILY, gate.classify(intent, genericShoe),
                "No brand shoe with 运动鞋 title should be SAME_FAMILY");
    }

    @Test
    void brandMatcherNikeAndNikeAreSame() {
        assertTrue(BrandMatcher.sameBrand("耐克", "Nike"),
                "耐克 and Nike should be same brand");
        assertTrue(BrandMatcher.productMatchesExpectedBrand(
                new ProductCard("1", "Nike Pegasus 41", "", java.math.BigDecimal.ONE, null,
                        "test", false, "shop", 4.5, 100, 0.8, List.of(), "", "Nike", "none", null),
                "耐克"), "Nike product should match 耐克 brand");
    }

    @Test
    void genericShoeWithoutBrandShouldBeSameFamily() {
        ProductIntent intent = new ProductIntent(
                "s1", "运动鞋", "shoe",
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "耐克", "", "",
                true,
                Map.of(), Map.of(),
                List.of(), List.of(),
                List.of(), 1.0, "test"
        );

        // 标题含"运动鞋"，无品牌 → SAME_FAMILY
        ProductCard simpleShoe = new ProductCard(
                "3", "缓震运动鞋通勤跑鞋", "", new java.math.BigDecimal(399), null,
                "淘宝", false, "shop", 4.5, 100, 0.8, List.of(), "", null, "none", null
        );
        assertEquals(IntentGate.IntentTier.SAME_FAMILY, gate.classify(intent, simpleShoe));

        // 标题含"运动鞋"且含"Pegasus"（不应被误判为华硕品牌），无品牌 → SAME_FAMILY
        ProductCard pegasusShoe = new ProductCard(
                "4", "Pegasus 41 运动鞋同款缓震跑鞋", "", new java.math.BigDecimal(399), null,
                "淘宝", false, "shop", 4.5, 100, 0.8, List.of(), "", null, "none", null
        );
        assertEquals(IntentGate.IntentTier.SAME_FAMILY, gate.classify(intent, pegasusShoe),
                "Pegasus shoe should be SAME_FAMILY, not REJECTED (asus inside pegasus is not a brand conflict)");
    }

    // ====== 辅助 ======

    private ProductCard product(String title, String brand) {
        return new ProductCard(
                "id-" + title.hashCode(), title, "",
                java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                "test", false, "shop", 4.5, 100, 0.8,
                List.of(), "", brand.isBlank() ? null : brand, "none", null
        );
    }
}
