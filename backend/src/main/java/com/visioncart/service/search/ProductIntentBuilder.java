package com.visioncart.service.search;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 从识别结果构建 ProductIntent。
 *
 * <p>核心规则：
 * <ul>
 *   <li>主商品优先级：用户修正 > category.level3 > attributes.商品主体 > 最长主品词 > category.level2</li>
 *   <li>relatedOnly 词不能当主搜索词</li>
 *   <li>feature 词可以和主商品组合，但不能单独搜索</li>
 *   <li>配件场景品牌是适配品牌，主商品场景品牌是制造商品牌</li>
 * </ul>
 */
@Component
public class ProductIntentBuilder {
    private static final Logger log = LoggerFactory.getLogger(ProductIntentBuilder.class);

    private final ProductTaxonomyRegistry taxonomy;
    private final BrandRelationResolver brandResolver;

    @Autowired
    public ProductIntentBuilder(ProductTaxonomyRegistry taxonomy) {
        this(taxonomy, new BrandRelationResolver());
    }

    public ProductIntentBuilder(ProductTaxonomyRegistry taxonomy, BrandRelationResolver brandResolver) {
        this.taxonomy = taxonomy;
        this.brandResolver = brandResolver;
    }

    /**
     * 从扁平 attributes 构建 ProductIntent。
     *
     * @param attributes 识别结果属性（来自 RecognitionSearchMapper 或属性修正）
     * @param sessionId  会话 ID
     * @return 结构化商品意图
     */
    public ProductIntent build(Map<String, String> attributes, String sessionId) {
        Map<String, String> attrs = attributes == null ? Map.of() : attributes;

        // 1. 提取原始字段
        String category = useful(attrs.get(SearchTextUtils.ATTR_CATEGORY));
        String keyword = useful(attrs.get(SearchTextUtils.ATTR_KEYWORD));
        String keywordsStr = useful(attrs.get(SearchTextUtils.ATTR_KEYWORDS));
        String brand = useful(attrs.get(SearchTextUtils.ATTR_BRAND));
        boolean brandReliable = "true".equals(attrs.get(SearchTextUtils.ATTR_BRAND_RELIABLE));
        String coreProductAttr = useful(attrs.get("商品主体"));
        String model = useful(attrs.get("型号"));
        String color = useful(attrs.get("颜色"));
        String material = useful(attrs.get("材质"));
        String style = useful(attrs.get("款式"));
        String type = useful(attrs.get("类型"));

        List<String> keywords = new ArrayList<>(SearchTextUtils.splitSearchTerms(keywordsStr));
        // Problem 8 fix: merge non-empty keyword into keywords list if not already present
        String mainKeyword = keyword;
        if (!mainKeyword.isBlank() && keywords.stream().noneMatch(k -> k.equalsIgnoreCase(mainKeyword))) {
            keywords.add(0, mainKeyword);
        }
        if (keyword.isBlank() && !keywords.isEmpty()) {
            keyword = keywords.get(0);
        }

        boolean phoneCaseDescriptor = looksLikePhoneCaseDescriptor(
                category, coreProductAttr, keyword, keywords, style, type);

        // 2. 解析品类
        ProductTaxonomyRegistry.TaxonomyEntry taxonomyEntry = taxonomy.resolve(category, keywords);
        if (phoneCaseDescriptor) {
            taxonomyEntry = taxonomy.resolve("手机壳", List.of("手机壳"));
        }

        // 3. 确定主商品
        String canonicalProduct = phoneCaseDescriptor
                ? "手机壳"
                : resolveCanonicalProduct(category, coreProductAttr, keyword, keywords, taxonomyEntry);
        ProductIntent.ProductRole productRole = resolveRole(taxonomyEntry, category, keywords);

        // 4. 解析品牌语义
        String productBrand = "";
        String compatibleBrand = "";
        if (!brand.isBlank()) {
            if (productRole == ProductIntent.ProductRole.ACCESSORY_MAIN) {
                // 配件场景：品牌是适配品牌
                compatibleBrand = brand;
            } else {
                // 主商品场景：品牌是制造商品牌
                productBrand = brand;
            }
        }

        // 5. 分类关键词
        BrandResolution brandResolution = brandResolver.resolve(
                brand, productRole, canonicalProduct, keywords, attrs);
        productBrand = brandResolution.productBrand();
        compatibleBrand = brandResolution.compatibleBrand();
        brandReliable = brandResolution.reliable();

        List<String> featureTerms = new ArrayList<>();
        List<String> relatedOnlyTerms = new ArrayList<>();
        List<String> negativeTerms = new ArrayList<>();

        for (String kw : keywords) {
            if (kw.equals(canonicalProduct)) continue;
            collectKeywordTerm(kw, canonicalProduct, featureTerms, relatedOnlyTerms);
        }
        collectFeatureTerms(category, featureTerms);
        collectFeatureTerms(style, featureTerms);
        collectFeatureTerms(type, featureTerms);

        // 6. 硬约束和软约束
        Map<String, String> hardAttributes = new LinkedHashMap<>();
        Map<String, String> softAttributes = new LinkedHashMap<>();

        if (!model.isBlank()) hardAttributes.put("型号", model);
        if (!canonicalProduct.isBlank()) hardAttributes.put("类目", canonicalProduct);

        if (!color.isBlank()) softAttributes.put("颜色", color);
        if (!material.isBlank()) softAttributes.put("材质", material);
        if (!style.isBlank()) softAttributes.put("款式", style);
        if (!type.isBlank()) softAttributes.put("类型", type);

        // 7. 确定品类族
        String productFamily = taxonomyEntry != null ? taxonomyEntry.family() : resolveFamilyFromCategory(category);

        ProductIntent intent = new ProductIntent(
                sessionId,
                canonicalProduct,
                productFamily,
                productRole,
                productBrand,
                compatibleBrand,
                model,
                brandReliable,
                hardAttributes,
                softAttributes,
                unique(featureTerms),
                unique(relatedOnlyTerms),
                negativeTerms,
                brandReliable ? 1.0 : 0.7,
                "recognition"
        );

        log.info("ProductIntent built: canonicalProduct={}, role={}, productBrand={}, compatibleBrand={}, " +
                        "features={}, relatedOnly={}, family={}",
                canonicalProduct, productRole, productBrand, compatibleBrand,
                featureTerms, relatedOnlyTerms, productFamily);

        return intent;
    }

