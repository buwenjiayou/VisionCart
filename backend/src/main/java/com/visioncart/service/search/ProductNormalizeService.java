package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes cross-platform product fields to a unified internal format.
 * Handles: brand names, shop types, colors, ratings, self-operated flags.
 */
@Service
public class ProductNormalizeService {

    // Official/self-operated shop indicators per platform
    private static final Map<String, Set<String>> OFFICIAL_SHOP_INDICATORS = Map.of(
            "jd", Set.of("自营", "京东自营", "官方旗舰店", "官方店"),
            "taobao", Set.of("天猫旗舰店", "品牌店", "官方旗舰店", "专卖店"),
            "tmall", Set.of("天猫旗舰店", "品牌店", "官方旗舰店"),
            "pdd", Set.of("官方", "百亿补贴", "品牌店"),
            "ebay", Set.of("Top Rated Seller", "Authorized Seller")
    );

    // Color normalization map
    private static final Map<String, String> COLOR_NORMALIZATION = Map.ofEntries(
            Map.entry("黑色", "black"), Map.entry("黑", "black"),
            Map.entry("白色", "white"), Map.entry("白", "white"),
            Map.entry("红色", "red"), Map.entry("红", "red"), Map.entry("酒红", "red"),
            Map.entry("蓝色", "blue"), Map.entry("蓝", "blue"), Map.entry("深蓝", "blue"),
            Map.entry("绿色", "green"), Map.entry("绿", "green"), Map.entry("墨绿", "green"),
            Map.entry("黄色", "yellow"), Map.entry("黄", "yellow"),
            Map.entry("粉色", "pink"), Map.entry("粉", "pink"),
            Map.entry("紫色", "purple"), Map.entry("紫", "purple"),
            Map.entry("灰色", "gray"), Map.entry("灰", "gray"), Map.entry("银灰", "gray"),
            Map.entry("金色", "gold"), Map.entry("金", "gold"),
            Map.entry("银色", "silver"), Map.entry("银", "silver"),
            Map.entry("棕色", "brown"), Map.entry("棕", "brown"),
            Map.entry("橙色", "orange"), Map.entry("橙", "orange")
    );

    /**
     * Normalize a product card's fields to unified internal format.
     */
    public ProductCard normalize(ProductCard card) {
        if (card == null) return null;

        String normalizedBrand = normalizeBrand(card.brand());
        String normalizedShopType = normalizeShopType(card.platform(), card.shopName());
        boolean selfOperated = isSelfOperated(card.platform(), card.shopName(), card.title());

        return new ProductCard(
                card.id(),
                card.title(),
                card.imageUrl(),
                card.price(),
                card.originalPrice(),
                card.platform(),
                selfOperated,
                card.shopName(),
                normalizeRating(card.platform(), card.rating()),
                card.sales(),
                card.similarity(),
                card.tags(),
                card.detailUrl(),
                normalizedBrand,
                card.ratingSource(),
                card.salesLabel(),
                card.mainCategoryCode(),
                card.productRole()
        );
    }

    /**
     * Normalize a list of product cards.
     */
    public List<ProductCard> normalizeAll(List<ProductCard> products) {
        return products.stream().map(this::normalize).toList();
    }

    /**
     * Normalize brand name (trim, consistent casing).
     */
    public String normalizeBrand(String brand) {
        if (StringUtils.isBlank(brand)) return null;
        return brand.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("(?i)\\b(official|旗舰店|自营|官方)\\b", "")
                .trim();
    }

    /**
     * Normalize shop type to unified categories.
     */
    public String normalizeShopType(String platform, String shopName) {
        if (StringUtils.isBlank(platform) || StringUtils.isBlank(shopName)) return "unknown";
        String lower = shopName.toLowerCase();

        Set<String> indicators = OFFICIAL_SHOP_INDICATORS.getOrDefault(platform.toLowerCase(), Set.of());
        for (String indicator : indicators) {
            if (lower.contains(indicator.toLowerCase())) {
                return "official";
            }
        }

        if (lower.contains("专营") || lower.contains("授权")) return "authorized";
        if (lower.contains("个人") || lower.contains("小店")) return "personal";
        return "third_party";
    }

    /**
     * Determine if a product is self-operated/official.
     */
    public boolean isSelfOperated(String platform, String shopName, String title) {
        if (StringUtils.isBlank(platform)) return false;

        // Check shop name indicators
        String shopLower = StringUtils.defaultString(shopName).toLowerCase();
        Set<String> indicators = OFFICIAL_SHOP_INDICATORS.getOrDefault(platform.toLowerCase(), Set.of());
        for (String indicator : indicators) {
            if (shopLower.contains(indicator.toLowerCase())) {
                return true;
            }
        }

        // Check title indicators
        String titleLower = StringUtils.defaultString(title).toLowerCase();
        return titleLower.contains("自营") || titleLower.contains("官方旗舰店");
    }

    /**
     * Normalize rating to 0-5 scale based on platform conventions.
     * eBay: 0-100 (percentage) → 0-5
     * PDD/Taobao/JD: 0-5 scale (already normalized)
     */
    public double normalizeRating(String platform, double rating) {
        if (rating <= 0) return 0;
        if (platform == null) return Math.min(5.0, rating);
        return switch (platform.toLowerCase()) {
            case "ebay" -> {
                // eBay uses percentage (0-100) or star (0-5)
                if (rating > 10) yield Math.min(5.0, rating / 20.0);
                if (rating > 5) yield Math.min(5.0, rating / 2.0);
                yield rating;
            }
            case "pdd", "taobao", "tmall", "jd" -> {
                // Chinese platforms use 0-5 scale with decimals
                if (rating > 5.0) yield 5.0; // Cap at 5.0
                yield rating;
            }
            default -> Math.min(5.0, rating);
        };
    }

    /**
     * Get normalized color from raw color string.
     */
    public String normalizeColor(String rawColor) {
        if (StringUtils.isBlank(rawColor)) return null;
        return COLOR_NORMALIZATION.getOrDefault(rawColor.trim(), rawColor.trim().toLowerCase());
    }
}
