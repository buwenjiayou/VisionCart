package com.visioncart.service.search;

import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.metrics.PerformanceMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Regression tests for CandidateFilterService.
 * Verifies dedup safety with empty ids and metrics timer correctness.
 */
class CandidateFilterServiceTest {

    private CandidateFilterService filterService;

    @BeforeEach
    void setUp() {
        RelevanceRanker ranker = new RelevanceRanker();
        ProductValidityFilter validityFilter = mock(ProductValidityFilter.class);
        when(validityFilter.filterValid(any())).thenAnswer(inv -> inv.getArgument(0));
        PerformanceMetricsService metricsService = mock(PerformanceMetricsService.class);
        when(metricsService.startCandidateFilterTimer()).thenReturn(
                io.micrometer.core.instrument.Timer.start(io.micrometer.core.instrument.Metrics.globalRegistry));
        filterService = new CandidateFilterService(ranker, validityFilter, metricsService, new ProductSortService(new ProductReputationService()));
    }

    // ==================== P1: Dedup with empty id ====================

    @Test
    void productsWithEmptyIdAreNotCollapsed() {
        ProductCard a = productCard("", "商品A", 99.0, "淘宝", "http://img.a.com/1.jpg");
        ProductCard b = productCard("", "商品B", 199.0, "京东", "http://img.b.com/2.jpg");
        ProductCard c = productCard("", "商品A", 99.0, "淘宝", "http://img.a.com/1.jpg"); // duplicate of A

        var result = filterService.filter(List.of(a, b, c), SearchFilter.empty(), Map.of(), 50, 1);

        // A and B are distinct (different price+platform+imageUrl), C is dup of A
        assertThat(result.products()).hasSize(2);
    }

    @Test
    void productsWithBlankIdAreDedupedByCompositeKey() {
        ProductCard a = productCard("  ", "同名商品", 99.0, "淘宝", "http://img.a.com/1.jpg");
        ProductCard b = productCard(null, "同名商品", 99.0, "淘宝", "http://img.a.com/1.jpg"); // same composite key
        ProductCard c = productCard("", "同名商品", 99.0, "京东", "http://img.c.com/3.jpg"); // different platform

        var result = filterService.filter(List.of(a, b, c), SearchFilter.empty(), Map.of(), 50, 1);

        // A and B have same composite key (title+price+platform+imageUrl), C is different
        assertThat(result.products()).hasSize(2);
    }

    @Test
    void productsWithValidIdAreDedupedById() {
        ProductCard a = productCard("id-1", "商品A", 99.0, "淘宝", "http://img.a.com/1.jpg");
        ProductCard b = productCard("id-1", "商品A完全不同的标题", 199.0, "京东", "http://img.b.com/2.jpg");

        var result = filterService.filter(List.of(a, b), SearchFilter.empty(), Map.of(), 50, 1);

        // Same id → deduped to 1
        assertThat(result.products()).hasSize(1);
    }

    // ==================== P1: Metrics timer always stops ====================

    @Test
    void filterStopsTimerOnNormalPath() {
        PerformanceMetricsService metricsService = mock(PerformanceMetricsService.class);
        when(metricsService.startCandidateFilterTimer()).thenReturn(
                io.micrometer.core.instrument.Timer.start(io.micrometer.core.instrument.Metrics.globalRegistry));
        RelevanceRanker ranker = new RelevanceRanker();
        ProductValidityFilter validityFilter = mock(ProductValidityFilter.class);
        when(validityFilter.filterValid(any())).thenAnswer(inv -> inv.getArgument(0));
        CandidateFilterService service = new CandidateFilterService(ranker, validityFilter, metricsService, new ProductSortService(new ProductReputationService()));

        var result = service.filter(
                List.of(productCard("1", "商品", 99.0, "淘宝", "http://img.com/1.jpg")),
                SearchFilter.empty(), Map.of(), 50, 1);

        assertThat(result.products()).hasSize(1);
        // Timer must be stopped via finally block
        verify(metricsService).stopCandidateFilterTimer(any(), eq(1), eq(1));
    }

    @Test
    void filterStopsTimerEvenWhenCandidatesFilteredToZero() {
        PerformanceMetricsService metricsService = mock(PerformanceMetricsService.class);
        when(metricsService.startCandidateFilterTimer()).thenReturn(
                io.micrometer.core.instrument.Timer.start(io.micrometer.core.instrument.Metrics.globalRegistry));
        RelevanceRanker ranker = new RelevanceRanker();
        ProductValidityFilter validityFilter = mock(ProductValidityFilter.class);
        when(validityFilter.filterValid(any())).thenAnswer(inv -> inv.getArgument(0));
        CandidateFilterService service = new CandidateFilterService(ranker, validityFilter, metricsService, new ProductSortService(new ProductReputationService()));

        // Filter with a restrictive price filter that eliminates all products
        SearchFilter priceFilter = new SearchFilter(
                new PriceRange(1000.0, 2000.0), List.of(), null,
                List.of(), List.of(), null, null, null, null, Map.of());
        var result = service.filter(
                List.of(productCard("1", "便宜商品", 10.0, "淘宝", "http://img.com/1.jpg")),
                priceFilter, Map.of(), 50, 1);

        assertThat(result.products()).isEmpty();
        // Timer must still be stopped even when result is empty (finally block)
        verify(metricsService).stopCandidateFilterTimer(any(), eq(1), eq(0));
    }

    // ==================== Helper ====================

    private ProductCard productCard(String id, String title, double price, String platform, String imageUrl) {
        return new ProductCard(
                id, title, imageUrl, BigDecimal.valueOf(price), null,
                platform, false, "店铺", 4.5, 100, 0.9,
                List.of(), "https://example.com", null, null, null, null, null
        );
    }
}