    /**
     * 确定主商品。
     * 优先级：用户修正(商品主体) > category.level3 > 最长主品词 > category.level2
     */
    private String resolveCanonicalProduct(String category, String coreProductAttr,
                                            String keyword, List<String> keywords,
                                            ProductTaxonomyRegistry.TaxonomyEntry entry) {
        // 1. 用户修正的"商品主体"属性优先
        if (!coreProductAttr.isBlank() && taxonomy.isPrimary(coreProductAttr)) {
            return coreProductAttr;
        }

        // 2. 品类注册表的 primary 列表
        if (entry != null && !entry.primary().isEmpty()) {
            // 找最长匹配的 primary
            String best = "";
            for (String p : entry.primary()) {
                if (category.contains(p) || keywords.stream().anyMatch(kw -> kw.contains(p))) {
                    if (p.length() > best.length()) best = p;
                }
            }
            if (!best.isBlank()) return best;
        }

        // 3. 从关键词中找最长主品词
        String bestFromKeywords = "";
        for (String kw : keywords) {
            if (taxonomy.isPrimary(kw) && kw.length() > bestFromKeywords.length()) {
                bestFromKeywords = kw;
            }
        }
        if (!bestFromKeywords.isBlank()) return bestFromKeywords;

        // 4. category 本身如果是主品词
        if (!category.isBlank() && taxonomy.isPrimary(category)) {
            return category;
        }

        // 5. 从 category 中提取主品词
        if (!category.isBlank()) {
            for (String p : taxonomy.allPrimaryTerms()) {
                if (category.contains(p)) return p;
            }
        }

        // 6. fallback
        return category.isBlank() ? keyword : category;
    }

