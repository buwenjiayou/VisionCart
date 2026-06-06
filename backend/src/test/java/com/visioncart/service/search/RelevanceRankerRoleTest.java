package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 RelevanceRanker 的结构化角色判断。
 * 验证 mainCategoryCode + productRole 在 tier() 中的过滤效果。
 */
class RelevanceRankerRoleTest {

    private RelevanceRanker ranker;

    @BeforeEach
    void setUp() {
        ranker = new RelevanceRanker();
    }

    // ====== 场景1: 搜杯子 → 应拒绝杯套、杯盖、吸管等配件 ======

    @Test
    void cupSearchShouldRejectCupAccessories() {
        SearchIntent intent = intent("杯子", "杯子", List.of("杯子"));

        // 主品 → 应通过
        for (String title : List.of("马克杯", "玻璃杯", "保温杯", "陶瓷杯")) {
            RelevanceRanker.RelevanceTier t = ranker.tier(product(title, "cup", "main"), intent);
            assertNotEquals(RelevanceRanker.RelevanceTier.REJECTED, t,
                    title + "(main) should not be REJECTED when searching 杯子, got " + t);
        }

        // 配件 → 应拒绝
        assertRejected(ranker.tier(product("杯套", "cup", "accessory"), intent));
        assertRejected(ranker.tier(product("杯盖", "cup", "accessory"), intent));
        assertRejected(ranker.tier(product("杯垫", "cup", "accessory"), intent));
        assertRejected(ranker.tier(product("水杯吸管", "cup", "accessory"), intent));
        assertRejected(ranker.tier(product("保温杯杯盖", "cup", "accessory"), intent));
    }

    // ====== 场景2: 搜杯套 → 应接受杯套、水杯保护套 ======

    @Test
    void cupAccessorySearchShouldAllowCupAccessories() {
        SearchIntent intent = intent("杯套", "杯套", List.of("杯套"));

        // 配件 → 应通过
        RelevanceRanker.RelevanceTier t1 = ranker.tier(product("杯套", "cup", "accessory"), intent);
        assertEquals(RelevanceRanker.RelevanceTier.STRONG, t1, "杯套 should be STRONG");

        RelevanceRanker.RelevanceTier t2 = ranker.tier(product("水杯保护套", "cup", "accessory"), intent);
        assertNotEquals(RelevanceRanker.RelevanceTier.REJECTED, t2, "水杯保护套 should not be REJECTED, got " + t2);

        // 主品 → 搜配件时主品应被拒绝
        assertRejected(ranker.tier(product("马克杯", "cup", "main"), intent));
    }

    // ====== 场景3: 宽泛类目"餐具水具"+关键词"杯子" → 应拒绝杯套 ======

    @Test
    void broadTablewareCategoryWithCupKeywordShouldRejectAccessories() {
        // VLM 返回"餐具水具"，但关键词含"杯子"
        // CategoryNormalizer 会归一到 cup
        SearchIntent intent = intent("餐具水具", "杯子", List.of("杯子"));

        // 主品 → 应通过
        assertNotRejected(ranker.tier(product("马克杯", "cup", "main"), intent));

        // 配件 → 应拒绝
        assertRejected(ranker.tier(product("杯套", "cup", "accessory"), intent));
        assertRejected(ranker.tier(product("杯盖", "cup", "accessory"), intent));
    }

    // ====== 场景4: 搜手机 → 应拒绝手机壳 ======

    @Test
    void phoneSearchShouldRejectPhoneCase() {
        SearchIntent intent = intent("手机", "手机", List.of("手机"));

        // Debug
        assertEquals("phone", intent.mainCategoryCode());
        assertEquals("main", intent.intentRole());
        assertFalse(intent.targetsAccessory());

        ProductCard iPhone = product("iPhone 15", "phone", "main");
        RelevanceRanker.RelevanceTier tier = ranker.tier(iPhone, intent);
        assertNotEquals(RelevanceRanker.RelevanceTier.REJECTED, tier,
                "iPhone 15 should not be REJECTED when searching 手机, got " + tier);

        assertRejected(ranker.tier(product("手机壳", "phone", "accessory"), intent));
        assertRejected(ranker.tier(product("手机膜", "phone", "accessory"), intent));
    }

    // ====== 场景5: 搜手机壳 → 应拒绝手机，接受手机壳 ======

    @Test
    void phoneCaseSearchShouldRejectPhone() {
        SearchIntent intent = intent("手机壳", "手机壳", List.of("手机壳"));

        assertNotRejected(ranker.tier(product("苹果手机壳", "phone", "accessory"), intent));
        assertRejected(ranker.tier(product("iPhone 15", "phone", "main"), intent));
    }

    // ====== 场景6: 跨品类应拒绝 ======

    @Test
    void crossCategoryShouldReject() {
        SearchIntent intent = intent("杯子", "杯子", List.of("杯子"));

        // 手机产品搜杯子时应拒绝
        assertRejected(ranker.tier(product("iPhone 15", "phone", "main"), intent));
    }

