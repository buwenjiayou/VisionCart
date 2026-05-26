package com.visioncart.service.nlp;

import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.SearchFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedNlpParser {
    private static final Pattern PRICE_RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:到|至|-|~)\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern PRICE_MAX = Pattern.compile("(?:不超过|不超|最高|最多|以内|以下|低于)\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern PRICE_MAX_REVERSE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:以内|以下|不超过|不超|内)");
    private static final Pattern RATING_MIN = Pattern.compile("(\\d(?:\\.\\d)?)\\s*(?:分|星|评分|好评)?\\s*(?:以上|及以上|>=|≥)");

    public ParsedFilter parse(String input) {
        String normalized = normalize(input);
        Double min = null;
        Double max = null;
        Matcher range = PRICE_RANGE.matcher(normalized);
        if (range.find()) {
            min = Double.parseDouble(range.group(1));
            max = Double.parseDouble(range.group(2));
            if (min > max) {
                double temp = min;
                min = max;
                max = temp;
            }
        } else {
            Matcher maxMatcher = PRICE_MAX.matcher(normalized);
            if (maxMatcher.find()) {
                max = Double.parseDouble(maxMatcher.group(1));
            } else {
                Matcher reverseMatcher = PRICE_MAX_REVERSE.matcher(normalized);
                if (reverseMatcher.find()) {
                    max = Double.parseDouble(reverseMatcher.group(1));
                }
            }
        }

        Double ratingMin = null;
        Matcher rating = RATING_MIN.matcher(normalized);
        if (rating.find()) {
            ratingMin = Math.min(5.0, Double.parseDouble(rating.group(1)));
        }

        List<String> platforms = new ArrayList<>();
        if (containsAny(normalized, "京东", "jd")) {
            platforms.add("京东");
        }
        if (containsAny(normalized, "淘宝", "某宝")) {
            platforms.add("淘宝");
        }
        if (containsAny(normalized, "天猫", "tmall", "猫超")) {
            platforms.add("天猫");
        }
        if (containsAny(normalized, "拼多多", "pdd", "拼夕夕")) {
            platforms.add("拼多多");
        }

        List<String> colors = new ArrayList<>();
        if (containsAny(normalized, "黑", "黑色", "黑武士")) {
            colors.add("黑色");
        }
        if (containsAny(normalized, "白", "白色", "米白", "象牙白")) {
            colors.add("白色");
        }
        if (containsAny(normalized, "蓝", "蓝色", "深蓝", "藏青")) {
            colors.add("蓝色");
        }
        if (containsAny(normalized, "红", "红色", "酒红")) {
            colors.add("红色");
        }

        Boolean selfOperated = containsAny(normalized, "自营", "官方", "旗舰") ? Boolean.TRUE : null;
        String sortBy = null;
        String sortOrder = "desc";
        if (containsAny(normalized, "便宜", "低价", "实惠", "划算", "性价比")) {
            sortBy = "price";
            sortOrder = "asc";
        }
        if (containsAny(normalized, "销量", "热门", "爆款", "畅销")) {
            sortBy = "sales";
        }
        if (containsAny(normalized, "好评", "评分", "口碑")) {
            sortBy = "rating";
        }

        SearchFilter filter = new SearchFilter(
                new PriceRange(min, max),
                platforms,
                selfOperated,
                colors,
                List.of(),
                ratingMin,
                sortBy,
                sortOrder,
                null
        );
        // 关键字段: 价格、平台、排序 — 只有命中这些才算完整，颜色/自营/评分不够
        boolean hasPrice = min != null || max != null;
        boolean hasPlatform = !platforms.isEmpty();
        boolean hasSort = sortBy != null;
        boolean complete = hasPrice || hasPlatform || hasSort;
        return new ParsedFilter(filter, complete);
    }

    private String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT)
                .replaceAll("(?i)rmb|￥|¥|元|块钱|块", "")
                .replaceAll("\\s+", "");
    }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public record ParsedFilter(SearchFilter filter, boolean complete) {
    }
}
