package com.visioncart.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.strategy.VerticalSearchStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for classifiedPool save/get in CandidateSessionCache,
 * and the SearchCandidatePool model.
 */
class SearchCandidatePoolTest {

    private static ProductCard product(String id, String title, String platform, double price) {
        return new ProductCard(
                id, title, "https://img.example/" + id + ".jpg",
                BigDecimal.valueOf(price), null,
                platform, false, "shop", 4.5, 100, 0.8,
                List.of(), "https://detail.example/" + id,
                "", "api", "100 sold"
        );
    }

    private static ProductCard productWithRole(String id, String title, String platform,
                                                double price, String role) {
        return product(id, title, platform, price).withRole("", role);
    }

    @Test
    void saveAndGetClassifiedPool_roundTripsThroughRedis() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ObjectMapper objectMapper = new ObjectMapper();
        CandidateSessionCache cache = new CandidateSessionCache(redisTemplate, objectMapper);

        // Build a classifiedPool
        ProductCard p1 = product("p1", "iPhone 15 Pro Case", "淘宝", 29.9);
        ProductCard p2 = product("p2", "iPhone 15 Pro Screen Protector", "拼多多", 9.9);
        List<VerticalSearchStrategy.ClassifiedProduct> classified = List.of(
                new VerticalSearchStrategy.ClassifiedProduct(p1, IntentGate.IntentTier.EXACT_MAIN),
                new VerticalSearchStrategy.ClassifiedProduct(p2, IntentGate.IntentTier.RELATED_ACCESSORY)
        );
        ProductIntent intent = new ProductIntent(
                "sess1", "手机壳", "phone_case",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "", "iPhone 15 Pro", "iPhone 15 Pro", false,
                Map.of(), Map.of(), List.of(), List.of(), List.of(), 0.9, "test"
        );
        SearchFilter filter = SearchFilter.empty();
        SearchCandidatePool pool = new SearchCandidatePool(
                "sess1", "run-123", "identity-abc",
                intent, "DefaultProductIntentStrategy",
                filter, classified, 200
        );

        // Save
        cache.saveClassifiedPool("sess1", pool);

        // Capture the JSON written to Redis
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(
                eq("visioncart:session:classified_pool:sess1"),
                jsonCaptor.capture(),
                eq(30L), eq(TimeUnit.MINUTES)
        );

        // Mock the read
        when(valueOps.get("visioncart:session:classified_pool:sess1")).thenReturn(jsonCaptor.getValue());

        // Get
        Optional<SearchCandidatePool> restored = cache.getClassifiedPool("sess1");

