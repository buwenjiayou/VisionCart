package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Generic keyword-based capability evaluator.
 * Handles capabilities that can be detected through keyword matching in product text.
 * Used as a fallback when no specialized evaluator exists for a capability.
 *
 * SAFETY: Returns uncertain (not matched) for accessories/risk-word products
 * to prevent false positives like "手机防水袋" matching "waterproof".
 *
 * Keywords are loaded from the centralized CapabilitySynonymRegistry.
 */
@Component
public class GenericKeywordCapabilityEvaluator implements CapabilityEvaluator {

    private final ProductFeatureExtractor featureExtractor;
    private final CapabilitySynonymRegistry capabilityRegistry;

    public GenericKeywordCapabilityEvaluator(ProductFeatureExtractor featureExtractor,
                                             CapabilitySynonymRegistry capabilityRegistry) {
        this.featureExtractor = featureExtractor;
        this.capabilityRegistry = capabilityRegistry;
    }

    @Override
    public String capabilityCode() {
        return "_generic_keyword";
    }

    @Override
    public boolean supports(String categoryCode) {
        return true;
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        return CapabilityResult.uncertain(0.3, List.of("通用评估器"), "请使用具体能力评估器");
    }

    /**
     * Evaluate a specific capability using keyword matching.
     * Called by CapabilityRegistry as a fallback.
     *
     * SAFETY RULES:
     * 1. Negative keywords → noMatch
     * 2. Accessory + risk words → uncertain (not matched)
     * 3. Accessory without explicit main-product signal → uncertain
     * 4. Main product + positive keywords → matched
     */
    public CapabilityResult evaluateWithKeywords(String capabilityCode, ProductCard product) {
        // Load keywords from centralized CapabilitySynonymRegistry
        String[] positive = capabilityRegistry.positiveKeywords(capabilityCode);
        String[] negative = capabilityRegistry.negativeKeywords(capabilityCode);
        String label = capabilityRegistry.displayText(capabilityCode);

        if (positive.length == 0 && label.equals(capabilityCode)) {
            return CapabilityResult.uncertain(0.2, List.of("未知能力: " + capabilityCode), null);
        }

        ProductFeatureExtractor.ProductFeatures features = featureExtractor.extract(product);
        String text = features.allText();
        List<String> evidence = new ArrayList<>();

        // 1. Check negative keywords first → hard reject
        int negativeHits = 0;
        for (String neg : negative) {
            if (text.contains(neg)) {
                evidence.add("包含排除词「" + neg + "」");
                negativeHits++;
            }
        }
        if (negativeHits > 0) {
            return CapabilityResult.noMatch(evidence);
        }

        // 2. Check positive keywords
        int positiveHits = 0;
        for (String pos : positive) {
            if (text.contains(pos)) {
                evidence.add("包含「" + pos + "」");
                positiveHits++;
            }
        }

        if (positiveHits == 0) {
            evidence.add("未找到「" + label + "」相关关键词");
            return CapabilityResult.uncertain(0.25, evidence, null);
        }

        // 3. SAFETY: Accessory + risk words → uncertain (prevent "手机防水袋" → waterproof)
        if (features.isAccessory() && features.hasRiskWords()) {
            evidence.add("商品为配件类型，可能不是主商品");
            return CapabilityResult.uncertain(0.35, evidence,
                    "该商品可能是配件，" + label + "能力可能不适用");
        }

        // 4. SAFETY: Accessory without strong signals → uncertain
        if (features.isAccessory() && positiveHits <= 1) {
            evidence.add("配件类商品，仅匹配单一关键词");
            return CapabilityResult.uncertain(0.3, evidence,
                    "配件类商品的" + label + "能力可能不适用");
        }

        // 5. Main product + positive keywords → matched
        double confidence = Math.min(0.5 + positiveHits * 0.15, 0.9);
        String warning = positiveHits == 1 ? "仅匹配到单一关键词，请以商品详情为准" : null;
        return CapabilityResult.match(confidence, evidence, warning);
    }
}
