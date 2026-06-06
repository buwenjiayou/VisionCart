package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates whether a product is suitable for running/jogging.
 * Applies to: shoes, clothing, armbands, watches, headphones, water bottles.
 */
@Component
public class RunningSuitableEvaluator implements CapabilityEvaluator {

    private static final List<String> RUNNING_KEYWORDS = List.of(
            "跑步", "跑鞋", "马拉松", "慢跑", "jogging", "running",
            "运动鞋", "轻便", "透气", "减震", "缓震", "回弹",
            "速干", "排汗", "运动服", "健身", "gym", "workout"
    );

    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "正装", "皮鞋", "高跟鞋", "凉鞋", "拖鞋", "雪地靴",
            "鞋垫", "护膝", "鞋套", "鞋带"
    );

    @Override
    public String capabilityCode() {
        return "running_suitable";
    }

    @Override
    public boolean supports(String categoryCode) {
        return true;
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        String text = ProductFeatureExtractor.buildText(product);

        // Check negatives FIRST — prevents "跑步鞋垫" from matching running_suitable
        for (String neg : NEGATIVE_KEYWORDS) {
            if (text.contains(neg)) {
                return CapabilityResult.noMatch(List.of(neg + "不适合跑步"));
            }
        }

        for (String keyword : RUNNING_KEYWORDS) {
            if (text.contains(keyword)) {
                return CapabilityResult.match(0.85, List.of("包含跑步相关关键词: " + keyword));
            }
        }

        if (category != null) {
            String cat = category.toLowerCase();
            if (cat.contains("鞋") || cat.contains("shoe") || cat.contains("跑鞋")) {
                return CapabilityResult.uncertain(0.5, List.of("运动鞋类商品，可能适合跑步"), null);
            }
            if (cat.contains("耳机") || cat.contains("earphone")) {
                if (text.contains("运动") || text.contains("防水") || text.contains("防汗")) {
                    return CapabilityResult.match(0.7, List.of("运动耳机，适合跑步使用"));
                }
                return CapabilityResult.uncertain(0.4, List.of("耳机类商品，可能适合运动"), null);
            }
        }

        return CapabilityResult.noMatch(List.of("未找到适合跑步的相关信息"));
    }
}
