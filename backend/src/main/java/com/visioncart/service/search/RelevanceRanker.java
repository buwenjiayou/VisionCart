package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class RelevanceRanker {

    public List<ProductCard> rank(List<ProductCard> products, Map<String, String> attributes) {
        return products.stream()
                .sorted(Comparator.comparingDouble((ProductCard product) -> score(product, attributes)).reversed())
                .toList();
    }

    private double score(ProductCard product, Map<String, String> attributes) {
        double score = product.similarity() * 40;
        String title = product.title();
        if (contains(title, attributes.get("品牌"))) {
            score += 30;
        }
        if (contains(title, attributes.get("颜色"))) {
            score += 20;
        }
        if (contains(title, attributes.get("款式"))) {
            score += 20;
        }
        if (product.selfOperated()) {
            score += 15;
        }
        score += Math.min(15, Math.log10(Math.max(1, product.sales())) * 3);
        score += Math.min(10, product.rating() * 2);
        return score;
    }

    private boolean contains(String text, String token) {
        return token != null && !token.isBlank() && text != null && text.contains(token);
    }
}
