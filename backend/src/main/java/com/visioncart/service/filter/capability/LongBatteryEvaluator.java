package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.OptionalInt;

/**
 * Evaluates whether a product has long battery life / high capacity.
 * Applies to: phones, laptops, tablets, earphones, power banks, smartwatches.
 */
@Component
public class LongBatteryEvaluator implements CapabilityEvaluator {

    private static final List<String> LONG_BATTERY_KEYWORDS = List.of(
            "长续航", "超长续航", "大电池", "持久续航", "待机长", "续航久",
            "续航长", "连续使用", "长效电池",
            "72小时", "48小时", "36小时", "30小时", "24小时",
            "7000mah", "6000mah", "5000mah", "10000mah", "20000mah"
    );

    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "续航短", "电池小", "需频繁充电"
    );

    @Override
    public String capabilityCode() {
        return "long_battery";
    }

    @Override
    public boolean supports(String categoryCode) {
        return true;
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        String text = ProductFeatureExtractor.buildText(product);

        // Check for explicit long-battery keywords
        for (String keyword : LONG_BATTERY_KEYWORDS) {
            if (text.contains(keyword)) {
                return CapabilityResult.match(0.85, List.of("包含长续航关键词: " + keyword));
            }
        }

        // Check for negative keywords
        for (String neg : NEGATIVE_KEYWORDS) {
            if (text.contains(neg)) {
                return CapabilityResult.noMatch(List.of(neg));
            }
        }

        // Check mAh from product text
        OptionalInt mAh = ProductFeatureExtractor.extractmAhFromText(text);
        if (mAh.isPresent()) {
            int mah = mAh.getAsInt();
            if (mah >= 5000) {
                return CapabilityResult.match(0.8, List.of("电池容量 " + mah + "mAh，属于大容量"));
            }
            if (mah >= 3000) {
                return CapabilityResult.uncertain(0.5, List.of("电池容量 " + mah + "mAh，中等容量"), null);
            }
        }

        // Category-aware: power banks inherently have long battery
        if (category != null) {
            String cat = category.toLowerCase();
            if (cat.contains("充电宝") || cat.contains("移动电源")) {
                return CapabilityResult.match(0.7, List.of("充电宝类商品，容量通常较大"));
            }
        }

        return CapabilityResult.noMatch(List.of("未找到长续航相关信息"));
    }
}
