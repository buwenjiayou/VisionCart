package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProductDeduplicator {

    public List<ProductCard> deduplicate(List<ProductCard> products) {
        Map<String, ProductCard> byFingerprint = new LinkedHashMap<>();
        for (ProductCard product : products) {
            String fingerprint = fingerprint(product);
            ProductCard existing = byFingerprint.get(fingerprint);
            if (existing == null || product.price().compareTo(existing.price()) < 0) {
                byFingerprint.put(fingerprint, product);
            }
        }
        return byFingerprint.values().stream()
                .sorted(Comparator.comparing(ProductCard::similarity).reversed().thenComparing(ProductCard::price))
                .toList();
    }

    private String fingerprint(ProductCard product) {
        String normalizedTitle = product.title().replaceAll("[^\\p{IsHan}A-Za-z]", "").toLowerCase();
        String titlePart = normalizedTitle.length() <= 16 ? normalizedTitle : normalizedTitle.substring(0, 16);
        BigDecimal bucket = product.price().divide(BigDecimal.TEN, 0, java.math.RoundingMode.HALF_UP);
        return titlePart + "|" + bucket + "|" + product.shopName();
    }
}
