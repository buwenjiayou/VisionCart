package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Standalone filter service that operates on pre-fetched candidate lists.
 * Extracted from SearchOrchestrator to support session-based NLP filtering.
 */
@Service
public class CandidateFilterService {

    private static final Logger log = LoggerFactory.getLogger(CandidateFilterService.class);

    private final RelevanceRanker ranker;
    private final ProductValidityFilter validityFilter;
    private final com.visioncart.service.metrics.PerformanceMetricsService metricsService;
    private final ProductSortService productSortService;

    public CandidateFilterService(RelevanceRanker ranker, ProductValidityFilter validityFilter,
                                  com.visioncart.service.metrics.PerformanceMetricsService metricsService,
                                  ProductSortService productSortService) {
        this.ranker = ranker;
        this.validityFilter = validityFilter;
        this.metricsService = metricsService;
        this.productSortService = productSortService;
    }

    /**
     * Result of filtering candidates.
     */
    public record FilterResult(
            List<ProductCard> products,
            int totalInPool,
            int resultCount,
            boolean needExpand,
            boolean needRelaxHint
    ) {}

    /**
     * Full filter pipeline: validity → dedup → applyFilter → applyIntentFilter → applySort → count → paginate.
     */
    public FilterResult filter(List<ProductCard> candidates, SearchFilter filter,
                               Map<String, String> attributes, int pageSize, int page) {
        if (candidates == null || candidates.isEmpty()) {
            return new FilterResult(List.of(), 0, 0, false, false);
        }
        io.micrometer.core.instrument.Timer.Sample filterTimer = metricsService.startCandidateFilterTimer();
        int inputCount = candidates.size();
        int resultCount = 0;
        try {
            // Step 0: Filter out invalid products (下架/无库存/价格无效)
            List<ProductCard> validCandidates = validityFilter.filterValid(candidates);
            int totalInPool = validCandidates.size();

            // Step 0b: Deduplicate (same title+price or same id)
            List<ProductCard> deduped = deduplicate(validCandidates);

            SearchIntent intent = SearchIntent.from(attributes, filter);

            // Step 1: Apply structured filters (price, platform, keyword, color, brand, etc.)
            List<ProductCard> afterFilter = applyFilter(deduped, filter);

            // Step 2: Apply intent-based relevance filtering
            List<ProductCard> afterIntent = applyIntentFilter(afterFilter, intent, pageSize);

            // Step 3: Apply sort
            List<ProductCard> sorted = applySort(afterIntent, filter);

            // Step 4: Count AFTER dedup + validity + filter + intent + sort
            resultCount = sorted.size();

            // Step 5: Determine if expansion or relaxation is needed
            boolean needExpand = resultCount < 5;
            boolean needRelaxHint = resultCount >= 5 && resultCount < 20;

            // Step 6: Paginate (page is 1-based, guard against invalid values)
            int safePage = Math.max(1, page);
            int safePageSize = Math.max(1, pageSize);
            int fromIndex = Math.min((safePage - 1) * safePageSize, sorted.size());
            int toIndex = Math.min(fromIndex + safePageSize, sorted.size());
            List<ProductCard> paginated = sorted.subList(fromIndex, toIndex);

            log.info("Candidate filter: {} total -> {} valid -> {} deduped -> {} filtered -> {} intent -> {} sorted (count={})",
                    candidates.size(), totalInPool, deduped.size(), afterFilter.size(), afterIntent.size(), sorted.size(), resultCount);

            return new FilterResult(paginated, totalInPool, resultCount, needExpand, needRelaxHint);
        } catch (Exception e) {
            log.error("Candidate filter failed for {} candidates: {}", inputCount, e.getMessage());
            throw e;
        } finally {
            metricsService.stopCandidateFilterTimer(filterTimer, inputCount, resultCount);
        }
    }

    /**
     * Prepare a semantic pool for capability/preference evaluation.
     * Pipeline: validity → dedup → structured filter → sort → limit.
     * Deliberately SKIPS applyIntentFilter() because intent-based relevance filtering
     * would remove normal products that don't match semantic keywords like "可以带上飞机".
     * Capability evaluators need to see ALL structurally valid products, not just keyword-matched ones.
     */
    public List<ProductCard> prepareSemanticPool(List<ProductCard> candidates, SearchFilter baseFilter, int limit) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        List<ProductCard> valid = validityFilter.filterValid(candidates);
        List<ProductCard> deduped = deduplicate(valid);
        List<ProductCard> afterFilter = applyFilter(deduped, baseFilter);
        List<ProductCard> sorted = applySort(afterFilter, baseFilter);

