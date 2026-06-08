package com.visioncart.service.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.strategy.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchGoldenDatasetTest {

    private ProductIntentBuilder builder;
    private VerticalStrategyRegistry registry;

    @BeforeEach
    void setUp() {
        ProductTaxonomyRegistry taxonomy = new ProductTaxonomyRegistry();
        RelevanceRanker ranker = new RelevanceRanker();
        QueryPlanner planner = new QueryPlanner(taxonomy);
        IntentGate gate = new IntentGate(taxonomy, ranker);
        DefaultProductIntentStrategy defaultStrategy = new DefaultProductIntentStrategy(planner, gate);
        registry = new VerticalStrategyRegistry(
                List.of(
                        new PhoneCaseStrategy(planner, gate),
                        new PhoneStrategy(planner, gate),
                        new EarbudsStrategy(planner, gate),
                        new AccessoryMainStrategy(planner, gate),
                        new ConsumableStrategy(planner, gate),
                        defaultStrategy
                ),
                defaultStrategy
        );
        builder = new ProductIntentBuilder(taxonomy, new BrandRelationResolver());
    }

    @Test
    void goldenDatasetMatchesExpectedIntentBoundaries() throws Exception {
        List<GoldenCase> cases = new ObjectMapper().readValue(
                getClass().getResourceAsStream("/search-golden/matrix.json"),
                new TypeReference<>() {});

        for (GoldenCase fixture : cases) {
            ProductIntent intent = builder.build(fixture.attributes(), fixture.name());
            VerticalSearchStrategy strategy = registry.resolve(intent);
            IntentGate.IntentTier mainTier = strategy.classify(intent, product("main", fixture.mainTitle()));
            IntentGate.IntentTier relatedTier = strategy.classify(intent, product("related", fixture.relatedTitle()));
            QueryPlan plan = strategy.buildQueryPlan(intent, SearchFilter.empty());

            assertThat(intent.productFamily()).as(fixture.name()).isEqualTo(fixture.expectedFamily());
            assertThat(plan.primaryQueries()).as(fixture.name() + " required queries").isNotEmpty();
            assertThat(mainTier).as(fixture.name() + " main").isNotEqualTo(IntentGate.IntentTier.REJECT);
            assertThat(relatedTier).as(fixture.name() + " related")
                    .isEqualTo(IntentGate.IntentTier.valueOf(fixture.relatedTier()));

            // P1-2: forbidden queries — primary/secondary/fallback 都不应包含禁止词
            if (fixture.forbiddenQueries() != null) {
                for (String forbidden : fixture.forbiddenQueries()) {
                    assertThat(plan.primaryQueries()).as(fixture.name() + " primary forbidden")
                            .doesNotContain(forbidden);
                    assertThat(plan.secondaryQueries()).as(fixture.name() + " secondary forbidden")
                            .doesNotContain(forbidden);
                    assertThat(plan.fallbackQueries()).as(fixture.name() + " fallback forbidden")
                            .doesNotContain(forbidden);
                }
            }
        }
    }

    @Test
    void goldenDatasetMixRatioRespectsLimits() throws Exception {
        List<GoldenCase> cases = new ObjectMapper().readValue(
                getClass().getResourceAsStream("/search-golden/matrix.json"),
                new TypeReference<>() {});

        for (GoldenCase fixture : cases) {
            if (fixture.maxRelatedTop20() == null && fixture.maxRelatedTotal() == null) continue;

            ProductIntent intent = builder.build(fixture.attributes(), fixture.name());
            VerticalSearchStrategy strategy = registry.resolve(intent);

            // 构造 50 个商品：30 个主商品 + 20 个 related
            List<VerticalSearchStrategy.ClassifiedProduct> classified = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                ProductCard p = product("main_" + i, fixture.mainTitle() + " 变体" + i);
                classified.add(new VerticalSearchStrategy.ClassifiedProduct(p, strategy.classify(intent, p)));
            }
            for (int i = 0; i < 20; i++) {
                ProductCard p = product("related_" + i, fixture.relatedTitle() + " 配件" + i);
                classified.add(new VerticalSearchStrategy.ClassifiedProduct(p, strategy.classify(intent, p)));
            }

            List<ProductCard> mixed = strategy.mix(intent, classified, 50);

            // 统计 RELATED_ACCESSORY 数量
            long totalRelated = mixed.stream()
                    .filter(p -> strategy.classify(intent, p) == IntentGate.IntentTier.RELATED_ACCESSORY)
                    .count();

            if (fixture.maxRelatedTotal() != null) {
                assertThat(totalRelated).as(fixture.name() + " maxRelatedTotal")
                        .isLessThanOrEqualTo(fixture.maxRelatedTotal());
            }

            if (fixture.maxRelatedTop20() != null && mixed.size() >= 20) {
                long top20Related = mixed.subList(0, 20).stream()
                        .filter(p -> strategy.classify(intent, p) == IntentGate.IntentTier.RELATED_ACCESSORY)
                        .count();
                assertThat(top20Related).as(fixture.name() + " maxRelatedTop20")
                        .isLessThanOrEqualTo(fixture.maxRelatedTop20());
            }
        }
    }

    private ProductCard product(String id, String title) {
        return new ProductCard(id, title, "", BigDecimal.TEN, null,
                "淘宝", false, "shop", 4.5, 100, 0.9,
                List.of(), "", null, "none", null);
    }

    private record GoldenCase(
            String name,
            Map<String, String> attributes,
            String expectedFamily,
            String mainTitle,
            String relatedTitle,
            String relatedTier,
            List<String> forbiddenQueries,
            Integer maxRelatedTop20,
            Integer maxRelatedTotal
    ) {}
}
