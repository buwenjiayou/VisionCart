package com.visioncart.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandidateSessionCacheTest {

    @Test
    void preservesDisplayFieldsWhenReadingCachedCandidates() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        CandidateSessionCache cache = new CandidateSessionCache(redisTemplate, new ObjectMapper());

        ProductCard source = new ProductCard(
                "p1", "Electric shaver", "https://img.example/p1.jpg",
                BigDecimal.valueOf(199), BigDecimal.valueOf(299),
                "淘宝", true, "Official shop", 4.8, 1234, 0.91,
                List.of("coupon"), "https://detail.example/p1",
                "BrandX", "api", "1234 sold"
        );

        cache.saveCandidates("sess1", List.of(source));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("visioncart:session:candidates:sess1"), jsonCaptor.capture(), eq(30L), eq(TimeUnit.MINUTES));
        when(valueOps.get("visioncart:session:candidates:sess1")).thenReturn(jsonCaptor.getValue());

        List<ProductCard> restored = cache.getCandidates("sess1");

        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).imageUrl()).isEqualTo("https://img.example/p1.jpg");
        assertThat(restored.get(0).detailUrl()).isEqualTo("https://detail.example/p1");
        assertThat(restored.get(0).originalPrice()).isEqualByComparingTo("299");
        assertThat(restored.get(0).ratingSource()).isEqualTo("api");
        assertThat(restored.get(0).salesLabel()).isEqualTo("1234 sold");
    }

    @Test
    void storesAndMatchesSearchIdentityForFreshSearchResults() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        CandidateSessionCache cache = new CandidateSessionCache(redisTemplate, new ObjectMapper());

        cache.saveCandidates("sess2", List.of(product("p2")), "hash-123");

        verify(valueOps).set(eq("visioncart:session:candidates:search:sess2"),
                eq("hash-123"), eq(30L), eq(TimeUnit.MINUTES));
        when(valueOps.get("visioncart:session:candidates:search:sess2")).thenReturn("hash-123");

        assertThat(cache.matchesSearchIdentity("sess2", "hash-123")).isTrue();
        assertThat(cache.matchesSearchIdentity("sess2", "hash-old")).isFalse();
    }

    @Test
    void unversionedCandidateSaveClearsSearchIdentity() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        CandidateSessionCache cache = new CandidateSessionCache(redisTemplate, new ObjectMapper());

        cache.saveCandidates("sess3", List.of(product("p3")));

        verify(redisTemplate).delete("visioncart:session:candidates:search:sess3");
    }

    @Test
    void bestCandidatesFallsBackToLightweightCandidatesWhenClassifiedPoolIsMissing() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        CandidateSessionCache cache = new CandidateSessionCache(redisTemplate, new ObjectMapper());

        cache.saveCandidates("sess4", List.of(product("p4")));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("visioncart:session:candidates:sess4"), jsonCaptor.capture(), eq(30L), eq(TimeUnit.MINUTES));
        when(valueOps.get("visioncart:session:classified_pool:sess4")).thenReturn(null);
        when(valueOps.get("visioncart:session:candidates:sess4")).thenReturn(jsonCaptor.getValue());

        assertThat(cache.getBestCandidates("sess4")).extracting(ProductCard::id).containsExactly("p4");
        assertThat(cache.exists("sess4")).isTrue();
    }

    private ProductCard product(String id) {
        return new ProductCard(
                id, "Electric shaver", "https://img.example/" + id + ".jpg",
                BigDecimal.valueOf(199), BigDecimal.valueOf(299),
                "淘宝", true, "Official shop", 4.8, 1234, 0.91,
                List.of("coupon"), "https://detail.example/" + id,
                "BrandX", "api", "1234 sold"
        );
    }
}
