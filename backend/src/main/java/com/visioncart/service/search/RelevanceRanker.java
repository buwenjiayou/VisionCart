package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class RelevanceRanker {

    public List<ProductCard> rank(List<ProductCard> products, Map<String, String> attributes) {
        SearchIntent intent = SearchIntent.from(attributes, null);
        return rank(products, intent);
    }

    public List<ProductCard> rank(List<ProductCard> products, SearchIntent intent) {
        return products.stream()
                .sorted(Comparator.comparingDouble((ProductCard product) -> score(product, intent)).reversed())
                .toList();
    }

    public List<ProductCard> withSimilarity(List<ProductCard> products, SearchIntent intent) {
        List<ProductCard> scored = new ArrayList<>();
        for (ProductCard product : products) {
            scored.add(withSimilarity(product, similarity(product, intent)));
        }
        return scored;
    }

    public boolean isRelevant(ProductCard product, SearchIntent intent) {
        if (!intent.hasSpecificSignals()) {
            return true;
        }
        double similarity = similarity(product, intent);
        if (intent.hasReliableBrand()) {
            if (BrandMatcher.hasConflictingBrand(product, intent.brand())) {
                return false;
            }
            return BrandMatcher.productMatchesExpectedBrand(product, intent.brand())
                    ? similarity >= 0.28
                    : similarity >= 0.45;
        }
        if (!intent.exactTerms().isEmpty()) {
            return similarity >= 0.35;
        }
        return similarity >= 0.20;
    }

    public double similarity(ProductCard product, SearchIntent intent) {
        String title = product.title();
        String searchable = searchable(product);
        double score = 0.0;

        if (!intent.brand().isBlank()) {
            if (BrandMatcher.productMatchesExpectedBrand(product, intent.brand())) {
                score += intent.hasReliableBrand() ? 0.25 : 0.15;
            } else if (intent.hasReliableBrand() && BrandMatcher.hasConflictingBrand(product, intent.brand())) {
                score -= 0.45;
            }
        }

        int exactMatches = matchCount(searchable, intent.exactTerms());
        if (exactMatches > 0) {
            score += Math.min(0.45, 0.32 + (exactMatches - 1) * 0.08);
        } else if (!intent.exactTerms().isEmpty()) {
            score -= 0.10;
        }

        score += keywordScore(title, intent.keywords());

        if (contains(title, intent.category()) && intent.category().length() >= 2) {
            score += 0.16;
        } else if (contains(title, intent.coreProduct()) && intent.coreProduct().length() >= 2) {
            score += 0.14;
        }

        int descriptiveMatches = matchCount(title, intent.descriptiveTerms());
        score += Math.min(0.12, descriptiveMatches * 0.04);

        return Math.max(0.0, Math.min(1.0, score));
    }

    private double score(ProductCard product, SearchIntent intent) {
        double score = product.similarity() * 100;
        if (intent.hasReliableBrand() && BrandMatcher.productMatchesExpectedBrand(product, intent.brand())) {
            score += 20;
        }
        if (!intent.exactTerms().isEmpty() && matchCount(searchable(product), intent.exactTerms()) > 0) {
            score += 25;
        }
        if (product.selfOperated()) {
            score += 8;
        }
        score += Math.min(8, Math.log10(Math.max(1, product.sales())) * 2);
        if (!"none".equals(product.ratingSource())) {
            score += Math.min(5, product.rating());
        }
        return score;
    }

    private boolean contains(String text, String token) {
        return token != null
                && !token.isBlank()
                && text != null
                && text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }

    private int matchCount(String text, List<String> tokens) {
        int matches = 0;
        for (String token : tokens) {
            if (SearchTextUtils.containsNormalized(text, token)) {
                matches++;
            }
        }
        return matches;
    }

    private double keywordScore(String text, List<String> keywords) {
        double best = 0.0;
        String normalizedText = SearchTextUtils.normalizeForMatch(text);
        for (String keyword : keywords) {
            String normalizedKeyword = SearchTextUtils.normalizeForMatch(keyword);
            if (normalizedKeyword.isBlank()) {
                continue;
            }
            if (normalizedText.contains(normalizedKeyword)) {
                best = Math.max(best, 0.22);
                continue;
            }
            best = Math.max(best, 0.18 * bigramCoverage(normalizedText, normalizedKeyword));
        }
        return best;
    }

    private double bigramCoverage(String normalizedText, String normalizedKeyword) {
        if (normalizedKeyword.length() < 4) {
            return 0.0;
        }
        int total = 0;
        int matched = 0;
        for (int i = 0; i < normalizedKeyword.length() - 1; i++) {
            String token = normalizedKeyword.substring(i, i + 2);
            if (token.matches("[a-z0-9]{2}")) {
                continue;
            }
            total++;
            if (normalizedText.contains(token)) {
                matched++;
            }
        }
        return total == 0 ? 0.0 : (double) matched / total;
    }

    private String searchable(ProductCard product) {
        return String.join(" ",
                product.title() == null ? "" : product.title(),
                product.brand() == null ? "" : product.brand(),
                product.shopName() == null ? "" : product.shopName());
    }

    private ProductCard withSimilarity(ProductCard product, double similarity) {
        return new ProductCard(
                product.id(),
                product.title(),
                product.imageUrl(),
                product.price(),
                product.originalPrice(),
                product.platform(),
                product.selfOperated(),
                product.shopName(),
                product.rating(),
                product.sales(),
                similarity,
                product.tags(),
                product.detailUrl(),
                product.brand(),
                product.ratingSource(),
                product.salesLabel()
        );
    }
}
