package com.visioncart.service.search.strategy;

import com.visioncart.service.search.ProductIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 垂直策略注册表。
 *
 * <p>按优先级遍历所有注册的 VerticalSearchStrategy，
 * 返回第一个 supports() 为 true 的策略。
 * 如果没有特殊策略，返回默认策略。
 */
@Component
public class VerticalStrategyRegistry {
    private static final Logger log = LoggerFactory.getLogger(VerticalStrategyRegistry.class);

    private final List<VerticalSearchStrategy> strategies;
    private final DefaultProductIntentStrategy defaultStrategy;

    public VerticalStrategyRegistry(
            @org.springframework.beans.factory.annotation.Autowired java.util.List<VerticalSearchStrategy> strategies,
            @org.springframework.beans.factory.annotation.Qualifier("defaultProductIntentStrategy") DefaultProductIntentStrategy defaultStrategy) {
        this.strategies = strategies;
        this.defaultStrategy = defaultStrategy;
    }

    /**
     * 解析适合该意图的搜索策略。
     * 垂直策略优先于默认策略。
     */
    public VerticalSearchStrategy resolve(ProductIntent intent) {
        for (VerticalSearchStrategy strategy : strategies) {
            // 跳过默认策略（它总是 supports=true，放在最后兜底）
            if (strategy == defaultStrategy) continue;
            if (strategy.supports(intent)) {
                log.info("Strategy resolved: {} for family={}, product={}",
                        strategy.getClass().getSimpleName(),
                        intent.productFamily(), intent.canonicalProduct());
                return strategy;
            }
        }
        log.debug("Strategy resolved: DefaultProductIntentStrategy for family={}", intent.productFamily());
        return defaultStrategy;
    }
}
