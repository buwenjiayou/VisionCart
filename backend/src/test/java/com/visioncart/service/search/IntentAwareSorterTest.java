package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.strategy.MixPolicy;
import com.visioncart.service.search.strategy.ResultMixer;
import com.visioncart.service.search.strategy.VerticalSearchStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

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
