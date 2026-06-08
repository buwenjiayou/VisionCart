package com.visioncart.service.search.strategy;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.service.search.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 耳机垂直搜索策略。
 *
 * <p>核心规则：
 * <ul>
 *   <li>耳机是 MAIN_PRODUCT，品牌是制造商品牌</li>
 *   <li>耳机壳、耳机套、耳帽必须 REJECT</li>
 *   <li>耳机线、耳塞等配件限量出现</li>
 * </ul>
 */
@Component
public class EarbudsStrategy extends DefaultProductIntentStrategy {

    private static final List<String> EARPHONE_CASE_TERMS = List.of(
            "耳机壳", "耳机套", "耳机保护壳", "耳机保护套", "耳帽", "耳塞套",
            "earphone case", "earbuds case", "ear tips");

    private static final List<String> EARPHONE_ACCESSORY = List.of(
            "耳机线", "耳塞", "耳挂", "耳机包", "充电盒", "charging case");

    public EarbudsStrategy(QueryPlanner queryPlanner, IntentGate intentGate) {
        super(queryPlanner, intentGate);
    }

    @Override
    public boolean supports(ProductIntent intent) {
        return "earbuds".equals(intent.productFamily());
    }

    @Override
    public IntentGate.IntentTier classify(ProductIntent intent, ProductCard product) {
        if (product == null || product.title() == null || product.title().isBlank()) {
            return IntentGate.IntentTier.REJECT;
        }

        String title = SearchTextUtils.normalizeForMatch(product.title());

        // 1. 耳机壳/耳机套 → REJECT
        for (String term : EARPHONE_CASE_TERMS) {
            if (SearchTextUtils.containsNormalized(title, term)) {
                return IntentGate.IntentTier.REJECT;
            }
        }

        // 2. 耳机配件 → RELATED_ACCESSORY
        for (String acc : EARPHONE_ACCESSORY) {
            if (SearchTextUtils.containsNormalized(title, acc)) {
                return IntentGate.IntentTier.RELATED_ACCESSORY;
            }
        }

        // 3. 委托给默认策略处理主品分类
        return super.classify(intent, product);
    }
}
