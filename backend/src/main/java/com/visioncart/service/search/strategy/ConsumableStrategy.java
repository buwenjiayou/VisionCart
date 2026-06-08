package com.visioncart.service.search.strategy;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.*;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 耗材垂直搜索策略（墨盒、滤芯、刀头、电池等）。
 *
 * <p>核心规则：
 * <ul>
 *   <li>适配型号比品牌更重要（HP 打印机用 HP 墨盒，但兼容墨盒也应保留）</li>
 *   <li>品牌不能简单硬拒——兼容墨盒/通用滤芯是正常商品</li>
 *   <li>适配型号必须出现在标题中</li>
 *   <li>不同型号的耗材不能混入</li>
 * </ul>
 */
@Component
public class ConsumableStrategy extends DefaultProductIntentStrategy {

    public ConsumableStrategy(QueryPlanner queryPlanner, IntentGate intentGate) {
        super(queryPlanner, intentGate);
    }

    @Override
    public boolean supports(ProductIntent intent) {
        return intent.productRole() == ProductIntent.ProductRole.CONSUMABLE;
    }

    @Override
    public IntentGate.IntentTier classify(ProductIntent intent, ProductCard product) {
        if (product == null || product.title() == null || product.title().isBlank()) {
            return IntentGate.IntentTier.REJECT;
        }

        String title = SearchTextUtils.normalizeForMatch(product.title());
        String canonicalProduct = intent.canonicalProduct();

        // 1. 标题必须含主品词（墨盒、滤芯等）
        boolean hasProduct = SearchTextUtils.containsNormalized(title, canonicalProduct);
        if (!hasProduct) {
            // 尝试 token 匹配
            if (!matchesByTokens(title, canonicalProduct)) {
                return IntentGate.IntentTier.REJECT;
            }
        }

        // 2. 适配型号检查（如果有型号，标题必须含型号）
        String model = intent.model();
        if (!model.isBlank()) {
            boolean hasModel = SearchTextUtils.containsNormalized(title, model);
            if (hasModel) {
                // 型号匹配 → 品牌作为加分项而非硬约束
                String brand = intent.bestBrand();
                if (!brand.isBlank() && BrandMatcher.productMatchesExpectedBrand(product, brand)) {
                    return IntentGate.IntentTier.EXACT_MAIN;
                }
                // 型号匹配但无品牌 → EXACT_MAIN（耗材场景品牌不是硬约束）
                return IntentGate.IntentTier.EXACT_MAIN;
            }
            // 型号不匹配 → REJECT
            return IntentGate.IntentTier.REJECT;
        }

        // 3. 无型号时，走默认策略
        return super.classify(intent, product);
    }

    @Override
    public boolean allowBrandRelaxation(ProductIntent intent) {
        // 耗材场景始终允许品牌放宽（兼容耗材是正常商品）
        return true;
    }

    private boolean matchesByTokens(String title, String canonicalProduct) {
        if (title.isBlank() || canonicalProduct.isBlank()) return false;
        java.util.List<String> tokens = java.util.Arrays.stream(canonicalProduct.split("[\\s,，、]+"))
                .map(SearchTextUtils::normalizeForMatch)
                .filter(t -> t.length() >= 2)
                .toList();
        if (tokens.isEmpty()) return false;
        return tokens.stream().allMatch(title::contains);
    }
}
