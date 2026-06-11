package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.service.search.strategy.MixPolicy;
import com.visioncart.service.search.strategy.VerticalSearchStrategy;
import com.visioncart.service.search.strategy.VerticalStrategyRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchRunServiceTest {

    @Test
    void recomputeDisplayPagePersistsRunStateWithoutShrinkingCandidatePoolToTop50() {
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        CandidateFilterService filterService = mock(CandidateFilterService.class);
        ProductReputationService reputationService = new ProductReputationService();
        ProductSortService sortService = new ProductSortService(reputationService);
        VerticalStrategyRegistry strategyRegistry = mock(VerticalStrategyRegistry.class);
        VerticalSearchStrategy strategy = mock(VerticalSearchStrategy.class);

        ProductIntent intent = new ProductIntent(
                "sess-run", "phone case", "phone_case",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "", "iPhone", "iPhone", false,
                Map.of(), Map.of(), List.of(), List.of(), List.of(), 0.9, "test"
        );
        List<ProductCard> products = new ArrayList<>();
        List<VerticalSearchStrategy.ClassifiedProduct> classified = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            ProductCard product = product("p" + i, "Phone case " + i, i % 2 == 0 ? "taobao" : "pdd");
            products.add(product);
            classified.add(new VerticalSearchStrategy.ClassifiedProduct(product, IntentGate.IntentTier.EXACT_MAIN));
        }
        SearchCandidatePool pool = new SearchCandidatePool(
                "sess-run", "run-1", "identity-1",
                intent, "TestStrategy", SearchFilter.empty(), classified, 120, products,
                SearchFilter.empty(), null, List.of());

        when(sessionCache.getClassifiedPool("sess-run")).thenReturn(Optional.of(pool));
        when(filterService.applyFilter(any(), any())).thenReturn(products);
        when(strategyRegistry.resolve(intent)).thenReturn(strategy);
        when(strategy.mix(eq(intent), any(), eq(50))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<VerticalSearchStrategy.ClassifiedProduct> sorted = invocation.getArgument(1);
            return sorted.stream().limit(50).map(VerticalSearchStrategy.ClassifiedProduct::product).toList();
        });

        SearchRunService service = new SearchRunService(
                sessionCache, filterService, reputationService, sortService, strategyRegistry);

        SearchResult result = service.recomputeDisplayPage("sess-run", SearchFilter.empty(), 50).orElseThrow();

        assertThat(result.products()).hasSize(50);
        assertThat(result.total()).isEqualTo(80);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductCard>> candidatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(sessionCache).saveCandidates(eq("sess-run"), candidatesCaptor.capture(), eq("identity-1"));
        assertThat(candidatesCaptor.getValue()).hasSize(80);

        ArgumentCaptor<SearchCandidatePool> poolCaptor = ArgumentCaptor.forClass(SearchCandidatePool.class);
        verify(sessionCache).saveClassifiedPool(eq("sess-run"), poolCaptor.capture());
        assertThat(poolCaptor.getValue().currentDisplayPage()).hasSize(50);
        assertThat(poolCaptor.getValue().toProductCards()).hasSize(80);
    }

    @Test
    void recomputeDisplayPageAlwaysEnrichesReputationLabels() {
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        CandidateFilterService filterService = mock(CandidateFilterService.class);
        ProductReputationService reputationService = new ProductReputationService();
        ProductSortService sortService = new ProductSortService(reputationService);
        VerticalStrategyRegistry strategyRegistry = mock(VerticalStrategyRegistry.class);
        VerticalSearchStrategy strategy = mock(VerticalSearchStrategy.class);

        ProductIntent intent = productIntent();
        ProductCard product = product("tb-1", "Taobao trusted", "taobao")
                .withReputationSignals(null, 0.96, null, null, "shop_dsr");
        List<ProductCard> products = List.of(product);
        List<VerticalSearchStrategy.ClassifiedProduct> classified = List.of(
                new VerticalSearchStrategy.ClassifiedProduct(product, IntentGate.IntentTier.EXACT_MAIN));
        SearchCandidatePool pool = new SearchCandidatePool(
                "sess-run", "run-1", "identity-1",
                intent, "TestStrategy", SearchFilter.empty(), classified, 1, products,
                SearchFilter.empty(), null, List.of());

        when(filterService.applyFilter(any(), any())).thenReturn(products);
        when(strategyRegistry.resolve(intent)).thenReturn(strategy);
        when(strategy.mix(eq(intent), any(), eq(50))).thenReturn(products);

        SearchRunService service = new SearchRunService(
                sessionCache, filterService, reputationService, sortService, strategyRegistry);

        SearchResult result = service.recomputeDisplayPage(pool, SearchFilter.empty(), 50, false);

        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).ratingDisplayLabel()).isEqualTo("店铺评分 4.8");
        assertThat(result.products().get(0).reputationIndex()).isEqualTo(72);
    }

    @Test
    void reviewQualityDisplayPageKeepsTrustedPddVisibleInTop20() {
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        CandidateFilterService filterService = mock(CandidateFilterService.class);
        ProductReputationService reputationService = new ProductReputationService();
        ProductSortService sortService = new ProductSortService(reputationService);
        VerticalStrategyRegistry strategyRegistry = mock(VerticalStrategyRegistry.class);
        VerticalSearchStrategy strategy = mock(VerticalSearchStrategy.class);

        ProductIntent intent = productIntent();
        List<ProductCard> products = new ArrayList<>();
        List<VerticalSearchStrategy.ClassifiedProduct> classified = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ProductCard product = product("tb-" + i, "Taobao " + i, "taobao")
                    .withReputationSignals(null, 0.96, null, null, "shop_dsr");
            products.add(product);
            classified.add(new VerticalSearchStrategy.ClassifiedProduct(product, IntentGate.IntentTier.EXACT_MAIN));
        }
        for (int i = 0; i < 10; i++) {
            ProductCard product = product("pdd-" + i, "PDD " + i, "pdd")
                    .withReputationSignals(null, 0.85, "high", null, "pdd_shop_level");
            products.add(product);
            classified.add(new VerticalSearchStrategy.ClassifiedProduct(product, IntentGate.IntentTier.EXACT_MAIN));
        }
        for (int i = 0; i < 5; i++) {
            ProductCard product = product("ebay-" + i, "eBay " + i, "eBay")
                    .withReputationSignals(null, null, null, 0.98, "seller");
            products.add(product);
            classified.add(new VerticalSearchStrategy.ClassifiedProduct(product, IntentGate.IntentTier.EXACT_MAIN));
        }
        SearchCandidatePool pool = new SearchCandidatePool(
                "sess-run", "run-1", "identity-1",
                intent, "TestStrategy", SearchFilter.empty(), classified, products.size(), products,
                SearchFilter.empty(), null, List.of());
        SearchFilter reviewSort = new SearchFilter(null, List.of(), null, List.of(), List.of(),
                null, "review_quality", "desc", null);

        when(filterService.applyFilter(any(), any())).thenReturn(products);
        when(strategyRegistry.resolve(intent)).thenReturn(strategy);
        when(strategy.mix(eq(intent), any(), eq(50))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<VerticalSearchStrategy.ClassifiedProduct> sorted = invocation.getArgument(1);
            return sorted.stream().limit(50).map(VerticalSearchStrategy.ClassifiedProduct::product).toList();
        });

        SearchRunService service = new SearchRunService(
                sessionCache, filterService, reputationService, sortService, strategyRegistry);

        SearchResult result = service.recomputeDisplayPage(pool, reviewSort, 50, false);
        List<ProductCard> top20 = result.products().stream().limit(20).toList();

        assertThat(top20).extracting(ProductCard::platform).contains("pdd");
        assertThat(top20).extracting(ProductCard::platform).contains("eBay");
        assertThat(top20.stream().filter(product -> "taobao".equals(product.platform())).count())
                .isLessThan(20);
    }

    @Test
    void overseasPoolRecomputeUsesOverseasMatcherInsteadOfDomesticStrategyMix() {
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        CandidateFilterService filterService = mock(CandidateFilterService.class);
        ProductReputationService reputationService = new ProductReputationService();
        ProductSortService sortService = new ProductSortService(reputationService);
        VerticalStrategyRegistry strategyRegistry = mock(VerticalStrategyRegistry.class);
        VerticalSearchStrategy strategy = mock(VerticalSearchStrategy.class);
        OverseasEnglishIntentMatcher matcher = spy(new OverseasEnglishIntentMatcher());

        ProductIntent intent = new ProductIntent(
                "sess-overseas", "mouse", "mouse",
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "", "", "",
                false,
                Map.of(),
                Map.of(),
                List.of("wireless"),
                List.of(),
                List.of(),
                1.0,
                "test"
        );
        ProductCard generic = product("generic", "Computer mouse", "eBay");
        ProductCard close = product("close", "Logitech wireless gaming mouse", "eBay");
        List<ProductCard> products = List.of(generic, close);
        List<VerticalSearchStrategy.ClassifiedProduct> classified = List.of(
                new VerticalSearchStrategy.ClassifiedProduct(generic, IntentGate.IntentTier.EXACT_MAIN),
                new VerticalSearchStrategy.ClassifiedProduct(close, IntentGate.IntentTier.EXACT_MAIN)
        );
        SearchCandidatePool pool = new SearchCandidatePool(
                "sess-overseas", "run-1", "identity-1",
                intent, "OverseasEnglishIntentMatcher(TestStrategy)",
                SearchFilter.empty(), classified, products.size(), products,
                SearchFilter.empty(), null, products, false);

        when(filterService.applyFilter(any(), any())).thenReturn(products);
        when(strategyRegistry.resolve(intent)).thenReturn(strategy);
        when(strategy.policy(intent)).thenReturn(MixPolicy.defaults());

        SearchRunService service = new SearchRunService(
                sessionCache, filterService, reputationService, sortService, strategyRegistry, matcher);

        SearchResult result = service.recomputeDisplayPage(pool, SearchFilter.empty(), 50, false);

        assertThat(result.products()).extracting(ProductCard::id)
                .containsExactly("close", "generic");
        verify(matcher, atLeastOnce()).sortWithinTier(eq(intent), any());
        verify(strategy, never()).mix(eq(intent), any(), anyInt());
    }

    @Test
    void domesticPoolRecomputeDoesNotUseOverseasMatcher() {
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        CandidateFilterService filterService = mock(CandidateFilterService.class);
        ProductReputationService reputationService = new ProductReputationService();
        ProductSortService sortService = new ProductSortService(reputationService);
        VerticalStrategyRegistry strategyRegistry = mock(VerticalStrategyRegistry.class);
        VerticalSearchStrategy strategy = mock(VerticalSearchStrategy.class);
        OverseasEnglishIntentMatcher matcher = spy(new OverseasEnglishIntentMatcher());

        ProductIntent intent = productIntent();
        ProductCard product = product("tb-1", "Phone case", "taobao");
        List<ProductCard> products = List.of(product);
        List<VerticalSearchStrategy.ClassifiedProduct> classified = List.of(
                new VerticalSearchStrategy.ClassifiedProduct(product, IntentGate.IntentTier.EXACT_MAIN));
        SearchCandidatePool pool = new SearchCandidatePool(
                "sess-domestic", "run-1", "identity-1",
                intent, "TestStrategy", SearchFilter.empty(), classified, products.size(), products,
                SearchFilter.empty(), null, products, true);

        when(filterService.applyFilter(any(), any())).thenReturn(products);
        when(strategyRegistry.resolve(intent)).thenReturn(strategy);
        when(strategy.mix(eq(intent), any(), eq(50))).thenReturn(products);

        SearchRunService service = new SearchRunService(
                sessionCache, filterService, reputationService, sortService, strategyRegistry, matcher);

        SearchResult result = service.recomputeDisplayPage(pool, SearchFilter.empty(), 50, false);

        assertThat(result.products()).extracting(ProductCard::id).containsExactly("tb-1");
        verify(matcher, never()).sortWithinTier(any(), any());
    }

    private static ProductCard product(String id, String title, String platform) {
        return new ProductCard(
                id, title, "https://img.example/" + id + ".jpg",
                BigDecimal.valueOf(99), null,
                platform, false, "shop", 4.5, 100, 0.8,
                List.of(), "https://detail.example/" + id,
                "", "shop_dsr", "100 sold"
        );
    }

    private static ProductIntent productIntent() {
        return new ProductIntent(
                "sess-run", "phone case", "phone_case",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "", "iPhone", "iPhone", false,
                Map.of(), Map.of(), List.of(), List.of(), List.of(), 0.9, "test"
        );
    }
}
