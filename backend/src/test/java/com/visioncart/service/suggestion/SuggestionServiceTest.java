package com.visioncart.service.suggestion;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.api.dto.SuggestionExecuteResult;
import com.visioncart.config.VisionCartProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionServiceTest {
    private SuggestionService service;

    @BeforeEach
    void setUp() {
        service = new SuggestionService(new VisionCartProperties(), null, null, null, new com.visioncart.service.search.ProductSortService(new com.visioncart.service.search.ProductReputationService()));
    }

    @Test
    void filterMainProductPersistsExcludeRolesInFilter() {
        List<ProductCard> products = List.of(
                productWithRole("1", "保温杯", "main", "淘宝", 100),
                productWithRole("2", "杯套", "accessory", "淘宝", 20),
                productWithRole("3", "杯盖", "case", "淘宝", 15),
                productWithRole("4", "滤网", "part", "淘宝", 10)
        );

        SuggestionExecuteResult result = service.execute("sess1", "filter_main_product", products, SearchFilter.empty());

        // Filter should have excludeRoles populated
        assertThat(result.updatedFilter()).isNotNull();
        assertThat(result.updatedFilter().excludeRoles())
                .contains("accessory", "case", "part", "consumable", "storage");
        // Products should be filtered to main only
        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).id()).isEqualTo("1");
        // Toast should indicate success
        assertThat(result.toast()).contains("排除配件");
    }

    @Test
    void excludeRolesPreservedAfterSubsequentSortAction() {
        // First apply filter_main_product, then sort — excludeRoles should survive
        List<ProductCard> products = List.of(
                productWithRole("1", "保温杯A", "main", "淘宝", 200),
                productWithRole("2", "保温杯B", "main", "拼多多", 100),
                productWithRole("3", "杯套", "accessory", "淘宝", 20)
        );

        SuggestionExecuteResult step1 = service.execute("sess2", "filter_main_product", products, SearchFilter.empty());
        assertThat(step1.updatedFilter().excludeRoles()).isNotEmpty();

        // Now apply sort on the result
        SuggestionExecuteResult step2 = service.execute("sess2", "sort_by_price_asc",
                step1.products(), step1.updatedFilter());

        // excludeRoles should survive the sort
        assertThat(step2.updatedFilter().excludeRoles())
                .contains("accessory", "case", "part", "consumable", "storage");
    }

    @Test
    void doesNotGenerateHistoryPriceCard() {
        List<SuggestionCard> cards = service.cards("app", List.of(
                product("1", "Nike Pegasus 41", "淘宝", 399, false, "Nike"),
                product("2", "Nike Pegasus 41", "拼多多", 299, false, "Nike")
        ));

        assertThat(cards)
                .noneMatch(card -> card.action().toLowerCase().contains("history"))
                .noneMatch(card -> card.title().contains("历史价格"));
    }

    @Test
    void generatesLowPriceCardWhenPriceSpreadIsUseful() {
        List<SuggestionCard> cards = service.cards("app", List.of(
                product("1", "罗技 M650 鼠标", "淘宝", 199, false, "Logitech"),
                product("2", "罗技 M650 鼠标", "拼多多", 129, false, "Logitech")
        ));

        assertThat(cards)
                .extracting(SuggestionCard::action)
                .contains("sort_by_price_asc");
        SuggestionCard lowPrice = cards.stream()
                .filter(card -> "sort_by_price_asc".equals(card.action()))
                .findFirst()
                .orElseThrow();
        assertThat(lowPrice.metric()).contains("¥129");
    }

    @Test
    void generatesOfficialCardOnlyWhenOfficialProductsExist() {
        List<SuggestionCard> withOfficial = service.cards("app", List.of(
                product("1", "Apple AirPods 官方", "淘宝", 999, true, "Apple"),
                product("2", "Apple AirPods", "拼多多", 899, false, "Apple")
        ));
        List<SuggestionCard> withoutOfficial = service.cards("app", List.of(
                product("3", "Apple AirPods", "淘宝", 999, false, "Apple")
        ));

        assertThat(withOfficial).extracting(SuggestionCard::action).contains("filter_self_operated");
        assertThat(withoutOfficial).extracting(SuggestionCard::action).doesNotContain("filter_self_operated");
    }

    @Test
    void generatesBrandAndColorAttributeCardsWhenTheyCanNarrowResults() {
        List<SuggestionCard> cards = service.cards(
                "app",
                List.of(
                        product("1", "Nike Pegasus 41 白色跑鞋", "淘宝", 499, false, "Nike"),
                        product("2", "Adidas 白色运动鞋", "拼多多", 399, false, "Adidas"),
                        product("3", "Nike Pegasus 41 黑色跑鞋", "淘宝", 459, false, "Nike")
                ),
                Map.of("品牌", "Nike", "颜色", "白色"),
                SearchFilter.empty()
        );

        assertThat(cards).extracting(SuggestionCard::action)
                .anyMatch(action -> action.startsWith("filter_brand:"))
                .anyMatch(action -> action.startsWith("filter_color:"));
    }

    @Test
    void executeSortActionsReturnUpdatedFilterAndSortedProducts() {
        List<ProductCard> products = List.of(
                product("1", "高价", "淘宝", 299, false, null, 4.6, 10),
                product("2", "低价", "拼多多", 99, false, null, 4.9, 200)
        );

        SuggestionExecuteResult priceResult = service.execute("s1", "sort_by_price_asc", products, SearchFilter.empty());
        assertThat(priceResult.products()).extracting(ProductCard::title).containsExactly("低价", "高价");
        assertThat(priceResult.updatedFilter().sortBy()).isEqualTo("price");
        assertThat(priceResult.updatedFilter().sortOrder()).isEqualTo("asc");

        SuggestionExecuteResult salesResult = service.execute("s1", "sort_by_sales_desc", products, SearchFilter.empty());
        assertThat(salesResult.products()).extracting(ProductCard::title).containsExactly("低价", "高价");
        assertThat(salesResult.updatedFilter().sortBy()).isEqualTo("sales");
        assertThat(salesResult.updatedFilter().sortOrder()).isEqualTo("desc");
    }

    @Test
    void cardActionsAreDistinctAndCapped() {
        List<SuggestionCard> cards = service.cards(
                "app",
                List.of(
                        product("1", "Nike 白色跑鞋 优惠券", "淘宝", 599, true, "Nike"),
                        product("2", "Nike 白色跑鞋", "拼多多", 299, false, "Nike"),
                        product("3", "Adidas 黑色跑鞋", "淘宝", 399, false, "Adidas"),
                        product("4", "Nike Pegasus 41 白色跑鞋", "天猫", 499, true, "Nike")
                ),
                Map.of("品牌", "Nike", "颜色", "白色", "型号", "Pegasus 41"),
                SearchFilter.empty()
        );

        assertThat(cards).hasSizeLessThanOrEqualTo(6);
        assertThat(cards.stream().map(SuggestionCard::action).distinct().count()).isEqualTo(cards.size());
    }

    @Test
    void generatesFreeShippingCardWhenProductsHaveShippingTag() {
        List<SuggestionCard> cards = service.cards("app", List.of(
                productWithTags("1", "运动鞋 包邮", "淘宝", 299, List.of("包邮", "正品")),
                productWithTags("2", "运动鞋", "拼多多", 199, List.of("热销"))
        ));

        assertThat(cards).extracting(SuggestionCard::action).contains("filter_free_shipping");
    }

    @Test
    void doesNotGenerateFreeShippingCardWhenAllProductsHaveShipping() {
        List<SuggestionCard> cards = service.cards("app", List.of(
                productWithTags("1", "运动鞋 包邮", "淘宝", 299, List.of("包邮")),
                productWithTags("2", "运动鞋 包邮", "拼多多", 199, List.of("免运费"))
        ));

        assertThat(cards).extracting(SuggestionCard::action).doesNotContain("filter_free_shipping");
    }

    @Test
    void generatesBestValueCardWhenEnoughPricedProducts() {
        List<SuggestionCard> cards = service.cards("app", List.of(
                product("1", "高价好评", "淘宝", 599, false, null, 4.9, 500),
                product("2", "中价中评", "拼多多", 399, false, null, 4.5, 100),
                product("3", "低价高销", "京东", 199, false, null, 4.2, 2000)
        ));

        assertThat(cards).extracting(SuggestionCard::action).contains("highlight_best_value");
    }

    @Test
    void doesNotGenerateBestValueCardWithFewerThanThreeProducts() {
        List<SuggestionCard> cards = service.cards("app", List.of(
                product("1", "商品A", "淘宝", 299, false, null, 4.8, 100),
                product("2", "商品B", "拼多多", 199, false, null, 4.5, 200)
        ));

        assertThat(cards).extracting(SuggestionCard::action).doesNotContain("highlight_best_value");
    }

    @Test
    void generatesPriceAlertCardWhenEnoughPricedProducts() {
        List<SuggestionCard> cards = service.cards("app", List.of(
                product("1", "商品A", "淘宝", 299, false, null),
                product("2", "商品B", "拼多多", 199, false, null)
        ));

        assertThat(cards).extracting(SuggestionCard::action).contains("action_set_price_alert");
    }

    @Test
    void doesNotGenerateSimilarStyleCard() {
        List<SuggestionCard> cards = service.cards("app", List.of(
                product("1", "商品A", "淘宝", 299, false, null),
                product("2", "商品B", "拼多多", 199, false, null)
        ));

        assertThat(cards).extracting(SuggestionCard::action).doesNotContain("search_similar_style");
    }

    @Test
    void undoRestoresFilterAndProvidesNewCards() {
        List<ProductCard> products = List.of(
                product("1", "高价", "淘宝", 299, false, null, 4.6, 10),
                product("2", "低价", "拼多多", 99, false, null, 4.9, 200)
        );

        // Execute sort by price
        SuggestionExecuteResult execResult = service.execute("undo-test", "sort_by_price_asc", products, SearchFilter.empty());
        assertThat(execResult.updatedFilter().sortBy()).isEqualTo("price");

        // Undo
        SuggestionExecuteResult undoResult = service.undo("undo-test", execResult.products());
        assertThat(undoResult.toast()).isEqualTo("已撤销");
        assertThat(undoResult.updatedFilter().sortBy()).isNull();
        assertThat(undoResult.cards()).isNotEmpty();
    }

    private ProductCard productWithTags(String id, String title, String platform, int price, List<String> tags) {
        return new ProductCard(
                id, title, "", BigDecimal.valueOf(price), BigDecimal.valueOf(price + 50L),
                platform, false, "精选店铺", 4.5, 100, 0.9,
                tags, "https://example.com/" + id, null, "shop_dsr", "100 销量"
        );
    }

    private ProductCard product(String id, String title, String platform, int price, boolean selfOperated, String brand) {
        return product(id, title, platform, price, selfOperated, brand, 4.8, 100);
    }

    private ProductCard product(String id, String title, String platform, int price, boolean selfOperated,
                                String brand, double rating, long sales) {
        return new ProductCard(
                id,
                title,
                "",
                BigDecimal.valueOf(price),
                BigDecimal.valueOf(price + 100L),
                platform,
                selfOperated,
                selfOperated ? "官方旗舰店" : "精选店铺",
                rating,
                sales,
                0.9,
                title.contains("优惠") ? List.of("优惠券") : List.of(),
                "https://example.com/" + id,
                brand,
                rating > 0 ? "shop_dsr" : "none",
                sales + " 销量"
        );
    }

    @Test
    void filterPriceBandFiltersProductsInRange() {
        List<ProductCard> products = List.of(
                product("1", "低价", "淘宝", 50, false, null),
                product("2", "中价A", "淘宝", 80, false, null),
                product("3", "中价B", "淘宝", 100, false, null),
                product("4", "中价C", "淘宝", 120, false, null),
                product("5", "高价", "淘宝", 200, false, null)
        );

        SuggestionExecuteResult result = service.execute("s1", "filter_price_band:80:130", products, SearchFilter.empty());

        assertThat(result.products()).extracting(ProductCard::title)
                .containsExactly("中价A", "中价B", "中价C");
        assertThat(result.updatedFilter().priceRange().min()).isEqualTo(80.0);
        assertThat(result.updatedFilter().priceRange().max()).isEqualTo(130.0);
    }

    @Test
    void filterMainProductExcludesAccessoryRoles() {
        List<ProductCard> products = List.of(
                productWithRole("1", "主体商品", "main"),
                productWithRole("2", "杯套", "accessory"),
                productWithRole("3", "杯盖", "case"),
                productWithRole("4", "保护壳", "part"),
                productWithRole("5", "另一个主体", "unknown")
        );

        SuggestionExecuteResult result = service.execute("s1", "filter_main_product", products, SearchFilter.empty());

        assertThat(result.products()).extracting(ProductCard::title)
                .containsExactly("主体商品", "另一个主体");
    }

    private ProductCard productWithRole(String id, String title, String role) {
        return new ProductCard(
                id, title, "", BigDecimal.valueOf(100), BigDecimal.valueOf(150),
                "淘宝", false, "精选店铺", 4.5, 100, 0.9,
                List.of(), "https://example.com/" + id, null, "none", null, "", role
        );
    }

    private ProductCard productWithRole(String id, String title, String role, String platform, int price) {
        return new ProductCard(
                id, title, "", BigDecimal.valueOf(price), BigDecimal.valueOf(price + 50),
                platform, false, "精选店铺", 4.5, 100, 0.9,
                List.of(), "https://example.com/" + id, null, "none", null, "", role
        );
    }
}
