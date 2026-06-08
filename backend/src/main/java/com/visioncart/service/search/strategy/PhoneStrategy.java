package com.visioncart.service.search.strategy;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.service.search.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 手机垂直搜索策略。
 *
 * <p>核心规则：
 * <ul>
 *   <li>手机是 MAIN_PRODUCT，品牌是制造商品牌</li>
 *   <li>手机壳、手机膜、手机套必须 REJECT</li>
 *   <li>手机配件（充电器、数据线）限量出现</li>
 * </ul>
 */
@Component
public class PhoneStrategy extends DefaultProductIntentStrategy {

    private static final List<String> PHONE_CASE_TERMS = List.of(
            "手机壳", "手机套", "保护壳", "保护套", "手机保护壳", "手机保护套",
            "手机膜", "钢化膜", "贴膜", "手机贴膜", "case", "screen protector");

    private static final List<String> PHONE_ACCESSORY = List.of(
            "充电器", "数据线", "充电线", "耳机", "手机支架", "车载支架",
            "充电宝", "移动电源", "手机膜");

    public PhoneStrategy(QueryPlanner queryPlanner, IntentGate intentGate) {
        super(queryPlanner, intentGate);
    }

    @Override
    public boolean supports(ProductIntent intent) {
        return "phone".equals(intent.productFamily());
    }

    @Override
    public IntentGate.IntentTier classify(ProductIntent intent, ProductCard product) {
        if (product == null || product.title() == null || product.title().isBlank()) {
            return IntentGate.IntentTier.REJECT;
        }

        String title = SearchTextUtils.normalizeForMatch(product.title());
        String brand = intent.bestBrand();

        // 1. 手机壳/手机膜 → REJECT
        for (String term : PHONE_CASE_TERMS) {
            if (SearchTextUtils.containsNormalized(title, term)) {
                return IntentGate.IntentTier.REJECT;
            }
        }

        // 2. 手机配件 → RELATED_ACCESSORY
        for (String acc : PHONE_ACCESSORY) {
            if (SearchTextUtils.containsNormalized(title, acc)) {
                return IntentGate.IntentTier.RELATED_ACCESSORY;
            }
        }

        // 3. 委托给默认策略处理主品分类
        return super.classify(intent, product);
    }
}
