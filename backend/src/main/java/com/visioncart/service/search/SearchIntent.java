package com.visioncart.service.search;

import com.visioncart.api.dto.SearchFilter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record SearchIntent(
        String brand,
        boolean reliableBrand,
        List<String> keywords,
        List<String> categoryChain,
        String category,
        String coreProduct,
        List<String> exactTerms,
        List<String> descriptiveTerms,
        List<String> modifierTerms,
        String mainCategoryCode,
        String intentRole,
        boolean strictIntent
) {
    private static final Pattern MODEL_TOKEN = Pattern.compile("(?i)\\b[a-z]{0,8}\\d[a-z0-9-]{1,16}\\b");

    private static final List<String> EXACT_ATTRIBUTE_KEYS = List.of(
            "型号", "model", "系列", "series", "货号", "item_no", "sku", "SKU",
            "规格", "spec", "容量", "capacity", "存储", "storage", "尺寸", "size", "尺码"
    );
    private static final List<String> DESCRIPTIVE_ATTRIBUTE_KEYS = List.of(
            "颜色", "color", "款式", "style", "材质", "material", "图案", "pattern",
            "类型", "type", "用途", "usage", "适用场景", "适用人群", "风格"
    );
    private static final List<String> GENERIC_TERMS = List.of(
            "商品", "产品", "同款", "爆款", "热卖", "新款", "常规", "通用", "标准",
            "运动", "休闲", "商务", "简约", "时尚", "经典",
            "热销", "正品", "特价", "包邮", "旗舰", "新品", "升级", "加厚", "加大"
    );
    private static final List<String> GENERIC_CJK_TERMS = List.of(
            "好看", "好用", "便宜", "大码", "小码", "均码", "百搭", "显瘦", "显高"
    );

    public SearchIntent {
        brand = SearchTextUtils.useful(brand);
        keywords = List.copyOf(keywords == null ? List.of() : keywords);
        categoryChain = List.copyOf(categoryChain == null ? List.of() : categoryChain);
        category = SearchTextUtils.useful(category);
        coreProduct = SearchTextUtils.useful(coreProduct);
        exactTerms = List.copyOf(exactTerms == null ? List.of() : exactTerms);
        descriptiveTerms = List.copyOf(descriptiveTerms == null ? List.of() : descriptiveTerms);
        modifierTerms = List.copyOf(modifierTerms == null ? List.of() : modifierTerms);
        mainCategoryCode = mainCategoryCode == null ? "" : mainCategoryCode;
        intentRole = intentRole == null ? "main" : intentRole;
    }

    public static SearchIntent from(Map<String, String> attributes, SearchFilter filter) {
        Map<String, String> source = attributes == null ? Map.of() : attributes;
        String brand = SearchTextUtils.useful(source.get(SearchTextUtils.ATTR_BRAND));
        boolean reliableBrand = Boolean.parseBoolean(source.getOrDefault(SearchTextUtils.ATTR_BRAND_RELIABLE, "false"));
        boolean strictIntent = Boolean.parseBoolean(source.getOrDefault(SearchTextUtils.ATTR_STRICT_INTENT, "false"));

        LinkedHashSet<String> categories = new LinkedHashSet<>();
        addSplit(categories, source.get(SearchTextUtils.ATTR_CATEGORY_CHAIN));
        add(categories, source.get(SearchTextUtils.ATTR_CATEGORY));
        String category = categories.stream().findFirst().orElse("");

        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        addSplit(keywords, source.get(SearchTextUtils.ATTR_KEYWORDS));
        addSplit(keywords, source.get(SearchTextUtils.ATTR_KEYWORD));
        if (filter != null) {
            addSplit(keywords, SearchTextUtils.positiveKeyword(filter.keyword()));
        }

        LinkedHashSet<String> exactTerms = new LinkedHashSet<>();
        for (String key : EXACT_ATTRIBUTE_KEYS) {
            addSpecific(exactTerms, source.get(key));
        }
        keywords.forEach(keyword -> addModelTokens(exactTerms, keyword));

        LinkedHashSet<String> descriptiveTerms = new LinkedHashSet<>();
        for (String key : DESCRIPTIVE_ATTRIBUTE_KEYS) {
            addSpecific(descriptiveTerms, source.get(key));
        }

        String core = SearchTextUtils.coreProductToken(category);
        if (core.isBlank() && !keywords.isEmpty()) {
            core = SearchTextUtils.coreProductToken(keywords.iterator().next());
        }

        if (keywords.isEmpty()) {
            add(keywords, join(brand, core.isBlank() ? category : core));
            add(keywords, category);
        }

        // 提取修饰词（电动、无线、智能等）
        LinkedHashSet<String> modifiers = new LinkedHashSet<>();
        String catModifier = SearchTextUtils.extractModifier(category);
        if (!catModifier.isBlank()) {
            modifiers.add(catModifier);
        }
        for (String kw : keywords) {
            String kwModifier = SearchTextUtils.extractModifier(kw);
            if (!kwModifier.isBlank()) {
                modifiers.add(kwModifier);
            }
        }

        // 结构化品类归一
        List<String> kwList = limit(keywords, 8);
        String mainCategoryCode = CategoryNormalizer.normalize(category, kwList);
        String intentRole = CategoryNormalizer.intentRole(category, kwList);

        return new SearchIntent(
                brand,
                reliableBrand && !brand.isBlank(),
                kwList,
                limit(categories, 4),
                category,
                core,
                limit(exactTerms, 8),
                limit(descriptiveTerms, 8),
                limit(modifiers, 4),
                mainCategoryCode,
                intentRole,
                strictIntent
        );
    }

    public boolean hasReliableBrand() {
        return reliableBrand && !brand.isBlank();
    }

    public boolean hasSpecificSignals() {
        return !keywords.isEmpty()
                || !category.isBlank()
                || !coreProduct.isBlank()
                || !exactTerms.isEmpty()
                || !descriptiveTerms.isEmpty()
                || !brand.isBlank();
    }

    /**
     * 判断搜索意图是否本身就是配件。
     * 如用户搜索"手机壳"，关键词/品类含"手机壳" → 意图是配件 → 不应过滤配件商品。
     */
    public boolean targetsAccessory() {
        for (String keyword : keywords) {
            if (SearchTextUtils.isAccessoryTerm(keyword)) return true;
        }
        return SearchTextUtils.isAccessoryTerm(category);
    }

    public List<String> queryTerms() {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.addAll(exactTerms);
        terms.addAll(keywords);
        if (!category.isBlank()) {
            terms.add(category);
        }
        if (!coreProduct.isBlank()) {
            terms.add(coreProduct);
        }
        return List.copyOf(terms);
    }

    private static void add(LinkedHashSet<String> values, String value) {
        String useful = SearchTextUtils.useful(value);
        if (!useful.isBlank() && !isGeneric(useful)) {
            values.add(useful);
        }
    }

    private static void addSpecific(LinkedHashSet<String> values, String value) {
        String useful = SearchTextUtils.useful(value);
        if (isSpecific(useful)) {
            values.add(useful);
        }
    }

    private static void addSplit(LinkedHashSet<String> values, String value) {
        SearchTextUtils.splitSearchTerms(value).forEach(term -> add(values, term));
    }

    private static void addModelTokens(LinkedHashSet<String> values, String value) {
        var matcher = MODEL_TOKEN.matcher(StringUtils.defaultString(value));
        while (matcher.find()) {
            addSpecific(values, matcher.group());
        }
    }

    private static boolean isSpecific(String value) {
        String useful = SearchTextUtils.useful(value);
        if (useful.isBlank() || isGeneric(useful)) {
            return false;
        }
        String compact = useful.replaceAll("\\s+", "");
        if (compact.length() < 2) {
            return false;
        }
        if (GENERIC_CJK_TERMS.contains(compact)) {
            return false;
        }
        return compact.matches(".*[A-Za-z0-9].*") || compact.length() >= 2;
    }

    private static boolean isGeneric(String value) {
        String compact = SearchTextUtils.useful(value).replaceAll("\\s+", "");
        return compact.isBlank() || GENERIC_TERMS.contains(compact);
    }

    private static List<String> limit(LinkedHashSet<String> values, int max) {
        return values.stream().limit(max).toList();
    }

    private static String join(String... parts) {
        List<String> useful = new ArrayList<>();
        for (String part : parts) {
            String value = SearchTextUtils.useful(part);
            if (!value.isBlank()) {
                useful.add(value);
            }
        }
        return String.join(" ", useful);
    }
}