        assertThat(restored).isPresent();
        assertThat(restored.get().poolSize()).isEqualTo(2);
        assertThat(restored.get().searchRunId()).isEqualTo("run-123");
        assertThat(restored.get().strategyName()).isEqualTo("DefaultProductIntentStrategy");
        assertThat(restored.get().classifiedPool()).hasSize(2);
        assertThat(restored.get().classifiedPool().get(0).tier()).isEqualTo(IntentGate.IntentTier.EXACT_MAIN);
        assertThat(restored.get().classifiedPool().get(1).tier()).isEqualTo(IntentGate.IntentTier.RELATED_ACCESSORY);
        assertThat(restored.get().toProductCards()).hasSize(2);
    }

    @Test
    void getBestCandidates_returnsClassifiedPoolWhenAvailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ObjectMapper objectMapper = new ObjectMapper();
        CandidateSessionCache cache = new CandidateSessionCache(redisTemplate, objectMapper);

        // Build classifiedPool with 3 products
        ProductCard p1 = product("p1", "Main Product", "淘宝", 99.0);
        ProductCard p2 = product("p2", "Related Accessory", "拼多多", 19.0);
        ProductCard p3 = product("p3", "Same Family Product", "eBay", 149.0);
        List<VerticalSearchStrategy.ClassifiedProduct> classified = List.of(
                new VerticalSearchStrategy.ClassifiedProduct(p1, IntentGate.IntentTier.EXACT_MAIN),
                new VerticalSearchStrategy.ClassifiedProduct(p2, IntentGate.IntentTier.RELATED_ACCESSORY),
                new VerticalSearchStrategy.ClassifiedProduct(p3, IntentGate.IntentTier.SAME_FAMILY)
        );
        ProductIntent intent = new ProductIntent(
                "sess1", "test", "test_family",
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "Brand", "", "", false,
                Map.of(), Map.of(), List.of(), List.of(), List.of(), 0.9, "test"
        );
        SearchCandidatePool pool = new SearchCandidatePool(
                "sess1", "run-1", "id-1",
                intent, "DefaultProductIntentStrategy",
                SearchFilter.empty(), classified, 100
        );

        // Save classifiedPool
        ArgumentCaptor<String> poolJson = ArgumentCaptor.forClass(String.class);
        cache.saveClassifiedPool("sess1", pool);
        verify(valueOps).set(
                eq("visioncart:session:classified_pool:sess1"),
                poolJson.capture(),
                eq(30L), eq(TimeUnit.MINUTES)
        );
        when(valueOps.get("visioncart:session:classified_pool:sess1")).thenReturn(poolJson.getValue());

        // Also save old-style candidates with only 1 product (simulating Top50)
        cache.saveCandidates("sess1", List.of(p1));
        ArgumentCaptor<String> candidatesJson = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(
                eq("visioncart:session:candidates:sess1"),
                candidatesJson.capture(),
                eq(30L), eq(TimeUnit.MINUTES)
        );
        when(valueOps.get("visioncart:session:candidates:sess1")).thenReturn(candidatesJson.getValue());

        // getBestCandidates should return the classifiedPool (3 products), not old candidates (1 product)
        List<ProductCard> best = cache.getBestCandidates("sess1");
        assertThat(best).hasSize(3);
    }

    @Test
    void getBestCandidatesFallsBackToLegacyCandidatesWhenClassifiedPoolMissing() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ObjectMapper objectMapper = new ObjectMapper();
        CandidateSessionCache cache = new CandidateSessionCache(redisTemplate, objectMapper);

        // No classifiedPool saved — only old-style candidates
        ProductCard p1 = product("p1", "Product A", "淘宝", 49.0);
        cache.saveCandidates("sess1", List.of(p1));
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(
                eq("visioncart:session:candidates:sess1"),
                jsonCaptor.capture(),
                eq(30L), eq(TimeUnit.MINUTES)
        );
        when(valueOps.get("visioncart:session:candidates:sess1")).thenReturn(jsonCaptor.getValue());
        when(valueOps.get("visioncart:session:classified_pool:sess1")).thenReturn(null);

        List<ProductCard> best = cache.getBestCandidates("sess1");
        assertThat(best).extracting(ProductCard::id).containsExactly("p1");
    }

    @Test
    void saveDefaultClassifiedPool_promotesLegacyProductsToSearchRunPool() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ObjectMapper objectMapper = new ObjectMapper();
        CandidateSessionCache cache = new CandidateSessionCache(redisTemplate, objectMapper);

        ProductCard p1 = product("p1", "Product A", "taobao", 49.0);
        ProductCard p2 = product("p2", "Product B", "pdd", 59.0);
        cache.saveDefaultClassifiedPool("sess1", List.of(p1, p2));

        ArgumentCaptor<String> poolJson = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(
                eq("visioncart:session:classified_pool:sess1"),
                poolJson.capture(),
                eq(30L), eq(TimeUnit.MINUTES)
        );
        when(valueOps.get("visioncart:session:classified_pool:sess1")).thenReturn(poolJson.getValue());

        List<ProductCard> best = cache.getBestCandidates("sess1");
        assertThat(best).extracting(ProductCard::id).containsExactly("p1", "p2");
        SearchCandidatePool pool = cache.getClassifiedPool("sess1").orElseThrow();
        assertThat(pool.productIntent().canonicalProduct()).isBlank();
        assertThat(pool.classifiedPool()).allSatisfy(classified ->
                assertThat(classified.tier()).isEqualTo(IntentGate.IntentTier.EXACT_MAIN));
    }

    @Test
    void deleteRemovesBothCandidatesAndClassifiedPool() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.delete(anyString())).thenReturn(true);
        ObjectMapper objectMapper = new ObjectMapper();
        CandidateSessionCache cache = new CandidateSessionCache(redisTemplate, objectMapper);

        cache.delete("sess1");

        // Should delete all three keys
        verify(redisTemplate).delete("visioncart:session:candidates:sess1");
        verify(redisTemplate).delete("visioncart:session:candidates:search:sess1");
        verify(redisTemplate).delete("visioncart:session:classified_pool:sess1");
    }

    @Test
    void classifiedPool_toProductCards_stripsTiers() {
        ProductCard p1 = product("p1", "Main", "淘宝", 99.0);
        ProductCard p2 = product("p2", "Accessory", "拼多多", 19.0);
        List<VerticalSearchStrategy.ClassifiedProduct> classified = List.of(
                new VerticalSearchStrategy.ClassifiedProduct(p1, IntentGate.IntentTier.EXACT_MAIN),
                new VerticalSearchStrategy.ClassifiedProduct(p2, IntentGate.IntentTier.RELATED_ACCESSORY)
        );
        ProductIntent intent = new ProductIntent(
                "sess1", "test", "test_family",
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "", "", "", false,
                Map.of(), Map.of(), List.of(), List.of(), List.of(), 0.9, "test"
        );
        SearchCandidatePool pool = new SearchCandidatePool(
                "sess1", "run-1", "id-1",
                intent, "DefaultProductIntentStrategy",
                SearchFilter.empty(), classified, 50
        );

        List<ProductCard> cards = pool.toProductCards();
        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).id()).isEqualTo("p1");
        assertThat(cards.get(1).id()).isEqualTo("p2");
        // Tiers are stripped
        assertThat(pool.poolSize()).isEqualTo(2);
    }
}
