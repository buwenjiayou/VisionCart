package com.visioncart.service.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.PlatformPriceStat;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SearchRequest;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.ai.HashUtils;
import com.visioncart.service.suggestion.SuggestionService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class SearchOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(SearchOrchestrator.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String CACHE_PREFIX = "visioncart:search:cache:";

    private final List<PlatformSearchService> platformServices;
    private final ProductDeduplicator deduplicator;
    private final RelevanceRanker ranker;
    private final SuggestionService suggestionService;
    private final ExecutorService searchExecutor;
    private final Duration platformTimeout;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PlatformCircuitBreaker circuitBreaker;
    private final RegionResolver regionResolver;
    private final RecognitionHistoryRepository recognitionHistoryRepository;

    public SearchOrchestrator(List<PlatformSearchService> platformServices,
                              ProductDeduplicator deduplicator,
                              RelevanceRanker ranker,
                              SuggestionService suggestionService,
                              @Qualifier("searchExecutor") ExecutorService searchExecutor,
                              VisionCartProperties properties,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              PlatformCircuitBreaker circuitBreaker,
                              RegionResolver regionResolver,
                              RecognitionHistoryRepository recognitionHistoryRepository) {
        this.platformServices = platformServices;
        this.deduplicator = deduplicator;
        this.ranker = ranker;
        this.suggestionService = suggestionService;
        this.searchExecutor = searchExecutor;
        this.platformTimeout = Duration.ofMillis(properties.getSearch().getPlatformTimeoutMs());
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.regionResolver = regionResolver;
        this.recognitionHistoryRepository = recognitionHistoryRepository;
    }

    public SearchResult search(SearchRequest request) {
        return search(request, regionResolver.isDomestic());
    }

    public SearchResult search(SearchRequest request, Long userId) {
        return search(request, regionResolver.isDomestic(), userId);
    }

    public SearchResult search(SearchRequest request, boolean domestic) {
        return search(request, domestic, null);
    }

    public SearchResult search(SearchRequest request, boolean domestic, Long userId) {
        Map<String, String> attributes = enrichAttributes(request, userId);
        SearchFilter filter = request.effectiveFilter();

        // Check cache (cache full ranked list, paginate on hit)
        String cacheKey = buildCacheKey(attributes, filter, domestic);
        List<ProductCard> cached = getFromCache(cacheKey);
        if (cached != null) {
            List<ProductCard> page = diversifyPlatforms(cached, request.effectivePageSize());
            List<SuggestionCard> cards = suggestionService.cards(request.clientType(), page);
            return new SearchResult(cached.size(), page, stats(cached), cards);
        }

        // Parallel platform search with circuit breaker, filtered by region
        List<CompletableFuture<List<ProductCard>>> searches = platformServices.stream()
                .filter(ps -> !domestic || ps.domesticOnly())
                .map(platformService -> {
                    try {
                        return CompletableFuture.supplyAsync(() -> {
                            if (!circuitBreaker.allowRequest(platformService.platform())) {
                                log.info("Circuit open, skipping {}", platformService.platform());
                                return List.<ProductCard>of();
                            }
                            long start = System.nanoTime();
                            try {
                                List<ProductCard> result = platformService.search(
                                        attributes, filter, request.effectivePage(), request.effectivePageSize());
                                long durationMs = (System.nanoTime() - start) / 1_000_000;
                                circuitBreaker.recordSuccess(platformService.platform(), durationMs);
                                return result;
                            } catch (Exception e) {
                                long durationMs = (System.nanoTime() - start) / 1_000_000;
                            circuitBreaker.recordFailure(platformService.platform(), durationMs);
                            throw e;
                        }
                        }, searchExecutor);
                    } catch (RejectedExecutionException error) {
                        log.warn("{} search skipped: executor queue full", platformService.platform());
                        return CompletableFuture.completedFuture(List.<ProductCard>of());
                    }
                })
                .map(future -> future.completeOnTimeout(List.of(), platformTimeout.toMillis(), TimeUnit.MILLISECONDS)
                        .exceptionally(error -> {
                            log.warn("{} search skipped: {}", error.getClass().getSimpleName(), error.getMessage());
                            return List.of();
                        }))
                .toList();

        List<ProductCard> all = searches.stream()
                .flatMap(future -> future.join().stream())
                .collect(Collectors.toCollection(ArrayList::new));

        // Fill similarity fallback
        fillSimilarity(all, attributes);

        List<ProductCard> filtered = applyCoreProductFilter(
                applyFilter(deduplicator.deduplicate(all), filter),
                attributes);
        List<ProductCard> ranked = applySort(ranker.rank(filtered, attributes), filter);
        List<ProductCard> page = diversifyPlatforms(ranked, request.effectivePageSize());
        List<SuggestionCard> cards = suggestionService.cards(request.clientType(), page);

        // Write cache
        putToCache(cacheKey, ranked);

        return new SearchResult(ranked.size(), page, stats(ranked), cards);
    }

    private Map<String, String> enrichAttributes(SearchRequest request, Long userId) {
        Map<String, String> attributes = new LinkedHashMap<>(
                request.attributes() == null ? Map.of() : request.attributes());
        if (StringUtils.isBlank(request.sessionId())) {
            return attributes;
        }

        boolean needsCategory = StringUtils.isBlank(SearchTextUtils.useful(attributes.get("类目")));
        boolean needsKeyword = StringUtils.isBlank(SearchTextUtils.useful(attributes.get("关键词")));
        if (!needsCategory && !needsKeyword) {
            return attributes;
        }

        (userId == null
                ? recognitionHistoryRepository.findById(request.sessionId())
                : recognitionHistoryRepository.findBySessionIdAndUserId(request.sessionId(), userId)).ifPresent(history -> {
            if (needsCategory) {
                putIfUsefulMissing(attributes, "类目", categoryName(history.getCategoryJson()));
            }
            if (needsKeyword && StringUtils.isNotBlank(history.getKeywords())) {
                putIfUsefulMissing(attributes, "关键词", history.getKeywords().split(",")[0]);
            }
        });
        return attributes;
    }

    private String categoryName(String categoryJson) {
        if (StringUtils.isBlank(categoryJson)) {
            return "";
        }
        try {
            JsonNode category = objectMapper.readTree(categoryJson);
            return firstNonBlank(
                    category.path("level3").asText(""),
                    category.path("level2").asText(""),
                    category.path("level1").asText(""));
        } catch (Exception e) {
            log.warn("Failed to parse recognition category for search: {}", e.getMessage());
            return "";
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private void putIfUsefulMissing(Map<String, String> attributes, String key, String value) {
        if (StringUtils.isNotBlank(SearchTextUtils.useful(attributes.get(key)))) {
            return;
        }
        String useful = SearchTextUtils.useful(value);
        if (StringUtils.isNotBlank(useful)) {
            attributes.put(key, useful);
        }
    }

    // --- Cache ---

    private String buildCacheKey(Map<String, String> attributes, SearchFilter filter, boolean domestic) {
        String raw;
        try {
            raw = objectMapper.writeValueAsString(attributes) + objectMapper.writeValueAsString(filter)
                    + (domestic ? ":CN" : ":INTL");
        } catch (JsonProcessingException e) {
            raw = attributes.toString() + filter.toString() + (domestic ? ":CN" : ":INTL");
        }
        return CACHE_PREFIX + HashUtils.md5Hex(raw);
    }

    private List<ProductCard> getFromCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("Cache read failed: {}", e.getMessage());
        }
        return null;
    }

    private void putToCache(String key, List<ProductCard> products) {
        try {
            String json = objectMapper.writeValueAsString(products);
            redisTemplate.opsForValue().set(key, json, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Cache write failed: {}", e.getMessage());
        }
    }

    // --- Similarity fallback ---

    private void fillSimilarity(List<ProductCard> products, Map<String, String> attributes) {
        Set<String> keywords = attributes.values().stream()
                .map(SearchTextUtils::useful)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.toSet());
        if (keywords.isEmpty()) return;

        for (int i = 0; i < products.size(); i++) {
            ProductCard p = products.get(i);
            if (p.similarity() > 0) continue;
            double jaccard = jaccardSimilarity(p.title(), keywords);
            products.set(i, new ProductCard(
                    p.id(), p.title(), p.imageUrl(), p.price(), p.originalPrice(),
                    p.platform(), p.selfOperated(), p.shopName(), p.rating(), p.sales(),
                    jaccard, p.tags(), p.detailUrl(), p.brand(), p.ratingSource(), p.salesLabel()
            ));
        }
    }

    private double jaccardSimilarity(String title, Set<String> keywords) {
        if (title == null || title.isBlank()) return 0;
        String normalizedTitle = title.toLowerCase();
        long matches = keywords.stream()
                .filter(kw -> normalizedTitle.contains(kw.toLowerCase()))
                .count();
        // union = keywords + title tokens not in keywords (approximate with keyword count)
        int union = Math.max(keywords.size(), 1);
        return (double) matches / union;
    }

    // --- Filter / Sort / Diversify / Stats (unchanged) ---

    private List<ProductCard> diversifyPlatforms(List<ProductCard> products, int pageSize) {
        List<ProductCard> page = new ArrayList<>();
        Map<String, List<ProductCard>> byPlatform = products.stream()
                .collect(Collectors.groupingBy(ProductCard::platform, java.util.LinkedHashMap::new, Collectors.toList()));

        byPlatform.values().forEach(platformProducts -> {
            if (!platformProducts.isEmpty() && page.size() < pageSize) {
                page.add(platformProducts.get(0));
            }
        });

        products.stream()
                .filter(product -> !page.contains(product))
                .limit(Math.max(0, pageSize - page.size()))
                .forEach(page::add);
        return page;
    }

    private List<ProductCard> applyFilter(List<ProductCard> products, SearchFilter filter) {
        return products.stream()
                .filter(product -> filter.platforms() == null || filter.platforms().isEmpty() || filter.platforms().contains(product.platform()))
                .filter(product -> filter.selfOperated() == null || filter.selfOperated() == product.selfOperated())
                .filter(product -> filter.ratingMin() == null || product.rating() == 0 || product.rating() >= filter.ratingMin())
                .filter(product -> filter.priceRange() == null || filter.priceRange().min() == null || product.price().compareTo(BigDecimal.valueOf(filter.priceRange().min())) >= 0)
                .filter(product -> filter.priceRange() == null || filter.priceRange().max() == null || product.price().compareTo(BigDecimal.valueOf(filter.priceRange().max())) <= 0)
                .filter(product -> filter.keyword() == null || filter.keyword().isBlank()
                        || StringUtils.defaultString(product.title()).toLowerCase().contains(filter.keyword().toLowerCase()))
                .filter(product -> filter.brands() == null || filter.brands().isEmpty() || filter.brands().stream().anyMatch(brand -> matchesBrand(product, brand)))
                .toList();
    }

    private boolean matchesBrand(ProductCard product, String brand) {
        String useful = SearchTextUtils.useful(brand);
        if (useful.isBlank()) {
            return true;
        }
        String productBrand = SearchTextUtils.useful(product.brand());
        return useful.equalsIgnoreCase(productBrand)
                || (product.title() != null && product.title().toLowerCase().contains(useful.toLowerCase()));
    }

    private List<ProductCard> applyCoreProductFilter(List<ProductCard> products, Map<String, String> attributes) {
        if (StringUtils.isBlank(SearchTextUtils.useful(attributes.get("类目")))) {
            return products;
        }
        List<ProductCard> relevant = products.stream()
                .filter(product -> SearchTextUtils.relevantToCoreProduct(product.title(), attributes))
                .toList();
        return relevant.isEmpty() ? products : relevant;
    }

    private List<ProductCard> applySort(List<ProductCard> products, SearchFilter filter) {
        String sortBy = filter.sortBy();
        if ("price".equals(sortBy)) {
            return products.stream().sorted(Comparator.comparing(ProductCard::price)).toList();
        }
        if ("sales".equals(sortBy)) {
            return products.stream().sorted(Comparator.comparingLong(ProductCard::sales).reversed()).toList();
        }
        if ("rating".equals(sortBy) || "reviews".equals(sortBy)) {
            return products.stream().sorted(Comparator.comparingDouble(ProductCard::rating).reversed()).toList();
        }
        return products;
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
