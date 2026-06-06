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
        // Normalize: strip non-Chinese/non-letter chars, lowercase
        String normalized = title.replaceAll("[^\\p{IsHan}A-Za-z]", "").toLowerCase();
        // Use first 24 chars as fingerprint
        return normalized.length() <= 24 ? normalized : normalized.substring(0, 24);
    }
}
