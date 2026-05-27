package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
        String searchable = String.join(" ",
                product.title() == null ? "" : product.title(),
                product.brand() == null ? "" : product.brand(),
                product.shopName() == null ? "" : product.shopName());
        String core = SearchTextUtils.coreProductToken(attributes);
        if (contains(title, SearchTextUtils.useful(attributes.get("关键词")))) {
            score += 45;
        }
        if (contains(title, SearchTextUtils.useful(attributes.get("类目")))) {
            score += 35;
        } else if (contains(title, core)) {
            score += 28;
        }
        if (contains(searchable, SearchTextUtils.useful(attributes.get("品牌")))) {
            score += 30;
        }
        if (contains(title, SearchTextUtils.useful(attributes.get("颜色")))) {
            score += 15;
        }
        if (contains(title, SearchTextUtils.useful(attributes.get("款式")))) {
            score += 10;
        }
        if (product.selfOperated()) {
            score += 15;
        }
        score += Math.min(15, Math.log10(Math.max(1, product.sales())) * 3);
        if (!"none".equals(product.ratingSource())) {
            score += Math.min(10, product.rating() * 2);
        }
        return score;
    }

    private boolean contains(String text, String token) {
        return token != null
                && !token.isBlank()
                && text != null
                && text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }
}
