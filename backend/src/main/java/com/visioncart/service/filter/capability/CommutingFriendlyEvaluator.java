package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.service.filter.capability.ProductFeatureExtractor.ProductFeatures;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluator for "commuting friendly" preference.
 * Checks for lightweight, portable, compact, and commuting-related keywords.
 * Products that are inherently for commuting (e.g., commuter bags) score higher.
 */
@Component
public class CommutingFriendlyEvaluator implements CapabilityEvaluator {

    private static final List<String> COMMUTE_KEYWORDS = List.of(
            "通勤", "通勤包", "通勤款", "日常通勤",
            "便携", "轻便", "轻量", "轻巧", "mini", "迷你",
            "折叠", "可折叠", "收纳", "随身", "口袋",
            "单肩", "斜挎", "手提", "背包",
            "日常", "百搭", "简约"
    );

    private static final List<String> ANTI_COMMUTE_KEYWORDS = List.of(
            "专业户外", "登山", "露营", "越野", "工业",
            "大型", "超大", "重型", "加厚加宽"
    );

    @Override
    public String capabilityCode() {
        return "commuting_friendly";
    }

    @Override
    public boolean supports(String categoryCode) {
        // Commuting applies broadly — bags, accessories, electronics, clothing, etc.
        return true;
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        ProductFeatures features = featureExtractor().extract(product);
        return evaluateWithFeatures(features, category);
    }

    public CapabilityResult evaluateWithFeatures(ProductFeatures features, String category) {
        String allText = features.allText();

        // Anti-commute indicators reduce score
        for (String anti : ANTI_COMMUTE_KEYWORDS) {
            if (allText.contains(anti)) {
                return CapabilityResult.noMatch(List.of("产品偏向专业/户外场景: " + anti));
            }
        }

        double score = 0;
        List<String> evidence = new java.util.ArrayList<>();

        // Check commute keywords
        for (String keyword : COMMUTE_KEYWORDS) {
            if (allText.contains(keyword)) {
                score += 0.2;
                evidence.add(keyword);
            }
        }

        // Lightweight bonus
        if (features.title().contains("轻") || features.title().contains("light")) {
            score += 0.15;
        }

        // Compact/mini bonus
        if (features.title().contains("mini") || features.title().contains("迷你")
                || features.title().contains("小巧") || features.title().contains("紧凑")) {
            score += 0.15;
        }

        score = Math.min(score, 0.95);

        if (score >= 0.6) {
            return CapabilityResult.match(score, evidence);
        } else if (score >= 0.3) {
            return CapabilityResult.uncertain(score, evidence, null);
        }

        return CapabilityResult.noMatch(List.of("未找到通勤相关特征"));
    }

    private ProductFeatureExtractor featureExtractor() {
        return Holder.INSTANCE;
    }

    private static class Holder {
        static final ProductFeatureExtractor INSTANCE = new ProductFeatureExtractor();
    }
}
