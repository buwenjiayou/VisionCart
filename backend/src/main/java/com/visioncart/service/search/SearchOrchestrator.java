package com.visioncart.service.search;

import com.visioncart.api.dto.PlatformPriceStat;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SearchRequest;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.service.suggestion.SuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class SearchOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(SearchOrchestrator.class);
    private static final Duration PLATFORM_TIMEOUT = Duration.ofSeconds(8);

    private final List<PlatformSearchService> platformServices;
    private final ProductDeduplicator deduplicator;
    private final RelevanceRanker ranker;
    private final SuggestionService suggestionService;

    public SearchOrchestrator(List<PlatformSearchService> platformServices,
                              ProductDeduplicator deduplicator,
                              RelevanceRanker ranker,
                              SuggestionService suggestionService) {
        this.platformServices = platformServices;
        this.deduplicator = deduplicator;
        this.ranker = ranker;
        this.suggestionService = suggestionService;
    }

    public SearchResult search(SearchRequest request) {
        Map<String, String> attributes = request.attributes() == null ? Map.of() : request.attributes();
        SearchFilter filter = request.effectiveFilter();
        List<CompletableFuture<List<ProductCard>>> searches = platformServices.stream()
                .map(platformService -> CompletableFuture
                        .supplyAsync(() -> platformService.search(
                                attributes,
                                filter,
                                request.effectivePage(),
                                request.effectivePageSize()))
                        .completeOnTimeout(List.of(), PLATFORM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .exceptionally(error -> {
                            log.warn("{} search skipped: {}", platformService.platform(), error.toString());
                            return List.of();
                        }))
                .toList();

        List<ProductCard> all = searches.stream()
                .flatMap(future -> future.join().stream())
                .collect(Collectors.toCollection(ArrayList::new));

        List<ProductCard> filtered = applyFilter(deduplicator.deduplicate(all), filter);
        List<ProductCard> ranked = applySort(ranker.rank(filtered, attributes), filter);
        List<ProductCard> page = diversifyPlatforms(ranked, request.effectivePageSize());
        List<SuggestionCard> cards = suggestionService.cards(request.sessionId(), request.clientType(), page);
        return new SearchResult(ranked.size(), page, stats(ranked), cards);
    }

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
                .filter(product -> filter.ratingMin() == null || product.rating() >= filter.ratingMin())
                .filter(product -> filter.priceRange() == null || filter.priceRange().min() == null || product.price().compareTo(BigDecimal.valueOf(filter.priceRange().min())) >= 0)
                .filter(product -> filter.priceRange() == null || filter.priceRange().max() == null || product.price().compareTo(BigDecimal.valueOf(filter.priceRange().max())) <= 0)
                .filter(product -> filter.keyword() == null || filter.keyword().isBlank() || product.title().contains(filter.keyword()))
                .toList();
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
