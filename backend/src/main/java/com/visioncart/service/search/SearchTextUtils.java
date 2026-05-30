package com.visioncart.service.search;

import com.visioncart.api.dto.SearchFilter;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class SearchTextUtils {

    public static final String ATTR_BRAND = "品牌";
    public static final String ATTR_COLOR = "颜色";
    public static final String ATTR_STYLE = "款式";
    public static final String ATTR_CATEGORY = "类目";
    public static final String ATTR_KEYWORD = "关键词";
    public static final String ATTR_KEYWORDS = "__keywords";
    public static final String ATTR_CATEGORY_CHAIN = "__category_chain";
    public static final String ATTR_BRAND_RELIABLE = "__brand_reliable";

    private SearchTextUtils() {}

    public static String useful(String value) {
        String trimmed = StringUtils.defaultString(value).trim();
        return trimmed.isBlank()
                || "未知".equals(trimmed)
                || "未识别".equals(trimmed)
                || "通用".equals(trimmed)
                || "常规".equals(trimmed)
                || "常规款".equals(trimmed)
                || "常规款式".equals(trimmed)
                || "普通款".equals(trimmed)
                || "标准款".equals(trimmed)
                || "基础款".equals(trimmed)
                || "无".equals(trimmed)
                || "无品牌".equals(trimmed)
                || "null".equalsIgnoreCase(trimmed)
                || "unknown".equalsIgnoreCase(trimmed)
                ? ""
                : trimmed;
    }

    public static String searchKeyword(Map<String, String> attributes, SearchFilter filter, String fallback) {
        if (filter != null && filter.keyword() != null && !filter.keyword().isBlank()) {
            return filter.keyword().trim();
        }

        Map<String, String> source = attributes == null ? Map.of() : attributes;
        String recognizedKeyword = useful(source.get(ATTR_KEYWORD));
        if (StringUtils.isNotBlank(recognizedKeyword)) {
            return recognizedKeyword;
        }

        String joined = Arrays.stream(new String[] {
                        source.get(ATTR_BRAND),
                        source.get(ATTR_COLOR),
                        source.get(ATTR_STYLE),
                        source.get(ATTR_CATEGORY)
                })
                .map(SearchTextUtils::useful)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(" "));
        return StringUtils.defaultIfBlank(joined, fallback);
    }

    public static long parseHumanCount(String value) {
        String text = StringUtils.defaultString(value).trim().replace(",", "");
        if (text.isBlank()) {
            return 0;
        }

        double multiplier = 1;
        if (text.contains("万")) {
            multiplier = 10_000;
        } else if (text.contains("千")) {
            multiplier = 1_000;
        }

        String number = text.replaceAll("[^0-9.]", "");
        if (number.isBlank()) {
            return 0;
        }
        try {
            return Math.round(Double.parseDouble(number) * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static long maxHumanCount(String... values) {
        long max = 0;
        for (String value : values) {
            max = Math.max(max, parseHumanCount(value));
        }
        return max;
    }

    public static String normalizeUrl(String url) {
        String trimmed = StringUtils.defaultString(url).trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    public static boolean relevantToCoreProduct(String title, Map<String, String> attributes) {
        String normalizedTitle = StringUtils.defaultString(title).toLowerCase(Locale.ROOT);
        String category = useful(attributes == null ? null : attributes.get(ATTR_CATEGORY));
        if (StringUtils.isBlank(category)) {
            return true;
        }

        String normalizedCategory = category.toLowerCase(Locale.ROOT);
        if (normalizedTitle.contains(normalizedCategory)) {
            return true;
        }

        String core = coreProductToken(category);
        if (StringUtils.isBlank(core) || core.length() < 2) {
            return true;
        }
        return normalizedTitle.contains(core.toLowerCase(Locale.ROOT));
    }

    public static String coreProductToken(Map<String, String> attributes) {
        if (attributes == null) {
            return "";
        }
        String category = useful(attributes.get(ATTR_CATEGORY));
        if (StringUtils.isNotBlank(category)) {
            return coreProductToken(category);
        }
        String keyword = useful(attributes.get(ATTR_KEYWORD));
        return coreProductToken(keyword);
    }

    public static String coreProductToken(String value) {
        String clean = useful(value).replaceAll("[\\s/]+", "");
        if (clean.isBlank()) {
            return "";
        }
        for (String term : CORE_PRODUCT_TERMS) {
            if (clean.contains(term)) {
                return term;
            }
        }
        List<String> tokens = categoryTokens(clean.toLowerCase(Locale.ROOT));
        if (tokens.isEmpty()) {
            return clean;
        }
        return tokens.get(tokens.size() - 1);
    }

    public static String inferBrand(String title, String shopName) {
        return BrandMatcher.inferBrand(title, shopName);
    }

    public static List<String> splitSearchTerms(String value) {
        String useful = useful(value);
        if (useful.isBlank()) {
            return List.of();
        }
        return Arrays.stream(useful.split("[,，;；|\\n\\r]+"))
                .map(SearchTextUtils::useful)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    public static boolean containsNormalized(String text, String token) {
        String normalizedToken = normalizeForMatch(token);
        return !normalizedToken.isBlank()
                && normalizeForMatch(text).contains(normalizedToken);
    }

    public static String normalizeForMatch(String value) {
        return StringUtils.defaultString(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    public static String salesLabel(long sales, String source) {
        if (sales <= 0) {
            return "销量未知";
        }
        String count = formatHumanCount(sales);
        if ("annual".equals(source)) {
            return "年销 " + count;
        }
        if ("promotion".equals(source)) {
            return "推广销量 " + count;
        }
        return "30天销量 " + count;
    }

    private static String formatHumanCount(long value) {
        if (value >= 10_000) {
            double tenThousands = value / 10_000.0;
            return String.format(Locale.ROOT, tenThousands >= 10 ? "%.0f万+" : "%.1f万+", tenThousands);
        }
        return value + "+";
    }

    private static List<String> categoryTokens(String category) {
        String clean = category.replaceAll("[\\s/]+", "");
        List<String> tokens = new ArrayList<>();
        if (clean.length() <= 4 && clean.length() >= 2) {
            tokens.add(clean.substring(0, 2));
            String tail = clean.substring(clean.length() - 2);
            if (!tokens.contains(tail)) {
                tokens.add(tail);
            }
            return tokens.stream()
                    .filter(token -> !GENERIC_CATEGORY_TOKENS.contains(token))
                    .toList();
        }
        for (int i = 0; i < clean.length() - 1; i++) {
            String token = clean.substring(i, i + 2);
            if (!GENERIC_CATEGORY_TOKENS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static final List<String> GENERIC_CATEGORY_TOKENS = List.of(
            "商品", "产品", "配件", "用品", "通用", "其他"
    );

    private static final List<String> CORE_PRODUCT_TERMS = List.of(
            "运动鞋", "篮球鞋", "跑鞋", "板鞋", "休闲鞋", "训练鞋",
            "鼠标", "键盘", "耳机", "手机", "电脑", "笔记本", "显示器", "手表",
            "相机", "路由器", "充电器", "数据线", "音箱", "鞋", "包", "衣", "裤", "裙"
    );

}
