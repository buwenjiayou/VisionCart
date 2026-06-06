package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates whether a product is suitable as a gift.
 * Considers: packaging quality, price point, brand prestige, product type.
 */
@Component
public class GiftFriendlyEvaluator implements CapabilityEvaluator {

    private static final List<String> GIFT_KEYWORDS = List.of(
            "礼盒", "送礼", "礼品", "礼物", "包装精美", "高端礼盒",
            "生日礼物", "情人节", "圣诞节", "母亲节", "父亲节",
            "gift", "present", "送男", "送女", "送女友", "送男友",
            "送朋友", "送长辈", "伴手礼", "节日礼物"
    );

    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "散装", "简装", "试用装", "小样", "替换装"
    );

    @Override
    public String capabilityCode() {
        return "gift_friendly";
    }

    @Override
    public boolean supports(String categoryCode) {
        return true;
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        String text = ProductFeatureExtractor.buildText(product);

        // Check for explicit gift keywords
        for (String keyword : GIFT_KEYWORDS) {
            if (text.contains(keyword)) {
                return CapabilityResult.match(0.9, List.of("包含送礼关键词: " + keyword));
            }
        }

        // Check for negative keywords
        for (String neg : NEGATIVE_KEYWORDS) {
            if (text.contains(neg)) {
                return CapabilityResult.noMatch(List.of(neg + "不适合送礼"));
            }
        }

        // Price-based heuristic: very cheap items are less gift-worthy
        double price = product.price() != null ? product.price().doubleValue() : 0;
        if (price > 100) {
            // Higher-priced items are more gift-worthy
            double confidence = Math.min(0.4 + (price / 1000) * 0.3, 0.7);
            return CapabilityResult.uncertain(confidence,
                    List.of("价格 ¥" + (int) price + "，适合作为礼物"), null);
        }

        // Brand prestige heuristic
        if (product.brand() != null && !product.brand().isBlank()) {
            return CapabilityResult.uncertain(0.4,
                    List.of("品牌商品，可能适合送礼"), null);
        }

        return CapabilityResult.noMatch(List.of("未找到适合送礼的相关信息"));
    }
}
