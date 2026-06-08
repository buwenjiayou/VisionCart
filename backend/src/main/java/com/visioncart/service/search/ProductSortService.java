package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.ReputationScore;
import com.visioncart.api.dto.SearchFilter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized product sorting service.
 * All sort paths (CandidateFilterService, FilterExecutionService, SuggestionService,
 * SearchOrchestrator) must use this service to ensure consistent behavior.
 */
@Service
public class ProductSortService {

    private static final int REVIEW_SORT_TOP_WINDOW = 20;
    private static final int REVIEW_SORT_MAX_PER_PLATFORM = 6;
    private static final int DISPLAY_SORT_TOP_WINDOW = 50;

    private final ProductReputationService reputationService;

    public ProductSortService(ProductReputationService reputationService) {
        this.reputationService = reputationService;
    }

    /**
     * Legacy rating comparator: has-rating first, then rating desc, then similarity desc, then sales desc.
     * Kept for backward compatibility. Prefer {@code sortByKey("review_quality")} for new code.
     */
    public static final Comparator<ProductCard> RATING_DESC = Comparator
            .<ProductCard, Boolean>comparing(p -> p.rating() > 0.0).reversed()
            .thenComparing(Comparator.comparingDouble(ProductCard::rating).reversed())
            .thenComparing(Comparator.comparingDouble(ProductCard::similarity).reversed())
            .thenComparing(Comparator.comparingLong(ProductCard::sales).reversed());

    public static final Comparator<ProductCard> PRICE_ASC = Comparator
            .comparing(ProductCard::price, Comparator.nullsLast(Comparator.naturalOrder()));

    public static final Comparator<ProductCard> PRICE_DESC = Comparator
            .comparing(ProductCard::price, Comparator.nullsLast(Comparator.reverseOrder()));

    public static final Comparator<ProductCard> SALES_DESC = Comparator
            .comparingLong(ProductCard::sales).reversed();

    /**
     * Sort products by the given sort key string.
     * Used by FilterExecutionService and SuggestionService.
     *
     * @param products the products to sort
     * @param sortKey  one of: "review_quality", "rating_desc", "price_asc", "price_desc", "sales_desc", "value_score"
     * @return sorted copy of the list
     */
    public List<ProductCard> sortByKey(List<ProductCard> products, String sortKey) {
        if (sortKey == null || products == null || products.size() <= 1) {
            return products == null ? List.of() : products;
        }
        List<ProductCard> sorted = new ArrayList<>(products);

        if ("review_quality".equals(sortKey)
                || "rating_desc".equals(sortKey)
                || "shop_trust".equals(sortKey)
                || "seller_trust".equals(sortKey)) {
            // Use shop/seller trust scoring for reputation sort.
            // IdentityHashMap avoids collisions when product IDs are null/blank/duplicate
            Map<ProductCard, ReputationScore> scoreCache = new IdentityHashMap<>();
            for (ProductCard p : products) {
                scoreCache.put(p, reputationService.shopTrustScore(p, products));
            }
            sorted.sort((a, b) -> {
                ReputationScore sa = scoreCache.getOrDefault(a, ReputationScore.EMPTY);
                ReputationScore sb = scoreCache.getOrDefault(b, ReputationScore.EMPTY);
                int trustCmp = Boolean.compare(sb.confidence() > 0, sa.confidence() > 0);
                if (trustCmp != 0) {
                    return trustCmp;
                }
                int scoreCmp = Double.compare(sb.score(), sa.score());
                if (scoreCmp != 0) {
                    return scoreCmp;
                }
                return Double.compare(b.similarity(), a.similarity());
            });
            return reputationService.attachShopTrustReputation(diversifyForReviewSort(sorted));
        }

        sorted.sort(switch (sortKey) {
            case "relevance" -> Comparator.comparingDouble(ProductCard::similarity).reversed();
            case "price_asc" -> PRICE_ASC;
            case "price_desc" -> PRICE_DESC;
            case "sales_desc" -> SALES_DESC;
            case "value_score" -> Comparator.comparingDouble(this::valueScore).reversed();
            default -> (a, b) -> 0; // relevance — keep original order
        });
        return diversifyDisplayWindow(sorted, DISPLAY_SORT_TOP_WINDOW, displayMaxPerPlatform(sorted));
    }

