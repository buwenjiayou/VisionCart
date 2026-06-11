package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.strategy.VerticalSearchStrategy.ClassifiedProduct;

import java.util.List;

/**
 * 完整候选池（Top300），作为搜索会话的"事实源"。
 * 所有排序、筛选、NLP、撤回都基于此池重新计算 displayPage。
 *
 * @param sessionId        会话 ID
 * @param searchRunId      搜索运行 ID（UUID，用于防过期）
 * @param searchIdentity   搜索身份哈希（用于锁机制去重）
 * @param productIntent    商品意图（分类用）
 * @param strategyName     使用的策略类名
 * @param baseFilter       搜索时的原始 filter
 * @param classifiedPool   Top300 带 tier 分类的候选池
 * @param rawCandidateCount 原始候选数（去重前）
 */
public record SearchCandidatePool(
        String sessionId,
        String searchRunId,
        String searchIdentity,
        ProductIntent productIntent,
        String strategyName,
        SearchFilter baseFilter,
        List<ClassifiedProduct> classifiedPool,
        int rawCandidateCount,
        List<ProductCard> rawCandidates,
        SearchFilter currentFilter,
        String currentSort,
        List<ProductCard> currentDisplayPage,
        Boolean domestic
) {
    public SearchCandidatePool(String sessionId,
                               String searchRunId,
                               String searchIdentity,
                               ProductIntent productIntent,
                               String strategyName,
                               SearchFilter baseFilter,
                               List<ClassifiedProduct> classifiedPool,
                               int rawCandidateCount,
                               List<ProductCard> rawCandidates,
                               SearchFilter currentFilter,
                               String currentSort,
                               List<ProductCard> currentDisplayPage) {
        this(sessionId, searchRunId, searchIdentity, productIntent, strategyName, baseFilter,
                classifiedPool, rawCandidateCount, rawCandidates, currentFilter, currentSort,
                currentDisplayPage, Boolean.TRUE);
    }

    public SearchCandidatePool(String sessionId,
                               String searchRunId,
                               String searchIdentity,
                               ProductIntent productIntent,
                               String strategyName,
                               SearchFilter baseFilter,
                               List<ClassifiedProduct> classifiedPool,
                               int rawCandidateCount) {
        this(sessionId, searchRunId, searchIdentity, productIntent, strategyName, baseFilter,
                classifiedPool, rawCandidateCount, List.of(), baseFilter,
                baseFilter != null ? baseFilter.sortBy() : null, List.of(), Boolean.TRUE);
    }

    public SearchCandidatePool {
        classifiedPool = classifiedPool == null ? List.of() : classifiedPool;
        rawCandidates = rawCandidates == null ? List.of() : rawCandidates;
        currentFilter = currentFilter != null ? currentFilter
                : baseFilter != null ? baseFilter : SearchFilter.empty();
        currentSort = currentSort != null ? currentSort : currentFilter.sortBy();
        currentDisplayPage = currentDisplayPage == null ? List.of() : currentDisplayPage;
        domestic = domestic == null ? Boolean.TRUE : domestic;
    }

    /**
     * 获取去重后的候选总数。
     */
    public int poolSize() {
        return classifiedPool.size();
    }

    /**
     * 提取所有 ProductCard（丢弃 tier）。
     * 用于需要 List<ProductCard> 的场景（如 CandidateFilterService）。
     */
    public List<ProductCard> toProductCards() {
        return classifiedPool.stream()
                .map(ClassifiedProduct::product)
                .toList();
    }

    public boolean isDomestic() {
        return Boolean.TRUE.equals(domestic);
    }

    public SearchCandidatePool withCurrentState(SearchFilter filter, List<ProductCard> displayPage) {
        SearchFilter effectiveFilter = filter != null ? filter : SearchFilter.empty();
        return new SearchCandidatePool(
                sessionId,
                searchRunId,
                searchIdentity,
                productIntent,
                strategyName,
                baseFilter,
                classifiedPool,
                rawCandidateCount,
                rawCandidates,
                effectiveFilter,
                effectiveFilter.sortBy(),
                displayPage == null ? List.of() : displayPage,
                domestic
        );
    }
}
