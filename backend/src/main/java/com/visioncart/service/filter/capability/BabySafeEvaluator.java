package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates whether a product is safe/suitable for babies and young children.
 * Applies to: bottles, pacifiers, toys, clothing, food, skincare, monitors.
 */
@Component
public class BabySafeEvaluator implements CapabilityEvaluator {

    private static final List<String> BABY_KEYWORDS = List.of(
            "婴儿", "宝宝", "幼儿", "母婴", "baby", "infant",
            "儿童", "kid", "child", "新生", "新生儿",
            "奶瓶", "奶嘴", "辅食", "尿不湿", "纸尿裤",
            "婴儿车", "安全座椅", "儿童座椅"
    );

    private static final List<String> SAFETY_KEYWORDS = List.of(
            "食品级", "bpa-free", "无毒", "安全材质", "环保材质",
            "3c认证", "国标", "欧盟认证", "fda", "安全认证"
    );

    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "成人", "adult", "酒精", "烟草", "利器", "小零件"
    );

    @Override
    public String capabilityCode() {
        return "baby_safe";
    }

    @Override
    public boolean supports(String categoryCode) {
        return true;
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        String text = ProductFeatureExtractor.buildText(product);

        for (String keyword : BABY_KEYWORDS) {
            if (text.contains(keyword)) {
                double confidence = 0.9;
                for (String safety : SAFETY_KEYWORDS) {
                    if (text.contains(safety)) {
                        confidence = 0.95;
                        break;
                    }
                }
                return CapabilityResult.match(confidence, List.of("包含婴儿/母婴关键词: " + keyword));
            }
        }

        for (String neg : NEGATIVE_KEYWORDS) {
            if (text.contains(neg)) {
                return CapabilityResult.noMatch(List.of(neg + "不适合婴儿使用"));
            }
        }

        if (category != null) {
            String cat = category.toLowerCase();
            if (cat.contains("婴儿") || cat.contains("母婴") || cat.contains("baby")) {
                return CapabilityResult.match(0.85, List.of("母婴类商品"));
            }
        }

        for (String safety : SAFETY_KEYWORDS) {
            if (text.contains(safety)) {
                return CapabilityResult.uncertain(0.5, List.of("包含安全认证关键词: " + safety),
                        "仅凭安全认证无法确认是否适合婴儿");
            }
        }

        return CapabilityResult.noMatch(List.of("未找到婴儿适用相关信息"));
    }
}
