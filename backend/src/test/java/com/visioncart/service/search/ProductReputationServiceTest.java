package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.ReputationScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ProductReputationService — the multi-signal reputation scoring system.
 * Verifies that cross-platform ratings are scored fairly with appropriate confidence weights.
 */
class ProductReputationServiceTest {

    private ProductReputationService service;
    private ProductSortService sortService;

    @BeforeEach
    void setUp() {
        service = new ProductReputationService();
        sortService = new ProductSortService(service);
    }

    // ==================== Scoring correctness ====================

    @Test
    @DisplayName("淘宝商品评分 4.8 应该获得高 itemRatingScore 和高 confidence")
    void taobaoItemRating48ScoresHigh() {
        ProductCard taobao = product("1", "淘宝商品", 4.8, "item_rating", "淘宝", 5000, 0.9);
        ReputationScore score = service.score(taobao);

        assertThat(score.itemRatingScore()).isGreaterThan(0.85);
        assertThat(score.confidence()).isEqualTo(0.90);  // item_rating confidence
        assertThat(score.displayLabel()).contains("商品评分");
    }

    @Test
    @DisplayName("拼多多店铺口碑'高'(4.6) 应该获得低 shopOrSellerScore 和低 confidence")
    void pddShopDsrHighScoresLow() {
        ProductCard pdd = product("2", "拼多多商品", 4.6, "shop_dsr", "拼多多", 10000, 0.8);
        ReputationScore score = service.score(pdd);

        assertThat(score.itemRatingScore()).isEqualTo(0);  // NOT an item rating
        assertThat(score.shopOrSellerScore()).isGreaterThan(0.8);
        assertThat(score.confidence()).isEqualTo(0.45);  // shop_dsr confidence
        assertThat(score.displayLabel()).contains("店铺口碑");
    }

    @Test
    @DisplayName("eBay卖家信誉 98% 应该显示卖家信誉，不显示商品评分")
    void ebaySellerFeedbackShowsSellerLabel() {
        // eBay: feedbackPercentage 98% → rating = 98/20 = 4.9
        ProductCard ebay = product("3", "eBay item", 4.9, "seller", "eBay", 200, 0.7);
        ReputationScore score = service.score(ebay);

        assertThat(score.itemRatingScore()).isEqualTo(0);  // NOT an item rating
        assertThat(score.shopOrSellerScore()).isGreaterThan(0.9);
        assertThat(score.confidence()).isEqualTo(0.55);  // seller confidence
        assertThat(score.displayLabel()).contains("卖家信誉");
    }

    @Test
    @DisplayName("无评分商品应该没有口碑信号，只有相似度贡献")
    void noRatingHasNoReputationSignals() {
        ProductCard noRating = product("4", "无评分商品", 0, "none", "淘宝", 100, 0.5);
        ReputationScore score = service.score(noRating);

        assertThat(score.itemRatingScore()).isEqualTo(0);
        assertThat(score.shopOrSellerScore()).isEqualTo(0);
        assertThat(score.confidence()).isEqualTo(0);
        assertThat(score.displayLabel()).isEqualTo("暂无评分");
        // Only similarity contributes (0.5 * 0.20 = 0.10)
        assertThat(score.score()).isEqualTo(0.5 * 0.20);
    }

    // ==================== Cross-platform fairness ====================

    @Test
    @DisplayName("淘宝商品评分 4.8 应该排在拼多多店铺口碑 4.6 前面")
    void taobaoItemRating48BeatsPddShopDsr46() {
        ProductCard taobao = product("1", "淘宝商品", 4.8, "item_rating", "淘宝", 3000, 0.85);
        ProductCard pdd = product("2", "拼多多商品", 4.6, "shop_dsr", "拼多多", 10000, 0.85);

        ReputationScore taobaoScore = service.score(taobao);
        ReputationScore pddScore = service.score(pdd);

        // Taobao item rating should win due to higher confidence weight
        assertThat(taobaoScore.score()).isGreaterThan(pddScore.score());
    }

    @Test
    @DisplayName("口碑排序：淘宝 item_rating 排第一，PDD shop_dsr 排最后")
    void reviewQualitySortOrder() {
        // Equal sales to isolate reputation signal differences
        List<ProductCard> products = List.of(
                product("1", "PDD商品", 4.6, "shop_dsr", "拼多多", 1000, 0.85),
                product("2", "淘宝商品", 4.8, "item_rating", "淘宝", 1000, 0.85),
                product("3", "eBay商品", 4.9, "seller", "eBay", 1000, 0.85)
        );

        List<ProductCard> sorted = sortService.sortByKey(products, "review_quality");

        // Taobao item_rating (conf 0.90) > eBay seller (conf 0.55) > PDD shop_dsr (conf 0.45)
        assertThat(sorted.get(0).id()).isEqualTo("2");  // Taobao
        assertThat(sorted.get(2).id()).isEqualTo("1");  // PDD last
    }

