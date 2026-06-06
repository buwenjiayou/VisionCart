package com.visioncart.service.filter.semantic;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SemanticActionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scores and reranks products based on user preference rules.
 *
 * Unlike hard filters and semantic filters, preferences don't exclude products —
 * they reorder the existing list so better-matching products appear first.
 *
 * Supports common preference patterns:
 * - 性价比高 (value for money): rating + sales + price balance
 * - 适合送礼 (gift-worthy): brand + rating + presentation keywords
 * - 轻便/便携 (lightweight/portable): weight/portability keywords
 * - 通勤方便 (commute-friendly): portability + daily-use keywords
 * - 耐用 (durable): durability keywords + rating
 * - 颜值高 (good-looking): aesthetics keywords + rating
 * - 学生党 (student-budget): price range + value keywords
 */
@Component
public class PreferenceScorer {

    private static final Logger log = LoggerFactory.getLogger(PreferenceScorer.class);

    // Weight components for the composite score
    private static final double W_RATING = 0.30;
    private static final double W_SALES = 0.20;
    private static final double W_PRICE = 0.25;
    private static final double W_KEYWORD = 0.25;

    /**
     * Rerank products based on preference rules.
     * Returns a new sorted list (does not modify the original).
     */
    public List<ProductCard> rerank(List<ProductCard> products,
                                    List<SemanticActionPlan.PreferenceRule> preferences) {
        if (products == null || products.isEmpty() || preferences == null || preferences.isEmpty()) {
            return products;
        }

        // Merge all preference keywords into a single scoring context
        PreferenceContext ctx = buildContext(preferences);

        log.info("PreferenceScorer: reranking {} products with {} preference rules, category={}",
                products.size(), preferences.size(), ctx.category);

        // Score each product
        List<ScoredPreference> scored = products.stream()
                .map(p -> new ScoredPreference(p, computeScore(p, ctx)))
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .toList();

        return scored.stream().map(sp -> sp.product).toList();
    }

    /**
     * Build a merged preference context from all preference rules.
     */
    private PreferenceContext buildContext(List<SemanticActionPlan.PreferenceRule> preferences) {
        Set<String> positiveKeywords = new HashSet<>();
        Set<String> negativeKeywords = new HashSet<>();
        String category = null;

        for (SemanticActionPlan.PreferenceRule rule : preferences) {
            String raw = rule.userMeaning() != null ? rule.userMeaning().toLowerCase() : "";
            String code = rule.code() != null ? rule.code().toLowerCase() : "";

            // Detect preference category and populate keywords
            if (containsAny(raw, "性价比", "划算", "实惠", "便宜好用", "物美价廉")) {
                category = "value";
            } else if (containsAny(raw, "送礼", "礼物", "礼品", "生日礼物", "女生", "男生")) {
                category = "gift";
                positiveKeywords.addAll(List.of("礼盒", "精美", "高端", "品牌", "时尚", "好看", "颜值"));
            } else if (containsAny(raw, "轻便", "便携", "小巧", "mini", "轻量")) {
                category = "lightweight";
                positiveKeywords.addAll(List.of("轻便", "便携", "小巧", "mini", "迷你", "轻量", "口袋"));
                negativeKeywords.addAll(List.of("大容量", "户外", "超大", "重型", "专业", "工业"));
            } else if (containsAny(raw, "通勤", "上班", "日常", "出行")) {
                category = "commute";
                positiveKeywords.addAll(List.of("通勤", "日常", "便携", "轻便", "商务", "简约"));
            } else if (containsAny(raw, "耐用", "结实", "质量好", "抗摔", "耐磨")) {
                category = "durable";
                positiveKeywords.addAll(List.of("耐用", "结实", "抗摔", "耐磨", "加厚", "加固", "军工"));
            } else if (containsAny(raw, "颜值", "好看", "漂亮", "时尚", "可爱")) {
                category = "aesthetic";
                positiveKeywords.addAll(List.of("颜值", "好看", "时尚", "可爱", "ins", "潮流", "高颜值"));
            } else if (containsAny(raw, "学生", "学生党", "平价", "入门")) {
                category = "student";
                // Student budget: prefer mid-range price
            } else {
                // Generic preference: use raw text as keyword
                if (raw.length() >= 2) {
                    positiveKeywords.add(raw);
                }
            }

            // Also extract code-based keywords
            if (code.contains("轻") || code.contains("便携")) {
                positiveKeywords.addAll(List.of("轻便", "便携", "小巧"));
                negativeKeywords.addAll(List.of("大容量", "重型"));
            }
        }

        return new PreferenceContext(category, positiveKeywords, negativeKeywords);
    }

