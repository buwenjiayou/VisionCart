package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.ReputationScore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Multi-signal reputation scoring service.
 * Decomposes cross-platform ratings into explainable, confidence-weighted reputation scores.
 *
 * <p>Scoring formula:
 * <pre>
 * reputationScore =
     *     itemRatingScore   * itemRatingConfidence   * 0.55
     *   + shopOrSellerScore * shopOrSellerConfidence * 0.15
     *   + salesScore                                  * 0.10
     *   + similarityScore                             * 0.20
 * </pre>
 *
 * <p>Rating source classification:
 * <ul>
 *   <li>{@code item_rating} — product-level rating (Taobao item_score, PDD goods_eval_score). Strong signal.</li>
 *   <li>{@code shop_dsr} — shop-level DSR/reputation. Weak signal. PDD 高/中/低 mapped here.</li>
 *   <li>{@code seller} — seller-level reputation (eBay feedbackPercentage). Medium signal.</li>
 *   <li>{@code none} — no rating data. Zero confidence.</li>
 * </ul>
 */
@Service
public class ProductReputationService {

    // === Weight constants ===
    private static final double W_ITEM_RATING = 0.55;
    private static final double W_SHOP_SELLER = 0.15;
    private static final double W_SALES = 0.10;
    private static final double W_SIMILARITY = 0.20;

    /**
     * Compute reputation score for a product within a pool (for sales normalization).
     */
    public ReputationScore score(ProductCard product, List<ProductCard> pool) {
        if (product == null) return ReputationScore.EMPTY;

        String source = normalizeSource(product.ratingSource());
        double rating = product.rating();

        // Item rating signal (only from actual product-level ratings)
        double itemScore = itemRatingScore(rating, source);
        double itemConf = itemRatingConfidence(source);

        // Shop/seller reputation signal
        double shopScore = shopOrSellerScore(rating, source);
        double shopConf = shopOrSellerConfidence(source);

        // Sales and similarity (always available)
        double salesScore = normalizeSales(product.sales(), pool);
        double simScore = clamp(product.similarity());

        double finalScore = itemScore * itemConf * W_ITEM_RATING
                + shopScore * shopConf * W_SHOP_SELLER
                + salesScore * W_SALES
                + simScore * W_SIMILARITY;

        String label = displayLabel(product, source, rating);

        return new ReputationScore(finalScore, itemScore, shopScore,
                Math.max(itemConf, shopConf), label);
    }

    /**
     * Quick score without pool context (sales normalized to 0).
     */
    public ReputationScore score(ProductCard product) {
        return score(product, List.of());
    }

    // === Item rating: only from genuine product-level ratings ===

    private double itemRatingScore(double rating, String source) {
        if (rating <= 0) return 0;
        if ("item_rating".equals(source)) {
            return clamp(rating / 5.0);  // normalize to 0~1
        }
        // shop_dsr / seller / none — NOT a product rating, don't use as item signal
        return 0;
    }

    private double itemRatingConfidence(String source) {
        return switch (source) {
            case "item_rating" -> 0.90;   // genuine product-level rating
            default -> 0.0;               // not a product rating
        };
    }

    // === Shop/seller reputation: from DSR, seller feedback, etc. ===

    private double shopOrSellerScore(double rating, String source) {
        if (rating <= 0) return 0;
        return switch (source) {
            case "shop_dsr" -> clamp(rating / 5.0);   // already normalized to 0~5 by platform services
            case "seller" -> clamp(rating / 5.0);      // eBay feedbackPercentage / 20 → 0~5
            default -> 0;
        };
    }

    private double shopOrSellerConfidence(String source) {
        return switch (source) {
            case "shop_dsr" -> 0.45;   // shop-level, not product-level
            case "seller" -> 0.55;     // seller reputation, somewhat relevant
            default -> 0.0;
        };
    }

    // === Sales normalization ===

    private double normalizeSales(long sales, List<ProductCard> pool) {
        if (sales <= 0 || pool == null || pool.isEmpty()) return 0;
        long maxSales = pool.stream().mapToLong(ProductCard::sales).max().orElse(0);
        if (maxSales <= 0) return 0;
        // Log-scale normalization to prevent mega-sellers from dominating
        return Math.log1p(sales) / Math.log1p(maxSales);
    }

    // === Utilities ===

    private double clamp(double value) {
        return Math.max(0, Math.min(1.0, value));
    }

    private String normalizeSource(String ratingSource) {
        if (ratingSource == null || ratingSource.isBlank() || "none".equals(ratingSource)) {
            return "none";
        }
        return ratingSource.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Generate a human-readable display label based on rating source.
     * Shows the actual meaning, not a fake unified "X.X 分".
     */
    private String displayLabel(ProductCard product, String source, double rating) {
        if (rating <= 0) return "暂无评分";

        String platform = platformLabel(product.platform());
        return switch (source) {
            case "item_rating" -> String.format(Locale.US, "%s · 商品评分 %.1f", platform, rating);
            case "shop_dsr" -> {
                // For PDD text DSR that was mapped, show the original meaning
                if (rating >= 4.6) yield platform + " · 店铺口碑 高";
                if (rating >= 3.8) yield platform + " · 店铺口碑 中";
                yield platform + " · 店铺口碑 低";
            }
            case "seller" -> String.format(Locale.US, "%s · 卖家信誉 %.0f%%", platform, rating * 20);
            default -> "暂无评分";
        };
    }

    private String platformLabel(String platform) {
        if (platform == null) return "";
        return switch (platform.toLowerCase(Locale.ROOT)) {
            case "taobao", "淘宝" -> "淘宝";
            case "pdd", "拼多多" -> "拼多多";
            case "ebay" -> "eBay";
            case "tmall", "天猫" -> "天猫";
            case "jd", "京东" -> "京东";
            default -> platform;
        };
    }
}
