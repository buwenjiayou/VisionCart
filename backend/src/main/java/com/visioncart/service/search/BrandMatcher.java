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
            // 手机/数码
            List.of("Apple", "苹果"),
            List.of("Huawei", "华为"),
            List.of("Xiaomi", "小米", "MI"),
            List.of("Honor", "荣耀"),
            List.of("OPPO"),
            List.of("vivo"),
            List.of("Samsung", "三星"),
            List.of("OnePlus", "一加"),
            List.of("Realme", "真我"),
            List.of("Meizu", "魅族"),
            List.of("Google", "谷歌"),
            // 电脑/外设
            List.of("Lenovo", "联想"),
            List.of("ThinkPad"),
            List.of("Dell", "戴尔"),
            List.of("HP", "惠普"),
            List.of("ASUS", "华硕"),
            List.of("Acer", "宏碁"),
            List.of("MSI", "微星"),
            List.of("Logitech", "罗技"),
            List.of("Razer", "雷蛇"),
            List.of("ZOWIE", "卓威"),
            List.of("Microsoft", "微软"),
            List.of("Rapoo", "雷柏"),
            List.of("Corsair", "海盗船"),
            // 音频
            List.of("Sony", "索尼"),
            List.of("Bose"),
            List.of("JBL"),
            List.of("Beats"),
            List.of("Marshall"),
            List.of("Sennheiser", "森海塞尔"),
            List.of("Audio-Technica", "铁三角"),
            List.of("Harman Kardon", "哈曼卡顿"),
            List.of("Shure", "舒尔"),
            List.of("Edifier", "漫步者"),
            List.of("1MORE", "万魔"),
            // 家电/个护
            List.of("Dyson", "戴森"),
            List.of("Philips", "飞利浦"),
            List.of("Panasonic", "松下"),
            List.of("Braun", "博朗"),
            List.of("Gillette", "吉列"),
            List.of("Midea", "美的"),
            List.of("Gree", "格力"),
            List.of("Haier", "海尔"),
            List.of("Roborock", "石头"),
            List.of("Dreame", "追觅"),
            List.of("Ecovacs", "科沃斯"),
            // 运动/服饰
            List.of("Nike", "耐克"),
            List.of("Adidas", "阿迪达斯"),
            List.of("Li-Ning", "李宁"),
            List.of("ANTA", "安踏"),
            List.of("Xtep", "特步"),
            List.of("PUMA", "彪马"),
            List.of("New Balance", "新百伦"),
            List.of("Under Armour", "安德玛"),
            List.of("FILA", "斐乐"),
            List.of("Skechers", "斯凯奇"),
            // 配件/充电
            List.of("Anker", "安克"),
            List.of("Baseus", "倍思"),
            List.of("UGREEN", "绿联"),
            List.of("Momax", "摩米士"),
            List.of("Belkin", "贝尔金"),
            // 美妆
            List.of("L'Oreal", "欧莱雅"),
            List.of("Estee Lauder", "雅诗兰黛"),
            List.of("LANEIGE", "兰芝"),
            List.of("Innisfree", "悦诗风吟"),
            // 个护小家电（易混淆品牌）
            List.of("Flyco", "飞科"),
            List.of("FengErPu", "锋尔普"),
            List.of("Povos", "奔腾"),
            List.of("SID", "超人"),
            List.of("Remington", "雷明顿"),
            List.of("Wahl", "华尔"),
            List.of("Conair"),
            List.of("Andis"),
            // 厨房小电
            List.of("Supor", "苏泊尔"),
            List.of("Joyoung", "九阳"),
            List.of("Bear", "小熊"),
            List.of("Aux", "奥克斯"),
            List.of("Meling", "美菱"),
            // 音频补充
            List.of("QCY"),
            List.of("Haylou"),
            List.of("Soundcore", "声阔"),
            List.of("Nothing")
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

    /**
     * Check if a brand is in the known brand dictionary.
     */
    public static boolean isKnownBrand(String brand) {
        if (StringUtils.isBlank(brand)) return false;
        String normalized = normalize(brand);
        return !normalized.isBlank() && ALIAS_TO_CANONICAL.containsKey(normalized);
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

    /**
     * Find the closest known brand to the given brand using Levenshtein distance.
     * Returns null if no brand is close enough (threshold: 2 edits or 30% of length).
     */
    public static String findClosestBrand(String brand) {
        if (StringUtils.isBlank(brand)) return null;
        String normalized = normalize(brand);
        if (normalized.isBlank()) return null;

        String bestMatch = null;
        int bestDistance = Integer.MAX_VALUE;

        for (List<String> group : BRAND_GROUPS) {
            for (String alias : group) {
                String normalizedAlias = normalize(alias);
                if (normalizedAlias.isBlank()) continue;
                int distance = levenshtein(normalized, normalizedAlias);
                // Threshold: max 2 edits, or 30% of the longer string
                int threshold = Math.min(2, Math.max(1, (int) (Math.max(normalized.length(), normalizedAlias.length()) * 0.3)));
                if (distance < bestDistance && distance <= threshold) {
                    bestDistance = distance;
                    bestMatch = group.get(0); // Return canonical form
                }
            }
        }
        return bestMatch;
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
