package com.visioncart.service.search;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 查询规划器：从 ProductIntent 生成分层搜索词。
 *
 * <p>核心规则：
 * <ul>
 *   <li>relatedOnly 词不能单独成为查询，只能与主商品组合</li>
 *   <li>feature 词可以和主商品组合，但不能单独搜索</li>
 *   <li>Level 1 查询优先，Level 2 补充，Level 3 兜底</li>
 * </ul>
 */
@Component
public class QueryPlanner {
    private static final Logger log = LoggerFactory.getLogger(QueryPlanner.class);

    private final ProductTaxonomyRegistry taxonomy;

    public QueryPlanner(ProductTaxonomyRegistry taxonomy) {
        this.taxonomy = taxonomy;
    }

    /**
     * 从 ProductIntent 生成查询计划。
     */
    public QueryPlan plan(ProductIntent intent) {
        List<String> primary = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        List<String> fallback = new ArrayList<>();

        String product = intent.canonicalProduct();
        String brand = intent.hasReliableBrand() ? intent.bestBrand() : "";
        String model = intent.model();
        String feature = firstImportantFeature(intent.featureTerms());

        // === Level 1: 精准主商品查询 ===
        // brand + model + feature + product
        add(primary, brand, model, feature, product);
        // brand + model + product
        add(primary, brand, model, product);
        // brand + feature + product
        add(primary, brand, feature, product);
        // brand + product
        add(primary, brand, product);
        // feature + product
        add(primary, feature, product);

        // === Level 2: 放宽但不跑偏 ===
        // relatedOnly 词只能与主商品组合
        for (String term : intent.relatedOnlyTerms()) {
            add(secondary, product, term);
            if (!brand.isBlank()) {
                add(secondary, brand, product, term);
            }
        }
        // 其他 feature 词组合
        for (String f : intent.featureTerms()) {
            if (!f.equals(feature)) {
                add(secondary, f, product);
            }
        }
        // 软约束属性组合
        for (Map.Entry<String, String> soft : intent.softAttributes().entrySet()) {
            add(secondary, product, soft.getValue());
        }

        // === Level 3: 兜底 ===
        add(fallback, product);

        // === 禁止单独查询的词 ===
        List<String> forbidden = new ArrayList<>(intent.relatedOnlyTerms());
        // feature 词也不能单独查询
        forbidden.addAll(intent.featureTerms().stream()
                .filter(f -> f.length() <= 3) // 只禁止短 feature 词（如"透明"、"磨砂"）
                .toList());

        // 去重并限制数量
        QueryPlan plan = new QueryPlan(
                unique(primary).stream().limit(4).toList(),
                unique(secondary).stream().limit(3).toList(),
                unique(fallback).stream().limit(2).toList(),
                forbidden,
                debugMap(intent)
        );

        log.info("QueryPlan: primary={}, secondary={}, fallback={}, forbidden={}",
                plan.primaryQueries(), plan.secondaryQueries(),
                plan.fallbackQueries(), plan.forbiddenStandaloneTerms());

        return plan;
    }

    /**
     * 获取第一优先级 feature 词（最长的 feature 词）。
     */
    private String firstImportantFeature(List<String> features) {
        if (features == null || features.isEmpty()) return "";
        return features.stream()
                .max(Comparator.comparingInt(String::length))
                .orElse("");
    }

    /**
     * 将多个非空部分组合成一个查询词，加入列表。
     */
    private void add(List<String> list, String... parts) {
        String joined = Arrays.stream(parts)
                .map(SearchTextUtils::useful)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(" "));
        if (!joined.isBlank()) {
            list.add(joined);
        }
    }

    /**
     * 去重，保持顺序。
     */
    private List<String> unique(List<String> list) {
        return new ArrayList<>(new LinkedHashSet<>(list));
    }

    private Map<String, String> debugMap(ProductIntent intent) {
        Map<String, String> debug = new LinkedHashMap<>();
        debug.put("canonicalProduct", intent.canonicalProduct());
        debug.put("productRole", intent.productRole().name());
        debug.put("productBrand", intent.productBrand());
        debug.put("compatibleBrand", intent.compatibleBrand());
        debug.put("model", intent.model());
        debug.put("featureTerms", intent.featureTerms().toString());
        debug.put("relatedOnlyTerms", intent.relatedOnlyTerms().toString());
        return debug;
    }
}