        log.info("Semantic pool: {} candidates -> {} valid -> {} deduped -> {} filtered -> {} sorted (limit={})",
                candidates.size(), valid.size(), deduped.size(), afterFilter.size(), sorted.size(), limit);

        return sorted.stream().limit(limit).toList();
    }

    /**
     * Deduplicate products by id or title+price+platform+image.
     * Blank ids are treated as missing to avoid collapsing distinct products.
     */
    private List<ProductCard> deduplicate(List<ProductCard> products) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        return products.stream()
                .filter(p -> {
                    String key = StringUtils.isNotBlank(p.id())
                            ? p.id()
                            : StringUtils.defaultString(p.title()) + "|"
                                + p.price() + "|"
                                + StringUtils.defaultString(p.platform()) + "|"
                                + StringUtils.defaultString(p.imageUrl());
                    return seen.add(key);
                })
                .toList();
    }

    /**
     * Apply structured filters: price range, platform, self-operated, rating, keyword, colors, brands.
     */
    public List<ProductCard> applyFilter(List<ProductCard> products, SearchFilter filter) {
        if (filter == null) {
            return products;
        }
        String positiveKeyword = SearchTextUtils.positiveKeyword(filter.keyword());
        List<String> negativeTerms = SearchTextUtils.negativeTerms(filter.keyword());
        return products.stream()
                .filter(product -> filter.platforms() == null || filter.platforms().isEmpty()
                        || filter.platforms().contains(product.platform()))
                .filter(product -> filter.selfOperated() == null || filter.selfOperated() == product.selfOperated())
                .filter(product -> filter.ratingMin() == null || product.rating() == 0
                        || product.rating() >= filter.ratingMin())
                .filter(product -> filter.priceRange() == null || filter.priceRange().min() == null
                        || product.price() == null || product.price().compareTo(BigDecimal.valueOf(filter.priceRange().min())) >= 0)
                .filter(product -> filter.priceRange() == null || filter.priceRange().max() == null
                        || product.price() == null || product.price().compareTo(BigDecimal.valueOf(filter.priceRange().max())) <= 0)
                .filter(product -> StringUtils.isBlank(positiveKeyword)
                        || productText(product).contains(positiveKeyword.toLowerCase()))
                .filter(product -> negativeTerms.isEmpty()
                        || negativeTerms.stream().noneMatch(term -> productText(product).contains(term.toLowerCase())))
                .filter(product -> filter.colors() == null || filter.colors().isEmpty()
                        || filter.colors().stream().anyMatch(color -> textMatches(product, color)))
                .filter(product -> filter.brands() == null || filter.brands().isEmpty()
                        || filter.brands().stream().anyMatch(brand -> matchesBrand(product, brand)))
                // Attributes matching: all specified attributes must match
                .filter(product -> matchesAttributes(product, filter.attributes()))
                // Exclude attributes: product must NOT match any exclude condition
                .filter(product -> !matchesExcludeAttributes(product, filter.attributes()))
                // Exclude roles: filter out products matching any excluded productRole
                .filter(product -> filter.excludeRoles() == null || filter.excludeRoles().isEmpty()
                        || product.productRole() == null
                        || !filter.excludeRoles().contains(product.productRole().toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    /**
     * Apply intent-based relevance filtering: STRONG tier first, then SAFE_FILL.
     */
    public List<ProductCard> applyIntentFilter(List<ProductCard> products, SearchIntent intent, int targetCount) {
        if (!intent.hasSpecificSignals()) {
            return products;
        }

        List<ProductCard> strong = products.stream()
                .filter(product -> ranker.tier(product, intent) == RelevanceRanker.RelevanceTier.STRONG)
                .toList();
        List<ProductCard> safeFill = products.stream()
                .filter(product -> ranker.tier(product, intent) == RelevanceRanker.RelevanceTier.SAFE_FILL)
                .toList();

        if (strong.size() >= targetCount) {
            return strong;
        }

        List<ProductCard> merged = new ArrayList<>(strong);
        java.util.Set<String> seen = strong.stream()
                .map(ProductCard::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        safeFill.stream()
                .filter(product -> seen.add(product.id()))
                .forEach(merged::add);
        // Fallback: if all products were rejected, return original list to avoid empty results
        return merged.isEmpty() ? products : merged;
    }

    /**
     * Apply sort based on filter's sortBy/sortOrder.
     * Delegates to centralized ProductSortService for consistent behavior.
     */
    public List<ProductCard> applySort(List<ProductCard> products, SearchFilter filter) {
        return productSortService.applySort(products, filter);
    }

    private boolean matchesBrand(ProductCard product, String brand) {
        String useful = SearchTextUtils.useful(brand);
        if (useful.isBlank()) {
            return true;
        }
        String productBrand = SearchTextUtils.useful(product.brand());
        return BrandMatcher.sameBrand(useful, productBrand)
                || BrandMatcher.productMatchesExpectedBrand(product, useful);
    }

    private boolean textMatches(ProductCard product, String token) {
        String useful = SearchTextUtils.useful(token);
        return useful.isBlank() || productText(product).contains(useful.toLowerCase());
    }

    private String productText(ProductCard product) {
        return String.join(" ",
                StringUtils.defaultString(product.title()),
                StringUtils.defaultString(product.brand()),
                StringUtils.defaultString(product.shopName()),
                String.join(" ", product.tags() == null ? List.of() : product.tags())
        ).toLowerCase();
    }

    /**
     * Check if product matches all specified attributes.
     * Attributes are matched against product title, tags, and normalized text.
     * Exclude attributes (keys starting with "exclude_") are handled separately.
     */
    private boolean matchesAttributes(ProductCard product, Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return true;
        String text = productText(product);
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            // Skip exclude attributes (handled by matchesExcludeAttributes)
            if (key.startsWith("exclude_")) continue;
            // Safety net: skip semantic attributes that should be handled by capability evaluators
            if (isSemanticAttribute(key, value)) continue;
            // Check if the attribute value appears in product text
            if (value != null && !value.isBlank() && !text.contains(value.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Safety net: detect semantic/capability attributes that should NOT be hard-filtered by text-contains.
     * These should be handled by FilterExecutionService capability evaluators instead.
     * This is a defensive layer — stripSemanticAttributes in NlpFilterController should already remove these.
     */
    private boolean isSemanticAttribute(String key, String value) {
        if (key == null) return false;
        String lowerKey = key.toLowerCase();
        String lowerValue = value != null ? value.toLowerCase() : "";
        String combined = lowerKey + " " + lowerValue;
        // Capability attributes
        return combined.contains("带上飞机") || combined.contains("可登机") || combined.contains("登机")
                || combined.contains("防水") || combined.contains("防尘") || combined.contains("防摔")
                || combined.contains("快充") || combined.contains("闪充") || combined.contains("快速充电")
                || combined.contains("护眼") || combined.contains("不伤眼") || combined.contains("无频闪")
                || combined.contains("降噪") || combined.contains("主动降噪")
                || combined.contains("长续航") || combined.contains("续航久")
                || combined.contains("适合长跑") || combined.contains("跑步")
                || combined.contains("适合婴儿") || combined.contains("婴儿") || combined.contains("母婴")
                || combined.contains("性价比") || combined.contains("轻便") || combined.contains("好看")
                || combined.contains("高级") || combined.contains("适合送礼") || combined.contains("送礼")
                // Preference attributes
                || combined.contains("适合女生") || combined.contains("女生")
                || combined.contains("适合通勤") || combined.contains("通勤")
                || combined.contains("质量好") || combined.contains("耐用")
                || combined.contains("品牌好") || combined.contains("大牌")
                || combined.contains("新款") || combined.contains("时尚")
                || combined.contains("实用") || combined.contains("百搭")
                // Exclusion attributes
                || combined.contains("不要山寨") || combined.contains("不要配件")
                || combined.contains("不要赠品") || combined.contains("不要低配")
                || combined.contains("排除") || combined.contains("不要");
    }

    /**
     * Check if product matches any exclude attribute.
     * Returns true if the product should be EXCLUDED.
     */
    private boolean matchesExcludeAttributes(ProductCard product, Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return false;
        String text = productText(product);
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            // Only process exclude attributes
            if (!key.startsWith("exclude_")) continue;
            if (value != null && !value.isBlank() && text.contains(value.toLowerCase())) {
                return true; // Product matches exclude condition → exclude it
            }
        }
        return false;
    }
}