    /**
     * Sort products by SearchFilter's sortBy/sortOrder fields.
     * Used by CandidateFilterService and SearchOrchestrator.
     *
     * @param products the products to sort
     * @param filter   the filter containing sortBy and sortOrder
     * @return sorted copy of the list
     */
    public List<ProductCard> applySort(List<ProductCard> products, SearchFilter filter) {
        if (filter == null || products == null || products.size() <= 1) {
            return products == null ? List.of() : products;
        }
        String sortBy = filter.sortBy();
        if (sortBy == null || sortBy.isBlank()) {
            return products;
        }
        String sortOrder = filter.sortOrder() == null ? "desc" : filter.sortOrder();

        return switch (sortBy) {
            case "price" -> {
                Comparator<ProductCard> cmp = "asc".equalsIgnoreCase(sortOrder) ? PRICE_ASC : PRICE_DESC;
                yield products.stream().sorted(cmp).toList();
            }
            case "sales" -> products.stream().sorted(SALES_DESC).toList();
            case "rating", "reviews", "review_quality", "rating_desc", "shop_trust", "seller_trust" ->
                    sortByKey(products, "review_quality");
            default -> products;
        };
    }

    private double valueScore(ProductCard product) {
        double price = product.price() != null ? product.price().doubleValue() : 1;
        double rating = product.rating();
        long sales = product.sales();
        if (price <= 0) return 0;
        return (rating * 2 + Math.log1p(sales)) / Math.log1p(price);
    }

    private List<ProductCard> diversifyForReviewSort(List<ProductCard> sorted) {
        return diversifyDisplayWindow(sorted, REVIEW_SORT_TOP_WINDOW, REVIEW_SORT_MAX_PER_PLATFORM);
    }

    private int displayMaxPerPlatform(List<ProductCard> products) {
        long platformCount = products.stream()
                .map(product -> product.platform() == null ? "" : product.platform())
                .distinct()
                .count();
        if (platformCount <= 1) {
            return DISPLAY_SORT_TOP_WINDOW;
        }
        return Math.max(8, (int) Math.ceil((double) DISPLAY_SORT_TOP_WINDOW / platformCount));
    }

    private List<ProductCard> diversifyDisplayWindow(List<ProductCard> sorted, int windowSize, int maxPerPlatform) {
        if (sorted == null || sorted.size() <= 1 || windowSize <= 0 || maxPerPlatform <= 0) {
            return sorted == null ? List.of() : sorted;
        }
        long platformCount = sorted.stream()
                .map(product -> product.platform() == null ? "" : product.platform())
                .distinct()
                .count();
        if (platformCount <= 1) {
            return sorted;
        }

        Map<String, Integer> counts = new java.util.HashMap<>();
        List<ProductCard> topWindow = new ArrayList<>();
        List<ProductCard> remainder = new ArrayList<>();

        for (ProductCard product : sorted) {
            String platform = product.platform() == null ? "" : product.platform();
            if (topWindow.size() >= windowSize) {
                remainder.add(product);
                continue;
            }

            boolean platformFull = counts.getOrDefault(platform, 0) >= maxPerPlatform;
            if (platformFull) {
                remainder.add(product);
                continue;
            }

            topWindow.add(product);
            counts.merge(platform, 1, Integer::sum);
        }

        topWindow.addAll(roundRobinByPlatform(remainder));
        return topWindow;
    }

    private List<ProductCard> roundRobinByPlatform(List<ProductCard> products) {
        Map<String, List<ProductCard>> buckets = new java.util.LinkedHashMap<>();
        for (ProductCard product : products) {
            String platform = product.platform() == null ? "" : product.platform();
            buckets.computeIfAbsent(platform, ignored -> new ArrayList<>()).add(product);
        }

        List<ProductCard> result = new ArrayList<>();
        boolean added;
        do {
            added = false;
            for (List<ProductCard> bucket : buckets.values()) {
                if (!bucket.isEmpty()) {
                    result.add(bucket.remove(0));
                    added = true;
                }
            }
        } while (added);
        return result;
    }
}
