package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProductDeduplicator {

    /**
     * 去重逻辑：同名商品保留不同价格（比价需要），同名同价只保留最便宜的。
     * 跨平台同一商品不同价格 = 保留，这正是比价的核心价值。
     */
    public List<ProductCard> deduplicate(List<ProductCard> products) {
        // Group by normalized title
        Map<String, List<ProductCard>> byTitle = new LinkedHashMap<>();
        for (ProductCard product : products) {
            String key = titleFingerprint(product);
            byTitle.computeIfAbsent(key, k -> new ArrayList<>()).add(product);
        }

        List<ProductCard> result = new ArrayList<>();
        for (List<ProductCard> group : byTitle.values()) {
            if (group.size() == 1) {
                result.add(group.get(0));
                continue;
            }
            // Sort by price ascending within group
            group.sort(Comparator.comparing(p -> p.price() == null ? BigDecimal.ZERO : p.price()));
            // Keep products with different prices (for comparison)
            // Only deduplicate when price is nearly identical (within 1%)
            result.add(group.get(0));
            for (int i = 1; i < group.size(); i++) {
                BigDecimal prevPrice = group.get(i - 1).price();
                BigDecimal currPrice = group.get(i).price();
                if (prevPrice == null || currPrice == null) {
                    result.add(group.get(i));
                    continue;
                }
                // If price differs by more than 1%, keep it (different price = valuable for comparison)
                double priceDiff = currPrice.subtract(prevPrice).abs().doubleValue();
                double avgPrice = prevPrice.add(currPrice).doubleValue() / 2.0;
                if (avgPrice <= 0 || priceDiff / avgPrice > 0.01) {
                    result.add(group.get(i));
                }
                // else: same name, nearly same price → deduplicate (skip)
            }
        }

        return result.stream()
                .sorted(Comparator.comparing(ProductCard::similarity).reversed().thenComparing(ProductCard::price))
                .toList();
    }

    private String titleFingerprint(ProductCard product) {
        String title = product.title() == null ? "" : product.title();
        // Normalize: strip non-Chinese/non-letter/non-digit chars, lowercase
        // Problem 10 fix: preserve digits — "iPhone 15 Pro" vs "iPhone 16 Pro",
        // "RTX 4060" vs "RTX 4070" must NOT be merged.
        String normalized = title.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "").toLowerCase();
        String modelToken = significantModelToken(title);
        String prefix = normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
        return modelToken.isBlank() ? prefix : prefix + "#" + modelToken;
    }

    private String significantModelToken(String title) {
        String text = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        String[] patterns = {
                "\\biphone\\s*(\\d{1,2})\\s*(pro\\s*max|pro|max|plus|mini)?\\b",
                "\\bmate\\s*(\\d{2,3})\\s*(pro|rs)?\\b",
                "\\brtx\\s*(\\d{3,4})\\b",
                "\\bgtx\\s*(\\d{3,4})\\b",
                "\\brx\\s*(\\d{3,4})\\b"
        };
        for (String pattern : patterns) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(text);
            if (matcher.find()) {
                return matcher.group().replaceAll("\\s+", "");
            }
        }
        return "";
    }
}