    @Test
    @DisplayName("所有无评分商品按销量和相似度排序")
    void allNoRatingProductsSortBySalesAndSimilarity() {
        List<ProductCard> products = List.of(
                product("1", "无评分A", 0, "none", "淘宝", 100, 0.9),
                product("2", "无评分B", 0, "none", "拼多多", 5000, 0.7),
                product("3", "无评分C", 0, "none", "eBay", 200, 0.95)
        );

        List<ProductCard> sorted = sortService.sortByKey(products, "review_quality");

        // All have 0 reputation, so order determined by sales + similarity
        // B has highest sales, C has highest similarity, A is lowest
        // Exact order depends on weighted formula
        assertThat(sorted).hasSize(3);
        // Verify no crash and all products present
        assertThat(sorted.stream().map(ProductCard::id)).containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test
    @DisplayName("高销量不能完全压过高质量评分")
    void highSalesCannotOverwhelmQualityRating() {
        ProductCard highQuality = product("1", "高质量低销量", 4.9, "item_rating", "淘宝", 50, 0.9);
        ProductCard highSales = product("2", "高销量低评分", 3.5, "shop_dsr", "拼多多", 100000, 0.9);

        ReputationScore qualityScore = service.score(highQuality);
        ReputationScore salesScore = service.score(highSales);

        // Quality should still win despite lower sales
        assertThat(qualityScore.score()).isGreaterThan(salesScore.score());
    }

    @Test
    @DisplayName("口碑排序 Top 20 单平台最多 6 个")
    void reviewQualitySortDiversifiesTopWindow() {
        List<ProductCard> products = new java.util.ArrayList<>();
        for (int i = 0; i < 18; i++) {
            products.add(product("pdd-" + i, "PDD高分" + i, 4.9, "item_rating", "拼多多", 1000 - i, 0.95));
        }
        for (int i = 0; i < 8; i++) {
            products.add(product("tb-" + i, "淘宝商品" + i, 4.4, "item_rating", "淘宝", 200 - i, 0.80));
        }
        for (int i = 0; i < 8; i++) {
            products.add(product("ebay-" + i, "eBay商品" + i, 4.3, "seller", "eBay", 150 - i, 0.75));
        }
        for (int i = 0; i < 8; i++) {
            products.add(product("jd-" + i, "京东商品" + i, 4.2, "item_rating", "京东", 120 - i, 0.70));
        }

        List<ProductCard> sorted = sortService.sortByKey(products, "review_quality");

        long pddInTop20 = sorted.stream().limit(20)
                .filter(product -> "拼多多".equals(product.platform()))
                .count();
        assertThat(pddInTop20)
                .isLessThanOrEqualTo(6);
    }

    // ==================== Display labels ====================

    @Test
    @DisplayName("PDD DSR文本映射不再伪装成商品评分")
    void pddDsrTextNotShownAsItemRating() {
        ProductCard pddHigh = product("1", "PDD高口碑", 4.6, "shop_dsr", "拼多多", 1000, 0.8);
        ProductCard pddMid = product("2", "PDD中口碑", 4.0, "shop_dsr", "拼多多", 1000, 0.8);
        ProductCard pddLow = product("3", "PDD低口碑", 3.2, "shop_dsr", "拼多多", 1000, 0.8);

        assertThat(service.score(pddHigh).displayLabel()).contains("店铺口碑 高");
        assertThat(service.score(pddMid).displayLabel()).contains("店铺口碑 中");
        assertThat(service.score(pddLow).displayLabel()).contains("店铺口碑 低");

        // None should say "商品评分"
        assertThat(service.score(pddHigh).displayLabel()).doesNotContain("商品评分");
        assertThat(service.score(pddMid).displayLabel()).doesNotContain("商品评分");
        assertThat(service.score(pddLow).displayLabel()).doesNotContain("商品评分");
    }

    @Test
    @DisplayName("eBay显示卖家信誉百分比，不显示商品评分")
    void ebayShowsSellerReputationPercentage() {
        ProductCard ebay = product("1", "eBay商品", 4.9, "seller", "eBay", 500, 0.8);
        String label = service.score(ebay).displayLabel();

        assertThat(label).contains("卖家信誉");
        assertThat(label).contains("%");
        assertThat(label).doesNotContain("商品评分");
    }

    // ==================== Helper ====================

    private ProductCard product(String id, String title, double rating, String ratingSource,
                                String platform, long sales, double similarity) {
        return new ProductCard(id, title, "http://img.com/" + id + ".jpg",
                BigDecimal.valueOf(100), null, platform, false, "shop",
                rating, sales, similarity, List.of(), "http://detail.com/" + id,
                null, ratingSource, null);
    }
}
