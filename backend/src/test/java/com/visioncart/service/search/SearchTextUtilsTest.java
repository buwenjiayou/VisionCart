package com.visioncart.service.search;

import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.SearchFilter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchTextUtilsTest {

    @Test
    void searchKeywordPrefersRecognizedKeyword() {
        String keyword = SearchTextUtils.searchKeyword(
                Map.of(
                        "品牌", "未知",
                        "颜色", "白色",
                        "款式", "常规款式",
                        "类目", "无线鼠标",
                        "关键词", "白色无线鼠标"
                ),
                emptyFilter(),
                "商品"
        );

        assertEquals("白色无线鼠标", keyword);
    }

    @Test
    void searchKeywordFallsBackToUsefulAttributeParts() {
        String keyword = SearchTextUtils.searchKeyword(
                Map.of(
                        "品牌", "未知",
                        "颜色", "白色",
                        "款式", "常规款式",
                        "类目", "无线鼠标"
                ),
                emptyFilter(),
                "商品"
        );

        assertEquals("白色 无线鼠标", keyword);
    }

    @Test
    void userKeywordOverridesRecognizedAttributes() {
        SearchFilter filter = new SearchFilter(
                new PriceRange(null, null),
                List.of(),
                null,
                List.of(),
                List.of(),
                null,
                null,
                "desc",
                "罗技鼠标"
        );

        assertEquals("罗技鼠标", SearchTextUtils.searchKeyword(Map.of("关键词", "白色无线鼠标"), filter, "商品"));
    }

    @Test
    void parsesChineseSalesText() {
        assertEquals(90_000L, SearchTextUtils.parseHumanCount("9万+"));
        assertEquals(15_000L, SearchTextUtils.parseHumanCount("1.5万"));
        assertEquals(1000L, SearchTextUtils.parseHumanCount("1000"));
    }

    @Test
    void normalizesProtocolRelativeUrl() {
        assertEquals("https://s.click.taobao.com/t", SearchTextUtils.normalizeUrl("//s.click.taobao.com/t"));
    }

    @Test
    void maxHumanCountIgnoresLeadingZeroCandidates() {
        assertEquals(5_000L, SearchTextUtils.maxHumanCount("0", "10", "5000+"));
        assertEquals(200_000L, SearchTextUtils.maxHumanCount("0", "50", "20\u4e07+"));
    }

    @Test
    void detectsCoreProductRelevance() {
        assertEquals(true, SearchTextUtils.relevantToCoreProduct(
                "白色静音无线办公鼠标",
                Map.of("类目", "无线鼠标", "关键词", "白色无线鼠标")));
        assertEquals(true, SearchTextUtils.relevantToCoreProduct(
                "罗技G102二代有线游戏鼠标RGB背光轻量设计",
                Map.of("类目", "无线鼠标", "关键词", "白色无线鼠标")));
        assertEquals(false, SearchTextUtils.relevantToCoreProduct(
                "肩颈按摩仪热敷捶打腰部按摩器",
                Map.of("类目", "无线鼠标", "关键词", "白色无线鼠标")));
        assertEquals(false, SearchTextUtils.relevantToCoreProduct(
                "舒适鞋垫透气减震",
                Map.of("类目", "运动鞋", "关键词", "Nike 运动鞋")));
    }

    @Test
    void buildsPlatformSpecificFallbackQueries() {
        Map<String, String> attributes = Map.of(
                "品牌", "未知",
                "颜色", "白色",
                "类目", "无线鼠标",
                "关键词", "白色无线鼠标"
        );

        assertEquals(true, SearchQueryBuilder.taobaoQueries(attributes, emptyFilter(), "商品").contains("白色无线鼠标"));
        assertEquals(true, SearchQueryBuilder.pddQueries(attributes, emptyFilter(), "商品").contains("鼠标"));
    }

    @Test
    void negativeKeywordDoesNotBecomePositivePlatformQuery() {
        Map<String, String> attributes = Map.of("类目", "无线鼠标", "关键词", "白色无线鼠标");
        SearchFilter filter = new SearchFilter(
                new PriceRange(null, null),
                List.of(),
                null,
                List.of(),
                List.of(),
                null,
                null,
                "desc",
                "!黑色,支架"
        );

        List<String> queries = SearchQueryBuilder.taobaoQueries(attributes, filter, "商品");

        assertEquals(false, queries.stream().anyMatch(query -> query.contains("!") || query.contains("支架")));
        assertEquals(List.of("黑色", "支架"), SearchTextUtils.negativeTerms(filter.keyword()));
    }

    @Test
    void productRelevanceAcceptsKeywordCoreWhenCategoryIsWrong() {
        assertEquals(true, SearchTextUtils.isProductRelevant(
                "罗技白色无线办公鼠标",
                Map.of("类目", "肩颈按摩仪", "关键词", "无线鼠标")));
    }

    @Test
    void buildsPreciseModelQueriesBeforeBroadQueries() {
        Map<String, String> attributes = Map.of(
                "品牌", "罗技",
                "型号", "M650",
                "类目", "无线鼠标",
                "关键词", "罗技 M650 鼠标"
        );

        List<String> queries = SearchQueryBuilder.taobaoQueries(attributes, emptyFilter(), "商品");

        assertEquals("罗技 M650 鼠标", queries.get(0));
        assertEquals(true, queries.contains("罗技 M650 无线鼠标"));
    }

    @Test
    void multipleKeywordsParticipateInIntentQueries() {
        Map<String, String> attributes = Map.of(
                "品牌", "罗技",
                SearchTextUtils.ATTR_KEYWORDS, "罗技 M650 鼠标,白色无线鼠标",
                "类目", "无线鼠标"
        );

        List<String> queries = SearchQueryBuilder.pddQueries(attributes, emptyFilter(), "商品");

        assertEquals(true, queries.contains("罗技 M650 鼠标"));
        assertEquals(true, queries.contains("罗技 白色无线鼠标"));
    }

    @Test
    void platformFetchSizeExpandsCandidatePool() {
        assertEquals(90, SearchQueryBuilder.platformFetchSize(20));
        assertEquals(100, SearchQueryBuilder.platformFetchSize(50));
    }

    private SearchFilter emptyFilter() {
        return new SearchFilter(
                new PriceRange(null, null),
                List.of(),
                null,
                List.of(),
                List.of(),
                null,
                null,
                "desc",
                null
        );
    }
}
