package com.visioncart.service.nlp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedNlpParserTest {

    private final RuleBasedNlpParser parser = new RuleBasedNlpParser();

    @Test
    void parsesPriceRatingColorAndSort() {
        RuleBasedNlpParser.ParsedFilter parsed = parser.parse("1000 元以内黑色款，要评价 4.8 分以上，按销量排");

        assertThat(parsed.complete()).isTrue();
        assertThat(parsed.filter().priceRange().max()).isEqualTo(1000);
        assertThat(parsed.filter().colors()).contains("黑色");
        assertThat(parsed.filter().ratingMin()).isEqualTo(4.8);
        assertThat(parsed.filter().sortBy()).isEqualTo("sales");
    }
}
