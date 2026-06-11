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
        if (PowerBankSearchRules.isPowerBank(intent)) {
            return powerBankPlan(intent);
        }

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

    private QueryPlan powerBankPlan(ProductIntent intent) {
        List<String> primary = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        List<String> fallback = new ArrayList<>();

        String product = StringUtils.defaultIfBlank(intent.canonicalProduct(), "充电宝");
        String brand = intent.hasReliableBrand() ? intent.bestBrand() : "";

        for (String spec : powerBankSpecs(intent)) {
            add(primary, brand, spec, product);
        }

        List<String> visualFromSoftAttributes = powerBankVisualsFromSoftAttributes(intent, true);
        List<String> safePrimaryVisuals = safePowerBankRetrievalVisuals(visualFromSoftAttributes);
        for (String visual : safePrimaryVisuals) {
            add(primary, brand, visual, product);
        }

        List<String> visualFromFeatures = powerBankVisualsFromFeatures(intent);
        for (String visual : safePowerBankRetrievalVisuals(visualFromFeatures)) {
            add(secondary, visual, product);
            if (!brand.isBlank()) {
                add(secondary, brand, visual, product);
            }
        }

        List<String> extraVisualFromSoftAttributes = powerBankVisualsFromSoftAttributes(intent, false);
        for (String visual : safePowerBankRetrievalVisuals(extraVisualFromSoftAttributes)) {
            add(secondary, visual, product);
        }

        for (String related : safeList(intent.relatedOnlyTerms())) {
            if (PowerBankSearchRules.isUnsafePowerBankRetrievalTerm(related)) {
                continue;
            }
            add(secondary, product, related);
        }

        add(primary, brand, product);
        add(primary, product);
        add(fallback, product);
        if (!"充电宝".equals(product)) {
            add(fallback, "充电宝");
        }
        if (!"移动电源".equals(product)) {
            add(fallback, "移动电源");
        }

        List<String> forbidden = new ArrayList<>();
        forbidden.addAll(List.of("LED灯", "黄色LED", "透明", "黄色", "指示灯", "小夜灯", "台灯", "灯泡"));
        forbidden.addAll(visualFromSoftAttributes);
        forbidden.addAll(extraVisualFromSoftAttributes);
        forbidden.addAll(visualFromFeatures);

        QueryPlan plan = new QueryPlan(
                unique(primary).stream().limit(4).toList(),
                unique(secondary).stream().limit(4).toList(),
                unique(fallback).stream().limit(2).toList(),
                unique(forbidden),
                debugMap(intent)
        );

        log.info("PowerBank QueryPlan: primary={}, secondary={}, fallback={}, forbidden={}",
                plan.primaryQueries(), plan.secondaryQueries(),
                plan.fallbackQueries(), plan.forbiddenStandaloneTerms());
        return plan;
    }

    private List<String> powerBankSpecs(ProductIntent intent) {
        LinkedHashSet<String> specs = new LinkedHashSet<>();
        addPowerBankSpecs(specs, intent.model());
        if (intent.hardAttributes() != null) {
            intent.hardAttributes().values().forEach(value -> addPowerBankSpecs(specs, value));
        }
        for (String feature : safeList(intent.featureTerms())) {
            if (PowerBankSearchRules.isStrongSpecOrFeature(feature)) {
                specs.add(feature);
            }
            addPowerBankSpecs(specs, feature);
        }
        return new ArrayList<>(specs);
    }

    private void addPowerBankSpecs(LinkedHashSet<String> specs, String value) {
        specs.addAll(PowerBankSearchRules.extractSpecs(value));
    }

    private List<String> powerBankVisualsFromSoftAttributes(ProductIntent intent, boolean primaryOnly) {
        LinkedHashSet<String> visuals = new LinkedHashSet<>();
        if (intent.softAttributes() != null) {
            for (Map.Entry<String, String> entry : intent.softAttributes().entrySet()) {
                if (primaryOnly && !List.of("款式", "类型", "风格").contains(entry.getKey())) {
                    continue;
                }
                addPowerBankVisual(visuals, entry.getValue(), intent.canonicalProduct());
            }
        }
        return new ArrayList<>(visuals);
    }

    private List<String> powerBankVisualsFromFeatures(ProductIntent intent) {
        LinkedHashSet<String> visuals = new LinkedHashSet<>();
        for (String feature : safeList(intent.featureTerms())) {
            if (!PowerBankSearchRules.isStrongSpecOrFeature(feature)) {
                addPowerBankVisual(visuals, feature, intent.canonicalProduct());
            }
        }
        return new ArrayList<>(visuals);
    }

    private void addPowerBankVisual(LinkedHashSet<String> visuals, String value, String canonicalProduct) {
        String cleaned = PowerBankSearchRules.cleanDescriptor(value, canonicalProduct);
        if (!PowerBankSearchRules.isUsefulVisualDescriptor(cleaned)) {
            return;
        }
        visuals.addAll(PowerBankSearchRules.expandVisualDescriptor(cleaned));
    }

    private List<String> safePowerBankRetrievalVisuals(List<String> visuals) {
        return safeList(visuals).stream()
                .filter(PowerBankSearchRules::isSafeRetrievalVisual)
                .toList();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
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