    private ProductIntent.ProductRole resolveRole(ProductTaxonomyRegistry.TaxonomyEntry entry,
                                                    String category, List<String> keywords) {
        if (entry != null) return entry.role();

        // 从品类名推断
        String catLower = category == null ? "" : category;
        if (SearchTextUtils.isAccessoryTerm(catLower)
                || keywords.stream().anyMatch(SearchTextUtils::isAccessoryTerm)) {
            return ProductIntent.ProductRole.ACCESSORY_MAIN;
        }
        return ProductIntent.ProductRole.MAIN_PRODUCT;
    }

    private String resolveFamilyFromCategory(String category) {
        if (category.isBlank()) return "";
        String catLower = category;
        if (catLower.contains("手机壳") || catLower.contains("保护壳") || catLower.contains("保护套")) return "phone_case";
        if (catLower.contains("手机膜") || catLower.contains("钢化膜") || catLower.contains("贴膜")) return "screen_protector";
        if (catLower.contains("手机")) return "phone";
        if (catLower.contains("耳机")) return "earbuds";
        if (catLower.contains("手表") || catLower.contains("手环")) return "watch";
        if (catLower.contains("鞋")) return "shoe";
        if (catLower.contains("包")) return "bag";
        return "";
    }

    /**
     * 判断一个词是否是有意义的特征词（既不是主品词也不是 relatedOnly）。
     */
    private boolean isMeaningfulFeature(String term, String canonicalProduct) {
        if (term.isBlank() || term.length() < 2) return false;
        // 过滤掉太通用的词
        String lower = term.toLowerCase();
        if (List.of("的", "了", "和", "与", "或", "通用", "适用", "适合").contains(lower)) return false;
        // 包含材质/颜色/功能描述的词是有意义的特征
        return term.matches(".*[\\u4e00-\\u9fff].*") || term.matches(".*[a-zA-Z]{2,}.*");
    }

    private void collectKeywordTerm(String term, String canonicalProduct,
                                    List<String> featureTerms,
                                    List<String> relatedOnlyTerms) {
        List<String> knownFeatures = taxonomy.matchingFeatures(term);
        if (taxonomy.isRelatedOnly(term)) {
            featureTerms.addAll(knownFeatures);
            relatedOnlyTerms.add(term);
            return;
        }
        if (!knownFeatures.isEmpty()) {
            featureTerms.addAll(knownFeatures);
            return;
        }
        // 既不是 feature 也不是 relatedOnly 的词，如果是有意义的特征也加入
        if (isMeaningfulFeature(term, canonicalProduct)) {
            featureTerms.add(term);
        }
    }

    private void collectFeatureTerms(String text, List<String> featureTerms) {
        featureTerms.addAll(taxonomy.matchingFeatures(text));
    }

    private List<String> unique(List<String> terms) {
        return terms.stream()
                .map(SearchTextUtils::useful)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    private boolean looksLikePhoneCaseDescriptor(String category, String coreProductAttr, String keyword,
                                                 List<String> keywords, String style, String type) {
        String combined = String.join(" ",
                useful(category),
                useful(coreProductAttr),
                useful(keyword),
                useful(style),
                useful(type),
                keywords == null ? "" : String.join(" ", keywords));
        if (combined.isBlank()) return false;
        if (containsAny(combined, "耳机壳", "耳机套", "AirPods", "平板壳", "iPad壳", "表壳")) {
            return false;
        }
        if (containsAny(combined, "手机壳", "手机套", "手机保护壳", "手机保护套", "MagSafe壳")) {
            return true;
        }
        boolean looksLikeCase = containsAny(combined, "防摔壳", "保护壳", "保护套", "磁吸壳");
        boolean magneticCaseSignal = looksLikeCase && containsAny(combined, "磁吸", "MagSafe", "magsafe");
        return magneticCaseSignal;
    }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (SearchTextUtils.containsNormalized(text, token)) {
                return true;
            }
        }
        return false;
    }

    /** 获取所有品类的 primary 词 */
    private Set<String> allPrimaryTerms() {
        return taxonomy.allPrimaryTerms();
    }

    private static String useful(String value) {
        return SearchTextUtils.useful(value);
    }
}
