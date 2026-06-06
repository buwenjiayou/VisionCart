package com.visioncart.service.suggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.SuggestionCard;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuggestionCardCacheTest {

    @Test
    void savesAndReadsSessionCards() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        SuggestionCard card = new SuggestionCard("c1", "Sort", "Low price", "money", "sort_by_price_asc", 90);
        String json = new ObjectMapper().writeValueAsString(List.of(card));
        assertThat(new ObjectMapper().readValue(json, SuggestionCard[].class)).hasSize(1);
        doReturn(json).when(valueOps).get(anyString());
        SuggestionCardCache cache = new SuggestionCardCache(redisTemplate, new ObjectMapper());

        cache.save("s1", List.of(card));
        List<SuggestionCard> result = cache.get("s1");

        verify(valueOps).set(eq("visioncart:session:suggestion-cards:s1"), contains("\"c1\""), any(Duration.class));
        verify(valueOps).get(eq("visioncart:session:suggestion-cards:s1"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("c1");
    }
}
