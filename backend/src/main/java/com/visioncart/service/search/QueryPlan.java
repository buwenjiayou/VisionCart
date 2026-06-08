package com.visioncart.service.search;

import java.util.List;
import java.util.Map;

/**
 * 分层查询计划：从 ProductIntent 生成的结构化搜索词方案。
 *
 * <p>三级查询：
 * <ul>
 *   <li>primaryQueries — 精准主商品查询，占 60% 预算</li>
 *   <li>secondaryQueries — 放宽但不跑偏，占 30%</li>
 *   <li>fallbackQueries — 兜底，仅主结果不足时启用</li>
 * </ul>
 */
public record QueryPlan(
        /** 精准主商品查询（Level 1） */
        List<String> primaryQueries,
        /** 放宽查询（Level 2） */
        List<String> secondaryQueries,
        /** 兜底查询（Level 3） */
        List<String> fallbackQueries,
        /** 禁止单独成为平台 query 的词 */
        List<String> forbiddenStandaloneTerms,
        /** 调试信息 */
        Map<String, String> debug
) {
    /** 获取所有查询（primary + secondary），用于主召回 */
    public List<String> allPrimaryAndSecondary() {
        List<String> all = new java.util.ArrayList<>(primaryQueries);
        all.addAll(secondaryQueries);
        return all;
    }

    /** 获取所有查询（primary + secondary + fallback），用于完整召回 */
    public List<String> allQueries() {
        List<String> all = new java.util.ArrayList<>(primaryQueries);
        all.addAll(secondaryQueries);
        all.addAll(fallbackQueries);
        return all;
    }

    /** 验证一个查询词是否合法（不是禁止单独使用的词） */
    public boolean isQueryAllowed(String query) {
        if (query == null || query.isBlank()) return false;
        String trimmed = query.trim();
        for (String forbidden : forbiddenStandaloneTerms) {
            if (trimmed.equals(forbidden)) return false;
        }
        return true;
    }
}
