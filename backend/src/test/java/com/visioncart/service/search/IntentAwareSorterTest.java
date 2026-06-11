package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.strategy.MixPolicy;
import com.visioncart.service.search.strategy.ResultMixer;
import com.visioncart.service.search.strategy.VerticalSearchStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentAwareSorterTest {

    private final IntentAwareSorter sorter = new IntentAwareSorter(new ProductSortService(new ProductReputationService()));

    @Test
    void reviewSortDoesNotPromoteRelatedAccessoryAboveExactMain() {
        ProductCard related = product("related", "\u5f15\u78c1\u7247", 5.0, 10_000);
        ProductCard exact = product("exact", "\u624b\u673a\u58f3", 4.0, 1);
        SearchFilter reviewSort = new SearchFilter(null, List.of(), null, List.of(), List.of(),
                null, "rating", "desc", null);

        List<VerticalSearchStrategy.ClassifiedProduct> sorted = sorter.sortClassified(List.of(
                new VerticalSearchStrategy.ClassifiedProduct(related, IntentGate.IntentTier.RELATED_ACCESSORY),
                new VerticalSearchStrategy.ClassifiedProduct(exact, IntentGate.IntentTier.EXACT_MAIN)
        ), reviewSort);
        List<ProductCard> mixed = ResultMixer.mix(sorted, MixPolicy.strictAccessory(), 20);

        assertThat(mixed).extracting(ProductCard::id).containsExactly("exact", "related");
    }

    @Test
    void resultMixerKeepsPlatformsVisibleWithinSameTier() {
        List<VerticalSearchStrategy.ClassifiedProduct> classified = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            classified.add(new VerticalSearchStrategy.ClassifiedProduct(
                    product("tb-" + i, "\u7535\u52a8\u5243\u987b\u5200" + i, 4.8, 1000, "\u6dd8\u5b9d"),
                    IntentGate.IntentTier.EXACT_MAIN));
        }
        for (int i = 0; i < 50; i++) {
            classified.add(new VerticalSearchStrategy.ClassifiedProduct(
                    product("pdd-" + i, "\u7535\u52a8\u5243\u987b\u5200" + i, 4.6, 1000, "\u62fc\u591a\u591a"),
                    IntentGate.IntentTier.EXACT_MAIN));
        }

        List<ProductCard> mixed = ResultMixer.mix(classified, MixPolicy.defaults(), 50);

        assertThat(mixed).hasSize(50);
        assertThat(mixed).extracting(ProductCard::platform)
                .contains("\u6dd8\u5b9d", "\u62fc\u591a\u591a");
    }

    @Test
    void photoRelevanceSortPromotesPowerBankMatchingPhotoSignalsWithinSameTier() {
        ProductIntent intent = new ProductIntent(
                "powerbank", "充电宝", "power_bank",
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "", "", "",
                false,
                Map.of("规格", "20Ah", "类目", "充电宝"),
                Map.of("款式", "透明外壳露电路板款"),
                List.of("黄色LED指示灯充电宝"),
                List.of(),
                List.of(), 0.9, "test"
        );
        ProductCard generic = product("generic", "普通20000mAh充电宝", 4.9, 10_000);
        ProductCard photoLike = product("photo", "20Ah透明外壳露电路板黄色LED指示灯充电宝", 4.0, 100);
        ProductCard component = product("component", "20Ah移动电源模块主板露电路板维修套件", 5.0, 20_000);

        List<VerticalSearchStrategy.ClassifiedProduct> sorted = sorter.sortClassified(List.of(
                new VerticalSearchStrategy.ClassifiedProduct(generic, IntentGate.IntentTier.EXACT_MAIN),
                new VerticalSearchStrategy.ClassifiedProduct(component, IntentGate.IntentTier.EXACT_MAIN),
                new VerticalSearchStrategy.ClassifiedProduct(photoLike, IntentGate.IntentTier.EXACT_MAIN)
        ), null, intent);

        assertThat(sorted).extracting(item -> item.product().id())
                .containsExactly("photo", "generic", "component");
    }

    private ProductCard product(String id, String title, double rating, long sales) {
        return product(id, title, rating, sales, "\u6dd8\u5b9d");
    }

    private ProductCard product(String id, String title, double rating, long sales, String platform) {
        return new ProductCard(
                id, title, "", BigDecimal.valueOf(99), null,
                platform, false, "shop", rating, sales, 0.8,
                List.of(), "", null, "item_rating", null
        );
    }
}
