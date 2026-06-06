package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates whether a product has noise-cancelling capability.
 * Applies to: earphones, headphones, earbuds, windows, doors, white noise machines.
 */
@Component
public class NoiseCancellingEvaluator implements CapabilityEvaluator {

    private static final List<String> NOISE_CANCELLING_KEYWORDS = List.of(
            "降噪", "主动降噪", "anc", "noise cancelling", "noise canceling",
            "隔音", "消噪", "抗噪", "静音", "减噪",
            "enc通话降噪", "通话降噪", "环境降噪"
    );

    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "开放式", "不降噪", "通透模式"
    );

    @Override
    public String capabilityCode() {
        return "noise_cancelling";
    }

    @Override
    public boolean supports(String categoryCode) {
        return true;
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        String text = ProductFeatureExtractor.buildText(product);

        for (String keyword : NOISE_CANCELLING_KEYWORDS) {
            if (text.contains(keyword)) {
                double confidence = 0.9;
                if (category != null && (category.contains("耳机") || category.contains("earphone"))) {
                    confidence = 0.95;
                }
                return CapabilityResult.match(confidence, List.of("包含降噪关键词: " + keyword));
            }
        }

        for (String neg : NEGATIVE_KEYWORDS) {
            if (text.contains(neg)) {
                return CapabilityResult.noMatch(List.of(neg + "不符合降噪需求"));
            }
        }

        if (category != null) {
            String cat = category.toLowerCase();
            if (cat.contains("耳机") || cat.contains("earphone") || cat.contains("headphone")) {
                if (text.contains("头戴式") || text.contains("旗舰") || text.contains("pro")) {
                    return CapabilityResult.uncertain(0.5, List.of("高端耳机，可能具备降噪功能"), null);
                }
                return CapabilityResult.uncertain(0.3, List.of("耳机类商品，可能具备降噪功能"), null);
            }
        }

        return CapabilityResult.noMatch(List.of("未找到降噪相关信息"));
    }
}
