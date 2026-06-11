package com.visioncart.service.search;

import com.visioncart.api.dto.PlatformPriceStat;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.service.search.strategy.ResultMixer;
import com.visioncart.service.search.strategy.VerticalSearchStrategy;
import com.visioncart.service.search.strategy.VerticalStrategyRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SearchRunService {
    private static final Logger log = LoggerFactory.getLogger(SearchRunService.class);
    private static final int REPUTATION_DIAGNOSTIC_WINDOW = 20;

    private final CandidateSessionCache sessionCache;
    private final CandidateFilterService filterService;
    private final ProductReputationService reputationService;
    private final ProductSortService productSortService;
    private final IntentAwareSorter intentAwareSorter;
    private final VerticalStrategyRegistry strategyRegistry;
    private final OverseasEnglishIntentMatcher overseasEnglishIntentMatcher;

    @Autowired
    public SearchRunService(CandidateSessionCache sessionCache,
                            CandidateFilterService filterService,
                            ProductReputationService reputationService,
                            ProductSortService productSortService,
                            VerticalStrategyRegistry strategyRegistry,
                            OverseasEnglishIntentMatcher overseasEnglishIntentMatcher) {
        this.sessionCache = sessionCache;
        this.filterService = filterService;
        this.reputationService = reputationService;
        this.productSortService = productSortService;
        this.intentAwareSorter = new IntentAwareSorter(productSortService);
        this.strategyRegistry = strategyRegistry;
        this.overseasEnglishIntentMatcher = overseasEnglishIntentMatcher;
    }

    SearchRunService(CandidateSessionCache sessionCache,
                     CandidateFilterService filterService,
                     ProductReputationService reputationService,
                     ProductSortService productSortService,
                     VerticalStrategyRegistry strategyRegistry) {
        this(sessionCache, filterService, reputationService, productSortService, strategyRegistry,
                new OverseasEnglishIntentMatcher());
    }

    public Optional<SearchResult> recomputeDisplayPage(String sessionId, SearchFilter filter, int pageSize) {
        return sessionCache.getClassifiedPool(sessionId)
                .map(pool -> recomputeDisplayPage(pool, filter, pageSize, true));
    }

    public SearchResult recomputeDisplayPage(SearchCandidatePool pool, SearchFilter filter, int pageSize,
                                             boolean persist) {
        SearchFilter effectiveFilter = filter != null ? filter : SearchFilter.empty();
        int safePageSize = Math.max(1, pageSize);

        List<VerticalSearchStrategy.ClassifiedProduct> filteredPool =
                applyFilterToClassifiedPool(pool.classifiedPool(), effectiveFilter);
        List<VerticalSearchStrategy.ClassifiedProduct> sorted =
                sortClassifiedForRegion(pool.isDomestic(), filteredPool, effectiveFilter, pool.productIntent());
        VerticalSearchStrategy strategy = strategyRegistry.resolve(pool.productIntent());
        List<ProductCard> displayPage = mixForRegion(
                pool.isDomestic(), strategy, pool.productIntent(), sorted, safePageSize);
        displayPage = enrichRatingLabels(displayPage, effectiveFilter);
        displayPage = diversifyPlatforms(displayPage, safePageSize);
        logReputationRerankSummary(effectiveFilter, filteredPool, displayPage);

        SearchCandidatePool updatedPool = pool.withCurrentState(effectiveFilter, displayPage);
        if (persist) {
            sessionCache.saveClassifiedPool(pool.sessionId(), updatedPool);
            sessionCache.saveCandidates(pool.sessionId(), updatedPool.toProductCards(), pool.searchIdentity());
        }

        return new SearchResult(
                filteredPool.size(),
                displayPage,
                stats(displayPage),
                List.of(),
                false,
                pool.searchRunId()
        );
    }

    public void restoreRunPool(String sessionId, SearchCandidatePool pool) {
        if (sessionId == null || sessionId.isBlank() || pool == null) {
            return;
        }
        sessionCache.saveClassifiedPool(sessionId, pool);
        sessionCache.saveCandidates(sessionId, pool.toProductCards(), pool.searchIdentity());
    }

    private List<VerticalSearchStrategy.ClassifiedProduct> applyFilterToClassifiedPool(
            List<VerticalSearchStrategy.ClassifiedProduct> pool,
            SearchFilter filter) {
        if (pool == null || pool.isEmpty()) {
            return List.of();
        }
        List<ProductCard> products = pool.stream()
                .map(VerticalSearchStrategy.ClassifiedProduct::product)
                .toList();
        List<ProductCard> filtered = filterService.applyFilter(products, filter);
        java.util.Set<String> filteredIds = filtered.stream()
                .map(ProductCard::id)
                .collect(Collectors.toSet());
        return pool.stream()
                .filter(cp -> filteredIds.contains(cp.product().id()))
                .toList();
    }

    private List<VerticalSearchStrategy.ClassifiedProduct> sortClassifiedForRegion(
            boolean domestic,
            List<VerticalSearchStrategy.ClassifiedProduct> classified,
            SearchFilter filter,
            ProductIntent intent) {
        if (domestic) {
            return intentAwareSorter.sortClassified(classified, filter, intent);
        }
        if (classified == null || classified.size() <= 1) {
            return classified == null ? List.of() : classified;
        }
        List<VerticalSearchStrategy.ClassifiedProduct> sorted = new ArrayList<>();
        for (IntentGate.IntentTier tier : tierOrder()) {
            List<ProductCard> tierProducts = classified.stream()
                    .filter(item -> item.tier() == tier)
                    .map(VerticalSearchStrategy.ClassifiedProduct::product)
                    .toList();
            List<ProductCard> userSorted = productSortService.applySort(tierProducts, filter);
            for (ProductCard product : overseasEnglishIntentMatcher.sortWithinTier(intent, userSorted)) {
                sorted.add(new VerticalSearchStrategy.ClassifiedProduct(product, tier));
            }
        }
        return sorted;
    }

    private List<ProductCard> mixForRegion(
            boolean domestic,
            VerticalSearchStrategy strategy,
            ProductIntent intent,
            List<VerticalSearchStrategy.ClassifiedProduct> classified,
            int pageSize) {
        if (domestic) {
            return strategy.mix(intent, classified, pageSize);
        }
        return ResultMixer.mix(classified, strategy.policy(intent), pageSize);
    }

    private List<ProductCard> enrichRatingLabels(List<ProductCard> products, SearchFilter filter) {
        return reputationService.attachReputation(products);
    }

    private boolean isReputationSort(SearchFilter filter) {
        if (filter == null || filter.sortBy() == null) {
            return false;
        }
        return switch (filter.sortBy()) {
            case "rating", "reviews", "review_quality", "rating_desc", "shop_trust", "seller_trust" -> true;
            default -> false;
        };
    }

    private List<ProductCard> diversifyPlatforms(List<ProductCard> products, int pageSize) {
        List<ProductCard> page = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        Map<String, List<ProductCard>> byPlatform = products.stream()
                .collect(Collectors.groupingBy(ProductCard::platform, LinkedHashMap::new, Collectors.toList()));

        byPlatform.values().forEach(platformProducts -> {
            if (!platformProducts.isEmpty() && page.size() < pageSize) {
                page.add(platformProducts.get(0));
                seenIds.add(platformProducts.get(0).id());
            }
        });

        products.stream()
                .filter(product -> seenIds.add(product.id()))
                .limit(Math.max(0, pageSize - page.size()))
                .forEach(page::add);
        return page;
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

    private void logReputationRerankSummary(SearchFilter filter,
                                            List<VerticalSearchStrategy.ClassifiedProduct> filteredPool,
                                            List<ProductCard> displayPage) {
        if (!isReputationSort(filter)) {
            return;
        }
        List<ProductCard> candidates = filteredPool == null ? List.of() : filteredPool.stream()
                .map(VerticalSearchStrategy.ClassifiedProduct::product)
                .toList();
        Map<String, Long> candidatePlatforms = platformCounts(candidates.stream());
        Map<String, Long> trustedPlatforms = platformCounts(candidates.stream()
                .filter(reputationService::hasShopOrSellerTrust));
        Map<String, Long> top20Platforms = platformCounts((displayPage == null ? List.<ProductCard>of() : displayPage)
                .stream()
                .limit(REPUTATION_DIAGNOSTIC_WINDOW));
        log.info("SearchRun reputation rerank: sortBy={}, candidates={}, trusted={}, top20={}",
                filter.sortBy(), candidatePlatforms, trustedPlatforms, top20Platforms);
    }

    private Map<String, Long> platformCounts(java.util.stream.Stream<ProductCard> products) {
        return products.collect(Collectors.groupingBy(
                product -> product.platform() == null || product.platform().isBlank() ? "unknown" : product.platform(),
                LinkedHashMap::new,
                Collectors.counting()));
    }

    private List<PlatformPriceStat> stats(List<ProductCard> products) {
        return products.stream()
                .collect(Collectors.groupingBy(ProductCard::platform))
                .entrySet()
                .stream()
                .map(entry -> {
                    BigDecimal min = entry.getValue().stream().map(ProductCard::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                    BigDecimal total = entry.getValue().stream().map(ProductCard::price).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal avg = total.divide(BigDecimal.valueOf(entry.getValue().size()), 2, RoundingMode.HALF_UP);
                    return new PlatformPriceStat(entry.getKey(), min, avg, entry.getValue().size());
                })
                .sorted(Comparator.comparing(PlatformPriceStat::minPrice))
                .toList();
    }
}
