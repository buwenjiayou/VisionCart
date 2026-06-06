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

    @Test
    void parsesChineseNumeralMax() {
        RuleBasedNlpParser.ParsedFilter parsed = parser.parse("低于一万");
        assertThat(parsed.filter().priceRange().max()).isEqualTo(10000);
        assertThat(parsed.complete()).isTrue();
    }

    @Test
    void parsesArabicNumeralWithWan() {
        RuleBasedNlpParser.ParsedFilter parsed = parser.parse("低于1万");
        assertThat(parsed.filter().priceRange().max()).isEqualTo(10000);
        assertThat(parsed.complete()).isTrue();
    }

    @Test
    void parsesChineseNumeralMin() {
        RuleBasedNlpParser.ParsedFilter parsed = parser.parse("超过五千");
        assertThat(parsed.filter().priceRange().min()).isEqualTo(5000);
        assertThat(parsed.complete()).isTrue();
    }

    @Test
    void parsesDecimalWan() {
        RuleBasedNlpParser.ParsedFilter parsed = parser.parse("1.5万以内");
        assertThat(parsed.filter().priceRange().max()).isEqualTo(15000);
        assertThat(parsed.complete()).isTrue();
    }

    @Test
    void parsesQian() {
        RuleBasedNlpParser.ParsedFilter parsed = parser.parse("价格高于3千");
        assertThat(parsed.filter().priceRange().min()).isEqualTo(3000);
        assertThat(parsed.complete()).isTrue();
    }

    @Test
    void smallPriceIsComplete() {
        // "低于1" is a valid price filter (Bug fix: floor changed from >1 to >0)
        RuleBasedNlpParser.ParsedFilter parsed = parser.parse("低于1");
        assertThat(parsed.complete()).isTrue();
    }

    // === 中文数字全面覆盖 ===

    @Test
    void chineseShi() {
        // "十" = 10
        assertThat(parser.parse("低于十").filter().priceRange().max()).isEqualTo(10);
    }

    @Test
    void chineseShiWan() {
        // "十万" = 100000
        assertThat(parser.parse("低于十万").filter().priceRange().max()).isEqualTo(100000);
    }

    @Test
    void chineseBaiWan() {
        // "一百万" = 1000000
        assertThat(parser.parse("超过一百万").filter().priceRange().min()).isEqualTo(1000000);
    }

    @Test
    void chineseErBaiWan() {
        // "二百万" = 2000000
        assertThat(parser.parse("二百万以内").filter().priceRange().max()).isEqualTo(2000000);
    }

    @Test
    void chineseSanBaiWu() {
        // "三百五" = 350
        assertThat(parser.parse("超过三百五").filter().priceRange().min()).isEqualTo(350);
    }

    @Test
    void chineseErBaiYiShiWu() {
        // "二百一十五" = 215
        assertThat(parser.parse("低于二百一十五").filter().priceRange().max()).isEqualTo(215);
    }

    @Test
    void chineseQianToQian() {
        // "一千到五千" = 1000-5000
        RuleBasedNlpParser.ParsedFilter parsed = parser.parse("一千到五千");
        assertThat(parsed.filter().priceRange().min()).isEqualTo(1000);
        assertThat(parsed.filter().priceRange().max()).isEqualTo(5000);
    }

    @Test
    void chineseYi() {
        // "一亿" = 100000000
        assertThat(parser.parse("低于一亿").filter().priceRange().max()).isEqualTo(100000000L);
    }

    @Test
    void chineseLiangWan() {
        // "两万" = 20000
        assertThat(parser.parse("低于两万").filter().priceRange().max()).isEqualTo(20000);
    }

    @Test
    void chineseQianWuBai() {
        // "一千五" = 1500
        assertThat(parser.parse("超过一千五").filter().priceRange().min()).isEqualTo(1500);
    }
}
