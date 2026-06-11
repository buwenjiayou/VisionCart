package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.strategy.VerticalSearchStrategy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class IntentAwareSorter {

    private final ProductSortService productSortService;
    private final PhotoRelevanceScorer photoRelevanceScorer = new PhotoRelevanceScorer();

    public IntentAwareSorter(ProductSortService productSortService) {
        this.productSortService = productSortService;
    }

    public List<VerticalSearchStrategy.ClassifiedProduct> sortClassified(
            List<VerticalSearchStrategy.ClassifiedProduct> products,
            SearchFilter filter) {
        return sortClassified(products, filter, null);
    }

    public List<VerticalSearchStrategy.ClassifiedProduct> sortClassified(
            List<VerticalSearchStrategy.ClassifiedProduct> products,
            SearchFilter filter,
            ProductIntent intent) {
        if (products == null || products.size() <= 1) {
            return products == null ? List.of() : products;
        }
        List<VerticalSearchStrategy.ClassifiedProduct> sorted = new ArrayList<>();
        for (IntentGate.IntentTier tier : tierOrder()) {
            List<ProductCard> tierProducts = products.stream()
                    .filter(item -> item.tier() == tier)
                    .map(VerticalSearchStrategy.ClassifiedProduct::product)
                    .toList();
            for (ProductCard product : applyPhotoSort(productSortService.applySort(tierProducts, filter), intent)) {
                sorted.add(new VerticalSearchStrategy.ClassifiedProduct(product, tier));
            }
        }
        return sorted;
    }

    public List<ProductCard> sortProducts(List<ProductCard> products,
                                          ProductIntent intent,
                                          VerticalSearchStrategy strategy,
                                          SearchFilter filter) {
        if (products == null || products.size() <= 1 || intent == null || strategy == null) {
            return products == null ? List.of() : products;
        }
        List<VerticalSearchStrategy.ClassifiedProduct> classified = products.stream()
                .map(product -> new VerticalSearchStrategy.ClassifiedProduct(
                        product, strategy.classify(intent, product)))
                .toList();
        return sortClassified(classified, filter, intent).stream()
                .map(VerticalSearchStrategy.ClassifiedProduct::product)
                .toList();
    }

    private List<ProductCard> applyPhotoSort(List<ProductCard> products, ProductIntent intent) {
        if (intent == null || products == null || products.size() <= 1) {
            return products == null ? List.of() : products;
        }
        List<ProductCard> sorted = new ArrayList<>(products);
        sorted.sort(Comparator.comparingDouble(
                (ProductCard product) -> photoRelevanceScorer.score(intent, product)).reversed());
        return sorted;
    }

    private List<IntentGate.IntentTier> tierOrder() {
        return java.util.Arrays.stream(IntentGate.IntentTier.values())
                .sorted(Comparator.comparingInt(this::tierOrdinal))
                .toList();
    }

    private int tierOrdinal(IntentGate.IntentTier tier) {
        return switch (tier) {
            case EXACT_MAIN -> 0;
            case COMPATIBLE_MAIN -> 1;
            case SAME_FAMILY -> 2;
            case RELATED_ACCESSORY -> 3;
            case SUBSTITUTE -> 4;
            case REJECT -> 5;
        };
    }
}
