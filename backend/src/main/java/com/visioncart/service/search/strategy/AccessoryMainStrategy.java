package com.visioncart.service.search.strategy;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.service.search.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用配件主品策略（耳机壳、电脑包、表带、键盘膜等）。
 *
 * <p>适用于所有 ACCESSORY_MAIN 角色但没有专属策略的类目。
 * 核心规则：
 * <ul>
 *   <li>品牌是适配品牌（不是制造商品牌）</li>
 *   <li>父品类主商品必须 REJECT（搜耳机壳不要耳机）</li>
 *   <li>同族配件可以 SAME_FAMILY</li>
 * </ul>
 */
@Component
public class AccessoryMainStrategy extends DefaultProductIntentStrategy {

    public AccessoryMainStrategy(QueryPlanner queryPlanner, IntentGate intentGate) {
        super(queryPlanner, intentGate);
    }

    @Override
    public boolean supports(ProductIntent intent) {
        // 适用于所有 ACCESSORY_MAIN 角色
        // 但 PhoneCaseStrategy 优先级更高（先注册），所以手机壳不会走这里
        return intent.productRole() == ProductIntent.ProductRole.ACCESSORY_MAIN;
    }

    @Override
    public IntentGate.IntentTier classify(ProductIntent intent, ProductCard product) {
        // 使用 IntentGate 的默认分类逻辑（已经处理了 ACCESSORY_MAIN 语义）
        return intentGate.classify(intent, product);
    }
}
