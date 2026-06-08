package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.ReputationScore;
import com.visioncart.config.VisionCartProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class ProductReputationServiceTest {

    private ProductReputationService service;
    private ProductSortService sortService;

    @BeforeEach
    void setUp() {
        service = new ProductReputationService();
        sortService = new ProductSortService(service);
    }

    @Test
    void itemRatingDoesNotContributeToShopTrustScore() {
        ProductCard itemOnly = product("tb-item", "taobao item", 4.9, "item_rating", "taobao", 1000, 0.90)
                .withReputationSignals(4.9, null, null, null, "item_rating");

        ReputationScore score = service.shopTrustScore(itemOnly, List.of(itemOnly));
        ProductCard enriched = service.attachShopTrustReputation(List.of(itemOnly)).get(0);

        assertThat(score.itemRatingScore()).isZero();
        assertThat(score.shopOrSellerScore()).isZero();
        assertThat(score.confidence()).isZero();
        assertThat(score.displayLabel()).isNull();
        assertThat(enriched.reputationIndex()).isZero();
        assertThat(enriched.ratingDisplayLabel()).isNull();
    }

    @Test
    void calibratedTrustIndexUsesPlatformNormalizedScoreTimesConfidence() {
        ProductCard taobao = product("tb-shop", "taobao shop", 4.8, "shop_dsr", "taobao", 1000, 0.80)
                .withReputationSignals(null, 0.96, null, null, "shop_dsr");
        ProductCard pddHigh = product("pdd-high", "pdd high", 4.6, "shop_dsr", "pdd", 1000, 0.80)
                .withReputationSignals(null, 0.85, "high", null, "pdd_shop_level");
        ProductCard ebay = product("ebay", "ebay seller", 4.9, "seller", "eBay", 1000, 0.80)
                .withReputationSignals(null, null, null, 0.98, "seller");

        ProductCard enrichedTaobao = service.attachShopTrustReputation(List.of(taobao)).get(0);
        ProductCard enrichedPdd = service.attachShopTrustReputation(List.of(pddHigh)).get(0);
        ProductCard enrichedEbay = service.attachShopTrustReputation(List.of(ebay)).get(0);

        assertThat(service.shopTrustScore(taobao).shopOrSellerScore()).isCloseTo(0.96, offset(0.001));
        assertThat(service.shopTrustScore(taobao).confidence()).isEqualTo(0.75);
        assertThat(enrichedTaobao.reputationIndex()).isEqualTo(72);
        assertThat(enrichedTaobao.ratingDisplayLabel()).isEqualTo("店铺评分 4.8");

        assertThat(service.shopTrustScore(pddHigh).shopOrSellerScore()).isEqualTo(0.85);
        assertThat(service.shopTrustScore(pddHigh).confidence()).isEqualTo(0.60);
        assertThat(enrichedPdd.reputationIndex()).isEqualTo(51);
        assertThat(enrichedPdd.ratingDisplayLabel()).isEqualTo("店铺口碑 高");

        assertThat(service.shopTrustScore(ebay).shopOrSellerScore()).isEqualTo(0.98);
        assertThat(service.shopTrustScore(ebay).confidence()).isEqualTo(0.70);
        assertThat(enrichedEbay.reputationIndex()).isEqualTo(69);
        assertThat(enrichedEbay.ratingDisplayLabel()).isEqualTo("卖家信誉 98%");
    }

    @Test
    void shopTrustRankUsesConfiguredFormula() {
        ProductCard taobao = product("tb-shop", "taobao shop", 4.8, "shop_dsr", "taobao", 1000, 0.80)
                .withReputationSignals(null, 0.96, null, null, "shop_dsr");
        ProductCard lowerSales = product("other", "other shop", 4.5, "shop_dsr", "taobao", 100, 0.50)
                .withReputationSignals(null, 0.90, null, null, "shop_dsr");

        ReputationScore score = service.shopTrustScore(taobao, List.of(taobao, lowerSales));

        // (0.96 * 0.75) * 0.85 + 0.80 * 0.10 = 0.692
        assertThat(score.score()).isCloseTo(0.692, offset(0.001));
        assertThat(score.shopOrSellerScore()).isCloseTo(0.96, offset(0.001));
        assertThat(score.confidence()).isEqualTo(0.75);
    }

    @Test
    void highSalesLowTrustDoesNotBeatLowSalesHighTrust() {
        ProductCard highSalesLowTrust = product("high-sales", "high sales low trust", 4.0, "shop_dsr", "taobao", 100_000, 0.80)
                .withReputationSignals(null, 0.60, null, null, "shop_dsr");
        ProductCard lowSalesHighTrust = product("low-sales", "low sales high trust", 4.8, "shop_dsr", "taobao", 10, 0.80)
                .withReputationSignals(null, 0.96, null, null, "shop_dsr");

        List<ProductCard> sorted = sortService.sortByKey(List.of(highSalesLowTrust, lowSalesHighTrust), "review_quality");

        assertThat(sorted).extracting(ProductCard::id)
                .containsExactly("low-sales", "high-sales");
    }

    @Test
    void reviewQualityRanksShopAndSellerTrustBeforeItemOnlyRating() {
        ProductCard pddHigh = product("pdd", "pdd trusted shop", 4.6, "shop_dsr", "pdd", 1000, 0.80)
                .withReputationSignals(null, 0.85, "high", null, "pdd_shop_level");
        ProductCard ebaySeller = product("ebay", "ebay trusted seller", 4.9, "seller", "eBay", 1000, 0.80)
                .withReputationSignals(null, null, null, 0.98, "seller");
        ProductCard taobaoItemOnly = product("tb-item", "taobao item only", 4.9, "item_rating", "taobao", 1000, 0.95)
                .withReputationSignals(4.9, null, null, null, "item_rating");

        List<ProductCard> sorted = sortService.sortByKey(List.of(taobaoItemOnly, pddHigh, ebaySeller), "review_quality");

        assertThat(sorted).extracting(ProductCard::id)
                .containsExactly("ebay", "pdd", "tb-item");
        assertThat(sorted.get(0).ratingDisplayLabel()).isEqualTo("卖家信誉 98%");
        assertThat(sorted.get(1).ratingDisplayLabel()).isNotBlank();
        assertThat(sorted.get(2).ratingDisplayLabel()).isNull();
    }

    @Test
    void reviewQualityKeepsStableOrderWhenTrustAndSimilarityTie() {
        ProductCard first = product("first", "first", 4.8, "shop_dsr", "taobao", 1, 0.80)
                .withReputationSignals(null, 0.96, null, null, "shop_dsr");
        ProductCard second = product("second", "second", 4.8, "shop_dsr", "taobao", 999_999, 0.80)
                .withReputationSignals(null, 0.96, null, null, "shop_dsr");

        List<ProductCard> sorted = sortService.sortByKey(List.of(first, second), "review_quality");

        assertThat(sorted).extracting(ProductCard::id)
                .containsExactly("first", "second");
    }

    @Test
    void pddLegacyMappedRatingIsReinterpretedAsCoarseShopLevel() {
        ProductCard pddHigh = product("pdd-high", "pdd high", 4.6, "shop_dsr", "pdd", 100, 0.80)
                .withReputationSignals(null, null, null, null, "shop_dsr");
        ProductCard pddMid = product("pdd-mid", "pdd mid", 4.0, "shop_dsr", "pdd", 100, 0.80)
                .withReputationSignals(null, null, null, null, "shop_dsr");
        ProductCard pddLow = product("pdd-low", "pdd low", 3.2, "shop_dsr", "pdd", 100, 0.80)
                .withReputationSignals(null, null, null, null, "shop_dsr");

        assertThat(service.shopTrustScore(pddHigh).shopOrSellerScore()).isEqualTo(0.85);
        assertThat(service.shopTrustScore(pddMid).shopOrSellerScore()).isEqualTo(0.60);
        assertThat(service.shopTrustScore(pddLow).shopOrSellerScore()).isEqualTo(0.30);
    }

    @Test
    void confidenceAndWeightsAreConfigurable() {
        VisionCartProperties.Search.Reputation config = new VisionCartProperties.Search.Reputation();
        config.setPddShopLevelConfidence(0.70);
        config.setTrustWeight(1.0);
        config.setRelevanceWeight(0.0);
        config.setSalesWeight(0.0);
        ProductReputationService configured = new ProductReputationService(config);
        ProductCard pddHigh = product("pdd-high", "pdd high", 4.6, "shop_dsr", "pdd", 100, 0.20)
                .withReputationSignals(null, 0.85, "high", null, "pdd_shop_level");

        ReputationScore score = configured.shopTrustScore(pddHigh, List.of(pddHigh));
        ProductCard enriched = configured.attachShopTrustReputation(List.of(pddHigh)).get(0);

        assertThat(score.confidence()).isEqualTo(0.70);
        assertThat(score.score()).isCloseTo(0.595, offset(0.001));
        assertThat(enriched.reputationIndex()).isEqualTo(60);
    }

    @Test
    void reviewQualitySortDiversifiesTopWindow() {
        List<ProductCard> products = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            products.add(product("pdd-" + i, "PDD " + i, 4.6, "shop_dsr", "pdd", 1000 - i, 0.95)
                    .withReputationSignals(null, 0.85, "high", null, "pdd_shop_level"));
        }
        for (int i = 0; i < 8; i++) {
            products.add(product("tb-" + i, "Taobao " + i, 4.8, "shop_dsr", "taobao", 200 - i, 0.80)
                    .withReputationSignals(4.9, 0.96, null, null, "shop_dsr"));
        }
        for (int i = 0; i < 8; i++) {
            products.add(product("ebay-" + i, "eBay " + i, 4.9, "seller", "eBay", 150 - i, 0.75)
                    .withReputationSignals(null, null, null, 0.98, "seller"));
        }

        List<ProductCard> sorted = sortService.sortByKey(products, "review_quality");

        long pddInTop20 = sorted.stream().limit(20)
                .filter(product -> "pdd".equals(product.platform()))
                .count();
        assertThat(pddInTop20).isLessThanOrEqualTo(6);
    }

    @Test
    void relevanceSortRestoresSimilarityDescendingOrder() {
        List<ProductCard> products = List.of(
                product("low", "low similarity", 0, "none", "taobao", 100, 0.20),
                product("high", "high similarity", 0, "none", "pdd", 100, 0.90),
                product("mid", "mid similarity", 0, "none", "eBay", 100, 0.55)
        );

        List<ProductCard> sorted = sortService.sortByKey(products, "relevance");

        assertThat(sorted).extracting(ProductCard::id)
                .containsExactly("high", "mid", "low");
    }

    private ProductCard product(String id, String title, double rating, String ratingSource,
                                String platform, long sales, double similarity) {
        return new ProductCard(id, title, "http://img.com/" + id + ".jpg",
                BigDecimal.valueOf(100), null, platform, false, "shop",
                rating, sales, similarity, List.of(), "http://detail.com/" + id,
                null, ratingSource, null);
    }
}
