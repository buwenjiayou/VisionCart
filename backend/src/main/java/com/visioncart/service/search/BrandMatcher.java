package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class BrandMatcher {

    private static final List<List<String>> BRAND_GROUPS = List.of(
            List.of("Apple", "苹果"),
            List.of("Huawei", "华为"),
            List.of("Xiaomi", "小米", "MI"),
            List.of("Honor", "荣耀"),
            List.of("OPPO"),
            List.of("vivo"),
            List.of("Samsung", "三星"),
            List.of("Lenovo", "联想"),
            List.of("ThinkPad"),
            List.of("Dell", "戴尔"),
            List.of("HP", "惠普"),
            List.of("ASUS", "华硕"),
            List.of("Acer", "宏碁"),
            List.of("Logitech", "罗技"),
            List.of("Razer", "雷蛇"),
            List.of("ZOWIE", "卓威"),
            List.of("Microsoft", "微软"),
            List.of("Nike", "耐克"),
            List.of("Adidas", "阿迪达斯"),
            List.of("Li-Ning", "李宁"),
            List.of("ANTA", "安踏"),
            List.of("Xtep", "特步"),
            List.of("PUMA", "彪马"),
            List.of("New Balance", "新百伦", "NB"),
            List.of("Under Armour", "安德玛"),
            List.of("FILA", "斐乐")
    );

    private static final Map<String, String> ALIAS_TO_CANONICAL = buildAliasMap();
    private static final List<Map.Entry<String, String>> ALIASES_BY_LENGTH = ALIAS_TO_CANONICAL.entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length()).reversed())
            .toList();

    private BrandMatcher() {
    }

    public static String canonical(String brand) {
        String useful = SearchTextUtils.useful(brand);
        if (useful.isBlank()) {
            return "";
        }
        String normalized = normalize(useful);
        return ALIAS_TO_CANONICAL.getOrDefault(normalized, normalized);
    }

    public static boolean sameBrand(String expectedBrand, String candidateBrand) {
        String expected = canonical(expectedBrand);
        String candidate = canonical(candidateBrand);
        return !expected.isBlank() && expected.equals(candidate);
    }

    public static boolean productMatchesExpectedBrand(ProductCard product, String expectedBrand) {
        String expected = canonical(expectedBrand);
        if (expected.isBlank() || product == null) {
            return false;
        }
        if (sameBrand(expectedBrand, product.brand())) {
            return true;
        }
        return textContainsCanonical(searchableText(product), expected);
    }

    public static boolean hasConflictingBrand(ProductCard product, String expectedBrand) {
        String expected = canonical(expectedBrand);
        if (expected.isBlank() || product == null) {
            return false;
        }

        String productBrand = SearchTextUtils.useful(product.brand());
        if (!productBrand.isBlank()) {
            return !sameBrand(expectedBrand, productBrand);
        }

        String detected = firstKnownCanonical(searchableText(product));
        return !detected.isBlank() && !expected.equals(detected);
    }

    public static String inferBrand(String title, String shopName) {
        String detected = firstKnownCanonical(StringUtils.defaultString(title) + " " + StringUtils.defaultString(shopName));
        if (detected.isBlank()) {
            return "";
        }
        return BRAND_GROUPS.stream()
                .filter(group -> canonical(group.get(0)).equals(detected))
                .findFirst()
                .map(group -> group.get(0))
                .orElse(detected);
    }

    private static boolean textContainsCanonical(String text, String expectedCanonical) {
        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) {
            return false;
        }
        String latinText = latinSearchText(text);
        return ALIAS_TO_CANONICAL.entrySet().stream()
                .anyMatch(entry -> expectedCanonical.equals(entry.getValue())
                        && containsAlias(normalizedText, latinText, entry.getKey()));
    }

    private static String firstKnownCanonical(String text) {
        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) {
            return "";
        }
        String latinText = latinSearchText(text);
        return ALIASES_BY_LENGTH.stream()
                .filter(entry -> containsAlias(normalizedText, latinText, entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    private static String searchableText(ProductCard product) {
        return String.join(" ",
                StringUtils.defaultString(product.title()),
                StringUtils.defaultString(product.brand()),
                StringUtils.defaultString(product.shopName()));
    }

    private static Map<String, String> buildAliasMap() {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (List<String> group : BRAND_GROUPS) {
            if (group.isEmpty()) {
                continue;
            }
            String canonical = normalize(group.get(0));
            for (String alias : group) {
                String normalized = normalize(alias);
                if (!normalized.isBlank()) {
                    aliases.put(normalized, canonical);
                }
            }
        }
        return aliases;
    }

    private static String normalize(String value) {
        return StringUtils.defaultString(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    private static String latinSearchText(String value) {
        return " " + StringUtils.defaultString(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim() + " ";
    }

    private static boolean containsAlias(String normalizedText, String latinText, String normalizedAlias) {
        if (normalizedAlias.matches("[a-z0-9]+")) {
            return Pattern.compile("(^|\\s)" + Pattern.quote(normalizedAlias) + "($|\\s)")
                    .matcher(latinText)
                    .find();
        }
        return normalizedText.contains(normalizedAlias);
    }
}