    @Test
    void accessoryTermDetectionWorks() {
        assertTrue(SearchTextUtils.isAccessoryTerm("杯套"), "杯套 should be accessory term");
        assertTrue(SearchTextUtils.isAccessoryTerm("手机壳"), "手机壳 should be accessory term");
        assertFalse(SearchTextUtils.isAccessoryTerm("杯子"), "杯子 should NOT be accessory term");
        assertFalse(SearchTextUtils.isAccessoryTerm("马克杯"), "马克杯 should NOT be accessory term");
        assertFalse(SearchTextUtils.isAccessoryTerm("手机"), "手机 should NOT be accessory term");
    }

    @Test
    void targetsAccessoryWorksForAccessoryKeywords() {
        SearchIntent accessoryIntent = intent("杯套", "杯套", List.of("杯套"));
        assertTrue(accessoryIntent.targetsAccessory(), "intent with keyword '杯套' should target accessory");

        SearchIntent mainIntent = intent("杯子", "杯子", List.of("杯子"));
        assertFalse(mainIntent.targetsAccessory(), "intent with keyword '杯子' should NOT target accessory");
    }

    // ====== 真实链路测试：withSimilarity() 自动分类 + tier() 过滤 ======

    @Test
    void phoneSearchShouldAcceptRawIphoneTitleAfterWithSimilarity() {
        SearchIntent intent = intent("手机", "手机", List.of("手机"));
        ProductCard raw = productWithoutRole("iPhone 15");
        List<ProductCard> classified = ranker.withSimilarity(List.of(raw), intent);
        ProductCard iphone = classified.get(0);

        assertEquals("phone", iphone.mainCategoryCode(),
                "iPhone 15 should be classified as 'phone' via brand alias");
        assertNotRejected(ranker.tier(iphone, intent));
    }

    @Test
    void phoneSearchShouldAcceptXiaomiTitleAfterWithSimilarity() {
        SearchIntent intent = intent("手机", "手机", List.of("手机"));
        ProductCard raw = productWithoutRole("小米14 Pro 5G智能手机");
        List<ProductCard> classified = ranker.withSimilarity(List.of(raw), intent);
        ProductCard xiaomi = classified.get(0);

        assertEquals("phone", xiaomi.mainCategoryCode(),
                "小米14 Pro should be classified as 'phone'");
        assertNotRejected(ranker.tier(xiaomi, intent));
    }

    @Test
    void phoneSearchShouldRejectMassagerAfterWithSimilarity() {
        SearchIntent intent = intent("手机", "手机", List.of("手机"));
        ProductCard raw = productWithoutRole("肩颈按摩仪");
        List<ProductCard> classified = ranker.withSimilarity(List.of(raw), intent);
        ProductCard massager = classified.get(0);

        // massager 无法归一化 → code="" → 被 intentCode 非空规则 REJECTED
        assertRejected(ranker.tier(massager, intent));
    }

    @Test
    void headphoneSearchShouldAcceptAirpodsAfterWithSimilarity() {
        SearchIntent intent = intent("耳机", "耳机", List.of("耳机"));
        ProductCard raw = productWithoutRole("Apple AirPods Pro 2");
        List<ProductCard> classified = ranker.withSimilarity(List.of(raw), intent);
        ProductCard airpods = classified.get(0);

        assertEquals("headphone", airpods.mainCategoryCode(),
                "AirPods should be classified as 'headphone' via brand alias");
        assertNotRejected(ranker.tier(airpods, intent));
    }

    // ====== 辅助方法 ======

    private SearchIntent intent(String category, String coreProduct, List<String> keywords) {
        String code = CategoryNormalizer.normalize(category, keywords);
        String role = CategoryNormalizer.intentRole(category, keywords);
        return new SearchIntent(
                "", false, keywords, List.of(), category, coreProduct,
                List.of(), List.of(), List.of(), code, role, false
        );
    }

    private ProductCard product(String title, String categoryCode, String role) {
        return new ProductCard(
                "id-" + title.hashCode(), title, "", BigDecimal.ONE, BigDecimal.ONE,
                "test", false, "shop", 4.5, 100, 0.8, List.of(), "", null, "none", null,
                categoryCode, role
        );
    }

    /** 模拟平台返回的原始商品（无结构化角色，类似真实场景） */
    private ProductCard productWithoutRole(String title) {
        return new ProductCard(
                "id-" + title.hashCode(), title, "", BigDecimal.ONE, BigDecimal.ONE,
                "test", false, "shop", 4.5, 100, 0.8, List.of(), "", null, "none", null
        );
    }

    private void assertRejected(RelevanceRanker.RelevanceTier tier) {
        assertEquals(RelevanceRanker.RelevanceTier.REJECTED, tier,
                "Expected REJECTED but got " + tier);
    }

    private void assertNotRejected(RelevanceRanker.RelevanceTier tier) {
        assertNotEquals(RelevanceRanker.RelevanceTier.REJECTED, tier,
                "Expected STRONG or SAFE_FILL but got REJECTED");
    }
}
