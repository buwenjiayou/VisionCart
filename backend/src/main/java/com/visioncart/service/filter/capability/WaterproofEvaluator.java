package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.service.filter.capability.ProductFeatureExtractor.ProductFeatures;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluator for "waterproof" capability.
 * Checks IP ratings, waterproof keywords in title/specs/tags, and product category context.
 *
 * Category-aware: a waterproof phone case is relevant for "手机防水",
 * but a waterproof bag is not relevant when searching for phones.
 */
@Component
public class WaterproofEvaluator implements CapabilityEvaluator {

    private static final List<String> WATERPROOF_KEYWORDS = List.of(
            "防水", "waterproof", "water resistant", "water-resistant",
            "防泼溅", "防溅水", "淋雨", "可水洗", "全身水洗",
            "ipx4", "ipx5", "ipx6", "ipx7", "ipx8",
            "ip67", "ip68", "ip69"
    );

    private static final List<String> WATERPROOF_SPEC_KEYWORDS = List.of(
            "防水等级", "防水性能", "防水深度", "水下", "潜水"
    );

    private static final List<String> NON_WATERPROOF_INDICATORS = List.of(
            "不防水", "非防水", "防水袋", "防水壳", "防水套", "防水包"
    );

    @Override
    public String capabilityCode() {
        return "waterproof";
    }

    @Override
    public boolean supports(String categoryCode) {
        // Waterproof is broadly applicable — electronics, bags, watches, outdoor gear, etc.
        return true;
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        ProductFeatures features = featureExtractor().extract(product);
        return evaluateWithFeatures(features, category);
    }

    /**
     * Evaluate with pre-extracted features for efficiency.
     */
    public CapabilityResult evaluateWithFeatures(ProductFeatures features, String category) {
        String allText = features.allText();

        // Check for explicit non-waterproof indicators
        for (String neg : NON_WATERPROOF_INDICATORS) {
            if (allText.contains(neg)) {
                if (neg.equals("防水袋") || neg.equals("防水壳") || neg.equals("防水套") || neg.equals("防水包")) {
                    return CapabilityResult.uncertain(0.4,
                            List.of("产品是防水配件（" + neg + "），不是防水主体商品"), null);
                }
                return CapabilityResult.noMatch(List.of("产品明确标注不防水"));
            }
        }

        // Check IP rating in specs
        if (features.ipRating().isPresent()) {
            String ip = features.ipRating().get();
            double confidence = ipRatingConfidence(ip);
            return CapabilityResult.match(confidence, List.of("IP等级: " + ip));
        }

        // Check waterproof keywords in title/tags/specs
        for (String keyword : WATERPROOF_KEYWORDS) {
            if (allText.contains(keyword)) {
                double confidence = keywordConfidence(keyword, features);
                String warning = null;

                if (features.isAccessory()) {
                    confidence *= 0.7;
                    warning = "产品为配件，防水性能可能依赖主体商品";
                }

                return CapabilityResult.match(confidence, List.of("关键词: " + keyword), warning);
            }
        }

        // Check spec-specific waterproof keywords (in allText since ProductCard has no separate specs field)
        for (String keyword : WATERPROOF_SPEC_KEYWORDS) {
            if (features.allText().contains(keyword)) {
                return CapabilityResult.match(0.7, List.of("规格参数: " + keyword));
            }
        }

        // Category-aware: some categories are inherently waterproof
        if (isInherentlyWaterproofCategory(features.category())) {
            return CapabilityResult.match(0.5, List.of("品类通常防水: " + features.category()));
        }

        return CapabilityResult.noMatch(List.of("未找到防水相关信息"));
    }

    private double ipRatingConfidence(String ip) {
        // IP67/IP68/IP69 = high confidence
        if (ip.contains("68") || ip.contains("69")) return 0.99;
        if (ip.contains("67")) return 0.95;
        if (ip.contains("66")) return 0.90;
        // IPX7/IPX8 = high confidence
        if (ip.contains("X7") || ip.contains("X8")) return 0.95;
        if (ip.contains("X5") || ip.contains("X6")) return 0.85;
        if (ip.contains("X4")) return 0.75;
        if (ip.contains("X3")) return 0.60;
        return 0.70;
    }

    private double keywordConfidence(String keyword, ProductFeatures features) {
        // "waterproof" in title = high confidence
        if (features.title().contains(keyword)) {
            if (keyword.equals("防水") || keyword.equals("waterproof")) return 0.92;
            return 0.85;
        }
        // In tags = medium-high
        if (features.tags().stream().anyMatch(t -> t.contains(keyword))) return 0.80;
        // In allText (title+brand+shop+tags) = medium
        if (features.allText().contains(keyword)) return 0.75;
        // In shop name = low (marketing, not product spec)
        if (features.shop().contains(keyword)) return 0.40;
        return 0.60;
    }

    private boolean isInherentlyWaterproofCategory(String category) {
        if (category == null) return false;
        return category.contains("潜水") || category.contains("游泳装备")
                || category.contains("户外防水") || category.contains("防水手表");
    }

    private ProductFeatureExtractor featureExtractor() {
        return Holder.INSTANCE;
    }

    // Lazy singleton to avoid circular dependency with Spring
    private static class Holder {
        static final ProductFeatureExtractor INSTANCE = new ProductFeatureExtractor();
    }
}
