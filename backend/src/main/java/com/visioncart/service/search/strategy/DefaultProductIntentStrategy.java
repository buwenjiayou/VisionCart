package com.visioncart.service.search.strategy;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.IntentGate;
import com.visioncart.service.search.ProductIntent;
import com.visioncart.service.search.QueryPlan;
import com.visioncart.service.search.QueryPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 默认商品意图搜索策略。
 *
 * <p>所有没有专属垂直策略的类目都走这个默认策略。
 * 封装了通用的 QueryPlan 生成、IntentGate 分类、结果混合逻辑。
 */
@Component
public class DefaultProductIntentStrategy implements VerticalSearchStrategy {
    private static final Logger log = LoggerFactory.getLogger(DefaultProductIntentStrategy.class);

    protected final QueryPlanner queryPlanner;
    protected final IntentGate intentGate;

    public DefaultProductIntentStrategy(QueryPlanner queryPlanner, IntentGate intentGate) {
        this.queryPlanner = queryPlanner;
        this.intentGate = intentGate;
    }

    @Override
    public boolean supports(ProductIntent intent) {
        return true; // 默认策略支持所有意图
    }

    @Override
    public QueryPlan buildQueryPlan(ProductIntent intent, SearchFilter filter) {
        return queryPlanner.plan(intent);
    }

    @Override
    public IntentGate.IntentTier classify(ProductIntent intent, ProductCard product) {
        return intentGate.classify(intent, product);
    }

    @Override
    public List<ProductCard> mix(ProductIntent intent, List<ClassifiedProduct> classified, int pageSize) {
        if (classified != null) {
            List<ProductCard> result = ResultMixer.mix(classified, policy(intent), pageSize);
            log.info("DefaultStrategy mix: {} classified -> {} total (pageSize={})",
                    classified.size(), result.size(), pageSize);
            return result;
        }
        // 按 tier 分组
        Map<IntentGate.IntentTier, List<ProductCard>> byTier = classified.stream()
                .collect(Collectors.groupingBy(
                        ClassifiedProduct::tier,
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(ClassifiedProduct::product, Collectors.toList())));

        // 强信号层：EXACT_MAIN + COMPATIBLE_MAIN + SAME_FAMILY
        List<ProductCard> strong = new ArrayList<>();
        strong.addAll(byTier.getOrDefault(IntentGate.IntentTier.EXACT_MAIN, List.of()));
        strong.addAll(byTier.getOrDefault(IntentGate.IntentTier.COMPATIBLE_MAIN, List.of()));
        strong.addAll(byTier.getOrDefault(IntentGate.IntentTier.SAME_FAMILY, List.of()));

        // RELATED_ACCESSORY：限量填充（不超过 pageSize 的 10%，至少 1 个，最多 5 个）
        // Problem 5 fix: 20% 太高，10% 更合理（Top20 最多 2 个，Top50 最多 5 个）
        List<ProductCard> relatedAccessory = byTier.getOrDefault(IntentGate.IntentTier.RELATED_ACCESSORY, List.of());
        int accessoryCap = Math.max(1, Math.min(5, (int) (pageSize * 0.1)));

        // 合并结果：strong 优先，RELATED_ACCESSORY 补充
        List<ProductCard> result = new ArrayList<>(strong);
        LinkedHashSet<String> seen = strong.stream().map(ProductCard::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (result.size() < pageSize) {
            int remaining = Math.min(accessoryCap, pageSize - result.size());
            relatedAccessory.stream()
                    .filter(p -> seen.add(p.id()))
                    .limit(remaining)
                    .forEach(result::add);
        }

        log.info("DefaultStrategy mix: {} strong + {} accessory = {} total (pageSize={})",
                strong.size(), result.size() - strong.size(), result.size(), pageSize);
        return result;
    }
}
