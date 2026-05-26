package com.visioncart.service.nlp;

import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.SearchFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiOutputValidatorTest {

    @Test
    void validFilterPassesThrough() {
        SearchFilter input = new SearchFilter(
                new PriceRange(100.0, 200.0),
                List.of("京东", "淘宝"),
                true,
                List.of("红色"),
                List.of("Nike"),
                4.5,
                "price",
                "asc",
                "连衣裙"
        );
        SearchFilter result = AiOutputValidator.validate(input);
        assertThat(result.sortBy()).isEqualTo("price");
        assertThat(result.platforms()).containsExactly("京东", "淘宝");
        assertThat(result.ratingMin()).isEqualTo(4.5);
        assertThat(result.sortOrder()).isEqualTo("asc");
    }

    @Test
    void invalidSortByNulled() {
        SearchFilter input = new SearchFilter(null, List.of(), null, List.of(), List.of(), null, "relevance", "desc", null);
        SearchFilter result = AiOutputValidator.validate(input);
        assertThat(result.sortBy()).isNull();
    }

    @Test
    void invalidPlatformsRemoved() {
        SearchFilter input = new SearchFilter(null, List.of("京东", "亚马逊", "淘宝"), null, List.of(), List.of(), null, null, "desc", null);
        SearchFilter result = AiOutputValidator.validate(input);
        assertThat(result.platforms()).containsExactly("京东", "淘宝");
    }

    @Test
    void outOfRangeRatingNulled() {
        SearchFilter input = new SearchFilter(null, List.of(), null, List.of(), List.of(), 6.0, null, "desc", null);
        SearchFilter result = AiOutputValidator.validate(input);
        assertThat(result.ratingMin()).isNull();
    }

    @Test
    void negativeRatingNulled() {
        SearchFilter input = new SearchFilter(null, List.of(), null, List.of(), List.of(), -1.0, null, "desc", null);
        SearchFilter result = AiOutputValidator.validate(input);
        assertThat(result.ratingMin()).isNull();
    }

    @Test
    void negativePricesCorrected() {
        SearchFilter input = new SearchFilter(new PriceRange(-10.0, -5.0), List.of(), null, List.of(), List.of(), null, null, "desc", null);
        SearchFilter result = AiOutputValidator.validate(input);
        assertThat(result.priceRange().min()).isNull();
        assertThat(result.priceRange().max()).isNull();
    }

    @Test
    void invalidSortOrderDefaultsToDesc() {
        SearchFilter input = new SearchFilter(null, List.of(), null, List.of(), List.of(), null, null, "random", null);
        SearchFilter result = AiOutputValidator.validate(input);
        assertThat(result.sortOrder()).isEqualTo("desc");
    }

    @Test
    void nullFilterReturnsEmpty() {
        SearchFilter result = AiOutputValidator.validate(null);
        assertThat(result).isEqualTo(SearchFilter.empty());
    }

    @Test
    void longStringsTruncated() {
        String longString = "a".repeat(200);
        SearchFilter input = new SearchFilter(null, List.of(), null, List.of(longString), List.of(longString), null, null, "desc", longString);
        SearchFilter result = AiOutputValidator.validate(input);
        assertThat(result.colors().get(0)).hasSize(100);
        assertThat(result.brands().get(0)).hasSize(100);
        assertThat(result.keyword()).hasSize(100);
    }

    @Test
    void validRatingRangeAccepted() {
        SearchFilter input = new SearchFilter(null, List.of(), null, List.of(), List.of(), 3.5, null, "desc", null);
        SearchFilter result = AiOutputValidator.validate(input);
        assertThat(result.ratingMin()).isEqualTo(3.5);
    }
}