    /**
     * Compute a composite preference score for a product.
     * Returns 0.0 ~ 1.0.
     */
    private double computeScore(ProductCard product, PreferenceContext ctx) {
        double score = 0.0;

        // 1. Rating component (normalized to 0~1, assuming 5-star scale)
        double ratingNorm = Math.min(product.rating() / 5.0, 1.0);
        score += W_RATING * ratingNorm;

        // 2. Sales component (log-normalized)
        double salesNorm = normalizeSales(product.sales());
        score += W_SALES * salesNorm;

        // 3. Price component (context-dependent)
        score += W_PRICE * computePriceScore(product, ctx);

        // 4. Keyword match component
        score += W_KEYWORD * computeKeywordScore(product, ctx);

        return score;
    }

    /**
     * Price score depends on preference category.
     * - value/student: prefer mid-low price
     * - gift: prefer mid-high price (perceived quality)
     * - others: neutral
     */
    private double computePriceScore(ProductCard product, PreferenceContext ctx) {
        if (product.price() == null) return 0.5;

        double price = product.price().doubleValue();

        return switch (ctx.category != null ? ctx.category : "") {
            case "value", "student" -> {
                // Lower price = higher score, but not too cheap (quality concern)
                if (price <= 0) yield 0.0;
                if (price < 20) yield 0.6;     // suspiciously cheap
                if (price < 50) yield 0.9;     // sweet spot for value
                if (price < 100) yield 0.8;
                if (price < 200) yield 0.6;
                yield 0.3; // expensive
            }
            case "gift" -> {
                // Mid-range preferred (not too cheap, not extravagant)
                if (price < 30) yield 0.3;     // too cheap for a gift
                if (price < 100) yield 0.7;
                if (price < 300) yield 0.9;    // sweet spot for gifts
                if (price < 500) yield 0.8;
                yield 0.6;
            }
            default -> 0.5; // neutral
        };
    }

    /**
     * Keyword match score: check product title and tags against preference keywords.
     */
    private double computeKeywordScore(ProductCard product, PreferenceContext ctx) {
        if (ctx.positiveKeywords.isEmpty() && ctx.negativeKeywords.isEmpty()) {
            return 0.5; // neutral when no keywords
        }

        String text = buildProductText(product);
        double score = 0.5; // baseline

        // Positive keyword matches boost score
        int positiveHits = 0;
        for (String kw : ctx.positiveKeywords) {
            if (text.contains(kw)) positiveHits++;
        }
        if (!ctx.positiveKeywords.isEmpty()) {
            score += 0.4 * Math.min(positiveHits / (double) ctx.positiveKeywords.size(), 1.0);
        }

        // Negative keyword matches reduce score
        int negativeHits = 0;
        for (String kw : ctx.negativeKeywords) {
            if (text.contains(kw)) negativeHits++;
        }
        if (!ctx.negativeKeywords.isEmpty()) {
            score -= 0.3 * Math.min(negativeHits / (double) ctx.negativeKeywords.size(), 1.0);
        }

        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * Normalize sales count to 0~1 using log scale.
     * 0 sales → 0.0, 100k+ sales → ~1.0
     */
    private double normalizeSales(long sales) {
        if (sales <= 0) return 0.0;
        return Math.min(Math.log10(sales + 1) / 6.0, 1.0); // log10(100001) ≈ 5
    }

    private String buildProductText(ProductCard product) {
        StringBuilder sb = new StringBuilder();
        if (product.title() != null) sb.append(product.title()).append(" ");
        if (product.brand() != null) sb.append(product.brand()).append(" ");
        if (product.tags() != null) sb.append(String.join(" ", product.tags())).append(" ");
        if (product.shopName() != null) sb.append(product.shopName());
        return sb.toString().toLowerCase();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /**
     * Internal preference context built from merging all preference rules.
     */
    private record PreferenceContext(
            String category,
            Set<String> positiveKeywords,
            Set<String> negativeKeywords
    ) {}

    /**
     * Product with its computed preference score (for sorting).
     */
    private record ScoredPreference(ProductCard product, double score) {}
}
