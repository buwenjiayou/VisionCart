package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.service.filter.capability.ProductFeatureExtractor.ProductFeatures;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalInt;

/**
 * Evaluator for "fast charging" capability.
 * Checks wattage (>= 20W = fast charge), protocol keywords, and product type.
 *
 * Category-aware: a fast charging cable is relevant, but a fast charging phone case is not.
 */
@Component
public class FastChargingEvaluator implements CapabilityEvaluator {

    private static final int FAST_CHARGE_MIN_WATTAGE = 20;

    private static final List<String> FAST_CHARGE_KEYWORDS = List.of(
            "快充", "闪充", "fast charge", "fast charging", "quick charge", "quick charge",
            "supercharge", "super charge", "vooc", "warp", "dash charge",
            "pd快充", "qc快充", "pps", "ufcs",
            "66w", "100w", "120w", "150w", "200w", "240w",
            "65w", "45w", "33w", "25w", "20w"
    );

    private static final List<String> PROTOCOL_KEYWORDS = List.of(
            "pd3.0", "pd3.1", "pd2.0", "qc3.0", "qc4.0", "qc4+",
            "ufcs", "pps", "scp", "fcp", "afc", "pe+", "pe2.0",
            "vooc", "warp", "supervooc", "dart", "hypercharge"
    );

    @Override
    public String capabilityCode() {
        return "fast_charging";
    }

    @Override
    public boolean supports(String categoryCode) {
        if (categoryCode == null) return true;
        String cat = categoryCode.toLowerCase();
        return cat.contains("充电") || cat.contains("charger") || cat.contains("电源")
                || cat.contains("power") || cat.contains("cable") || cat.contains("线")
                || cat.contains("适配器") || cat.contains("adapter");
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        ProductFeatures features = featureExtractor().extract(product);
        return evaluateWithFeatures(features, category);
    }

    public CapabilityResult evaluateWithFeatures(ProductFeatures features, String category) {
        String allText = features.allText();

        // Check for explicit fast charging keywords
        for (String keyword : FAST_CHARGE_KEYWORDS) {
            if (allText.contains(keyword)) {
                double confidence = keywordConfidence(keyword, features);
                String evidence = "快充关键词: " + keyword;

                OptionalInt wattage = features.wattage();
                if (wattage.isPresent()) {
                    evidence += " (" + wattage.getAsInt() + "W)";
                    confidence = Math.max(confidence, wattageConfidence(wattage.getAsInt()));
                }

                return CapabilityResult.match(confidence, List.of(evidence));
            }
        }

        // Check for charging protocol keywords
        for (String protocol : PROTOCOL_KEYWORDS) {
            if (allText.contains(protocol)) {
                double confidence = 0.75;
                if (features.isCharger() || features.isCable()) confidence = 0.85;
                return CapabilityResult.match(confidence, List.of("充电协议: " + protocol));
            }
        }

        // Check wattage alone
        OptionalInt wattage = features.wattage();
        if (wattage.isPresent() && wattage.getAsInt() >= FAST_CHARGE_MIN_WATTAGE) {
            double confidence = wattageConfidence(wattage.getAsInt());
            return CapabilityResult.match(confidence, List.of("功率: " + wattage.getAsInt() + "W"));
        }

        // Category-aware defaults
        if (features.isCharger()) {
            // If wattage is known and below threshold, explicitly reject
            if (wattage.isPresent() && wattage.getAsInt() < FAST_CHARGE_MIN_WATTAGE) {
                return CapabilityResult.noMatch(List.of("充电器功率 " + wattage.getAsInt() + "W，低于快充标准 " + FAST_CHARGE_MIN_WATTAGE + "W"));
            }
            return CapabilityResult.uncertain(0.3, List.of("充电器品类，但未标注快充功率"), null);
        }

        return CapabilityResult.noMatch(List.of("未找到快充相关信息"));
    }

    private double wattageConfidence(int watts) {
        if (watts >= 100) return 0.99;
        if (watts >= 65) return 0.95;
        if (watts >= 45) return 0.90;
        if (watts >= 33) return 0.85;
        if (watts >= 25) return 0.80;
        if (watts >= 20) return 0.70;
        return 0.50;
    }

    private double keywordConfidence(String keyword, ProductFeatures features) {
        // "快充" in title = high
        if (features.title().contains(keyword)) return 0.90;
        // In tags
        if (features.tags().stream().anyMatch(t -> t.contains(keyword))) return 0.80;
        // In specs
        if (features.allText().contains(keyword)) return 0.75;
        return 0.65;
    }

    private ProductFeatureExtractor featureExtractor() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        static final ProductFeatureExtractor INSTANCE = new ProductFeatureExtractor();
    }
}
