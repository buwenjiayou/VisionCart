package com.visioncart.service.search.strategy;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.IntentGate;
import com.visioncart.service.search.ProductIntent;
import com.visioncart.service.search.QueryPlan;
import com.visioncart.service.search.RelaxReason;
import com.visioncart.service.search.SearchTelemetry;

import java.util.List;

/**
 * 垂直类目搜索策略接口。
 *
 * <p>每个高频类目可以有自己的策略实现，决定：
 * <ul>
 *   <li>搜索词怎么生成（QueryPlan）</li>
 *   <li>商品怎么分类（IntentTier）</li>
 *   <li>结果怎么混合（ResultMixer）</li>
 *   <li>补召回怎么放宽</li>
 * </ul>
 *
 * <p>没有特殊策略的类目走 {@link DefaultProductIntentStrategy}。
 */
public interface VerticalSearchStrategy {

    /**
     * 是否支持该意图。
     * 通常按 productFamily 判断。
     */
    boolean supports(ProductIntent intent);

    /**
     * 生成搜索查询计划。
     */
    QueryPlan buildQueryPlan(ProductIntent intent, SearchFilter filter);

    /**
     * 对单个商品进行意图分类。
     */
    IntentGate.IntentTier classify(ProductIntent intent, ProductCard product);

    /**
     * 批量分类：对候选池中每个商品进行意图分类，过滤掉 REJECT。
     * 用于构建 classifiedPool（Top300）。
     *
     * @param products 候选商品列表
     * @param intent   商品意图
     * @return 分类后的商品列表（不含 REJECT）
     */
    default List<ClassifiedProduct> classifyAll(List<ProductCard> products, ProductIntent intent) {
        return products.stream()
                .map(p -> new ClassifiedProduct(p, classify(intent, p)))
                .filter(cp -> cp.tier() != IntentGate.IntentTier.REJECT)
                .toList();
    }

    /**
     * 结果混合：决定各类商品的比例。
     *
     * @param intent     商品意图
     * @param classified 分类后的商品（已按 IntentTier 分组）
     * @param pageSize   目标页大小
     * @return 混合后的商品列表
     */
    List<ProductCard> mix(ProductIntent intent, List<ClassifiedProduct> classified, int pageSize);

    default MixPolicy policy(ProductIntent intent) {
        return MixPolicy.defaults();
    }

    default QueryPlan relax(ProductIntent intent, SearchFilter filter, RelaxReason reason, SearchTelemetry telemetry) {
        if (telemetry != null) {
            telemetry.put("relaxReason", reason);
        }
        return buildQueryPlan(allowBrandRelaxation(intent) ? intent.withRelaxedBrand() : intent, filter);
    }

    /**
     * 补召回时是否放宽品牌约束。
     * 默认 true（品牌不可靠时去掉品牌重试）。
     */
    default boolean allowBrandRelaxation(ProductIntent intent) {
        return !intent.hasReliableBrand();
    }

    /**
     * 已分类的商品。
     */
    record ClassifiedProduct(ProductCard product, IntentGate.IntentTier tier) {}
}
