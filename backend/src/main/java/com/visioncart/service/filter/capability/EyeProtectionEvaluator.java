package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates whether a product is eye-friendly / eye-protecting.
 * Applies to: desk lamps, monitors, screen protectors, glasses, reading lights.
 */
@Component
public class EyeProtectionEvaluator implements CapabilityEvaluator {

    private static final List<String> EYE_PROTECTION_KEYWORDS = List.of(
            "护眼", "防蓝光", "无频闪", "减蓝光", "抗蓝光", "柔光",
            "不伤眼", "eye-care", "eye protection", "low blue light",
            "flicker-free", "dc调光", "自然光", "阅读灯"
    );

    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "装饰灯", "氛围灯", "彩灯", "闪光灯", "舞台灯",
            "护眼贴", "护眼罩", "眼罩", "眼贴"
    );

    @Override
    public String capabilityCode() {
        return "eye_protection";
    }

    @Override
    public boolean supports(String categoryCode) {
        return true; // Generic evaluator, supports all categories
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        String text = ProductFeatureExtractor.buildText(product);

        // Check negatives FIRST — prevents "护眼贴" from matching eye_protection
        for (String neg : NEGATIVE_KEYWORDS) {
            if (text.contains(neg)) {
                return CapabilityResult.noMatch(List.of("包含排除词: " + neg));
            }
        }

        for (String keyword : EYE_PROTECTION_KEYWORDS) {
            if (text.contains(keyword)) {
                return CapabilityResult.match(0.9, List.of("标题包含护眼关键词: " + keyword));
            }
        }

        // Category-aware
        if (category != null) {
            String cat = category.toLowerCase();
            if (cat.contains("台灯") || cat.contains("护眼灯") || cat.contains("阅读灯")) {
                return CapabilityResult.uncertain(0.5, List.of("台灯类商品，可能具备护眼功能"), null);
            }
            if (cat.contains("显示器") || cat.contains("monitor")) {
                return CapabilityResult.uncertain(0.4, List.of("显示器类商品，可能具备护眼模式"), null);
            }
        }

        return CapabilityResult.noMatch(List.of("未找到护眼相关信息"));
    }
}
