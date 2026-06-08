package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 意图门控：对搜索结果进行意图层级分类。
 * 门控优先于排序——REJECT 永远不能靠高销量翻身。
 */
@Component
public class IntentGate {
    private static final Logger log = LoggerFactory.getLogger(IntentGate.class);

    private final ProductTaxonomyRegistry taxonomy;
    private final RelevanceRanker ranker;

    public IntentGate(ProductTaxonomyRegistry taxonomy, RelevanceRanker ranker) {
        this.taxonomy = taxonomy;
        this.ranker = ranker;
    }

    /**
     * 意图层级。
     * EXACT_MAIN > COMPATIBLE_MAIN > SAME_FAMILY > RELATED_ACCESSORY > SUBSTITUTE > REJECT
     */
    public enum IntentTier {
        /** 主商品强匹配：标题含主商品词 + 适配品牌/型号匹配 */
        EXACT_MAIN,
        /** 配件主品且适配品牌/型号匹配 */
        COMPATIBLE_MAIN,
        /** 同品类可接受 */
        SAME_FAMILY,
        /** 相关配件（只能少量出现） */
        RELATED_ACCESSORY,
        /** 替代品（低比例） */
        SUBSTITUTE,
        /** 拒绝 */
        REJECT
    }

    /**
     * 对单个商品进行意图分类。
     */
    public IntentTier classify(ProductIntent intent, ProductCard product) {
        if (product == null || product.title() == null || product.title().isBlank()) {
            return IntentTier.REJECT;
        }

        String title = product.title().toLowerCase(Locale.ROOT);
        String canonicalProduct = intent.canonicalProduct();
        String brand = intent.bestBrand();

        // 1. 品类族不匹配 → REJECT
        if (!matchesProductFamily(intent, product)) {
            return IntentTier.REJECT;
        }

        // 2. 配件主品意图
        if (intent.isAccessoryMain()) {
            return classifyAccessoryMain(intent, product, title, canonicalProduct, brand);
        }

        // 3. 主商品意图
        if (intent.productRole() == ProductIntent.ProductRole.MAIN_PRODUCT) {
            return classifyMainProduct(intent, product, title, canonicalProduct, brand);
        }

        // 4. 其他角色（PART, CONSUMABLE 等）
        return classifyOther(intent, product, title, canonicalProduct, brand);
    }

    private IntentTier classifyAccessoryMain(ProductIntent intent, ProductCard product,
                                              String title, String canonicalProduct, String brand) {
        boolean hasProductInTitle = containsNormalized(title, canonicalProduct);

        // 标题含主商品词
        if (hasProductInTitle) {
            // 适配品牌匹配 → EXACT_MAIN
            if (!brand.isBlank() && matchesBrand(product, brand)) {
                return IntentTier.EXACT_MAIN;
            }
            // 无可靠品牌要求 → EXACT_MAIN
            if (!intent.hasReliableBrand()) {
                return IntentTier.EXACT_MAIN;
            }
            // 有可靠品牌但不匹配 → RELATED_ACCESSORY（降级，不直接拒绝）
            return IntentTier.RELATED_ACCESSORY;
        }

        // 标题不含主商品词，但含 relatedOnly 词
        for (String related : intent.relatedOnlyTerms()) {
            if (containsNormalized(title, related)) {
                return IntentTier.RELATED_ACCESSORY;
            }
        }

        // 标题含父品类词但无配件后缀 → 是主品而非配件（如搜手机壳，标题含"手机"但不含"壳"）→ REJECT
        // 标题含父品类词且有配件后缀 → 已在 canonicalProduct 匹配中处理
        return IntentTier.REJECT;
    }

    private IntentTier classifyMainProduct(ProductIntent intent, ProductCard product,
                                            String title, String canonicalProduct, String brand) {
        boolean hasProductInTitle = containsNormalized(title, canonicalProduct);

        // 先检查是否含配件词 → 搜手机不要手机壳
        boolean isAccessory = false;
        for (String accessory : getAccessoryTerms(intent)) {
            if (containsNormalized(title, accessory)) {
                isAccessory = true;
                break;
            }
        }
        if (isAccessory) {
            return IntentTier.REJECT;
        }

        if (hasProductInTitle) {
            return classifyByBrand(intent, product, brand, true);
        }

        // 标题不含主商品词，但含同族品类词（如搜"运动鞋"，标题含"跑步鞋"）
        String family = intent.productFamily();
        if (!family.isBlank() && matchesFamilyText(family, title)) {
            return classifyByBrand(intent, product, brand, true);
        }

        // 标题不含主商品词的连续子串，但包含核心字符（如"无线办公鼠标"含"无线"和"鼠标"）
        // 使用 token 级别匹配作为降级
        if (matchesByTokens(title, canonicalProduct)) {
            return classifyByBrand(intent, product, brand, true);
        }

        // 标题不含主商品词 → 检查品牌匹配
        if (!brand.isBlank() && matchesBrand(product, brand)) {
            return IntentTier.RELATED_ACCESSORY;
        }

        return IntentTier.REJECT;
    }

    private IntentTier classifyOther(ProductIntent intent, ProductCard product,
                                      String title, String canonicalProduct, String brand) {
        if (containsNormalized(title, canonicalProduct)) {
            return IntentTier.EXACT_MAIN;
        }
        return IntentTier.REJECT;
    }

    /**
     * 判断商品是否属于同一产品族。
     */
    private boolean matchesProductFamily(ProductIntent intent, ProductCard product) {
        String family = intent.productFamily();
        if (family.isBlank()) return true; // 无族信息时不过滤

        String title = product.title() == null ? "" : product.title().toLowerCase(Locale.ROOT);
        String canonicalProduct = intent.canonicalProduct();

        // 标题含主商品词
        if (!canonicalProduct.isBlank() && containsNormalized(title, canonicalProduct)) {
            return true;
        }

        // 标题含 relatedOnly 词
        for (String related : intent.relatedOnlyTerms()) {
            if (containsNormalized(title, related)) {
                return true;
            }
        }

        // 标题含 feature 词 + 父品类词
        for (String feature : intent.featureTerms()) {
            if (containsNormalized(title, feature)) {
                for (String parent : getParentPrimary(intent)) {
                    if (containsNormalized(title, parent)) {
                        return true;
                    }
                }
            }
        }

        // 标题含同族品类词（如搜"运动鞋"，标题含"跑步鞋"也应匹配）
        if (matchesFamilyText(family, title)) {
            return true;
        }

        // 品牌映射到同一品类族（如 Apple → phone，搜索"手机"时应匹配 iPhone）
        if (product.brand() != null && !product.brand().isBlank()) {
            String brandFamily = CategoryNormalizer.normalizeFromText(product.brand());
            if (!brandFamily.isBlank() && familyMatchesCode(family, brandFamily)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断标题是否包含同族品类词。
     * 如 family="shoe" 时，标题含"跑步鞋"、"篮球鞋"等也应匹配。
     */
    private boolean matchesFamilyText(String family, String title) {
        java.util.List<String> familyWords = familyTextWords(family);
        for (String word : familyWords) {
            if (containsNormalized(title, word)) {
                return true;
            }
        }
        return false;
    }

    /** 获取品类族的文本关键词 */
    private java.util.List<String> familyTextWords(String family) {
        return switch (family) {
            case "phone" -> java.util.List.of("手机", "智能手机", "phone");
            case "phone_case" -> java.util.List.of("手机壳", "手机套", "保护壳", "保护套", "case");
            case "screen_protector" -> java.util.List.of("手机膜", "钢化膜", "贴膜", "screen protector");
            case "earbuds" -> java.util.List.of("耳机", "蓝牙耳机", "earbuds", "earphone");
            case "earphone_case" -> java.util.List.of("耳机壳", "耳机套", "earphone case");
            case "watch" -> java.util.List.of("手表", "智能手表", "watch");
            case "watch_band" -> java.util.List.of("表带", "手表带", "watch band");
            case "laptop" -> java.util.List.of("笔记本", "笔记本电脑", "laptop");
            case "laptop_bag" -> java.util.List.of("电脑包", "笔记本包", "laptop bag");
            case "shoe" -> java.util.List.of("鞋", "运动鞋", "跑步鞋", "篮球鞋", "登山鞋",
                    "休闲鞋", "板鞋", "靴", "shoe");
            case "bag" -> java.util.List.of("包", "背包", "手提包", "bag");
            case "printer" -> java.util.List.of("\u6253\u5370\u673a", "\u55b7\u58a8\u6253\u5370\u673a", "\u6fc0\u5149\u6253\u5370\u673a", "printer");
            case "printer_ink" -> java.util.List.of("\u58a8\u76d2", "\u7852\u9f13", "toner", "ink cartridge");
            case "water_purifier" -> java.util.List.of("\u51c0\u6c34\u5668", "\u996e\u6c34\u673a", "water purifier");
            case "water_filter" -> java.util.List.of("\u6ee4\u82af", "\u51c0\u6c34\u5668\u6ee4\u82af", "filter cartridge");
            case "cup" -> java.util.List.of("\u676f\u5b50", "\u6c34\u676f", "\u4fdd\u6e29\u676f", "\u5496\u5561\u676f", "cup");
            case "cup_sleeve" -> java.util.List.of("\u676f\u5957", "\u6c34\u676f\u5957", "cup sleeve");
            case "shoe_insole" -> java.util.List.of("\u978b\u57ab", "\u8fd0\u52a8\u978b\u57ab", "insole");
            default -> java.util.List.of();
        };
    }

    /** 判断品类族是否匹配品类 code */
    private boolean familyMatchesCode(String family, String code) {
        return switch (family) {
            case "phone", "phone_case", "screen_protector" -> "phone".equals(code);
            case "earbuds", "earphone_case" -> "headphone".equals(code);
            case "watch", "watch_band" -> "watch".equals(code);
            case "laptop", "laptop_bag" -> "laptop".equals(code);
            case "shoe", "shoe_insole" -> "shoe".equals(code);
            case "printer", "printer_ink" -> "printer".equals(code);
            case "water_purifier", "water_filter" -> "water_purifier".equals(code);
            case "cup", "cup_sleeve" -> "cup".equals(code);
            default -> false;
        };
    }

    private boolean matchesBrand(ProductCard product, String brand) {
        if (brand.isBlank()) return true;
        return BrandMatcher.productMatchesExpectedBrand(product, brand);
    }

    /**
     * 根据品牌匹配情况分类商品。
     *
     * @param exactTitle 标题是否精确包含主商品词（true=连续子串，false=token 级别匹配）
     */
    private IntentTier classifyByBrand(ProductIntent intent, ProductCard product,
                                        String brand, boolean exactTitle) {
        // 品牌匹配 → EXACT_MAIN
        if (!brand.isBlank() && matchesBrand(product, brand)) {
            return IntentTier.EXACT_MAIN;
        }
        // 无品牌要求 → exactTitle ? EXACT_MAIN : SAME_FAMILY
        if (brand.isBlank() || !intent.hasReliableBrand()) {
            return exactTitle ? IntentTier.EXACT_MAIN : IntentTier.SAME_FAMILY;
        }
        // 有可靠品牌但不匹配：
        // - 商品有明确的不同品牌 → REJECT（如搜耐克，商品 brand=Adidas）
        // - 商品无品牌信息 → SAME_FAMILY（标题含主品词但无品牌标注）
        if (hasConflictingBrand(product, brand)) {
            return IntentTier.REJECT;
        }
        return exactTitle ? IntentTier.SAME_FAMILY : IntentTier.RELATED_ACCESSORY;
    }

    /**
     * 判断商品是否有明确的冲突品牌（品牌字段非空且与期望品牌不同）。
     */
    private boolean hasConflictingBrand(ProductCard product, String expectedBrand) {
        return BrandMatcher.hasConflictingBrand(product, expectedBrand);
    }

    private java.util.List<String> getParentPrimary(ProductIntent intent) {
        ProductTaxonomyRegistry.TaxonomyEntry entry = taxonomy.resolve(
                intent.canonicalProduct(), java.util.List.of());
        return entry != null ? entry.parentPrimary() : java.util.List.of();
    }

    private java.util.List<String> getAccessoryTerms(ProductIntent intent) {
        ProductTaxonomyRegistry.TaxonomyEntry entry = taxonomy.resolve(
                intent.canonicalProduct(), java.util.List.of());
        if (entry == null) return java.util.List.of();
        // 获取同族的 accessory 词
        return taxonomy.allRelatedOnlyTerms().stream()
                .filter(term -> term.length() >= 2)
                .toList();
    }

    private boolean containsNormalized(String text, String token) {
        return SearchTextUtils.containsNormalized(text, token);
    }

    /**
     * Token 级别匹配：标题是否包含 canonicalProduct 的核心 token。
     * 用于处理非连续子串的情况，如"无线办公鼠标"包含"无线"和"鼠标"两个 token。
     *
     * <p>拆分策略：
     * <ul>
     *   <li>先按空格/标点拆分</li>
     *   <li>如果没有拆分出多个 token，尝试按常见品类后缀拆分（如"鼠标"、"手机壳"）</li>
     *   <li>标题必须包含所有核心 token</li>
     * </ul>
     */
    private boolean matchesByTokens(String title, String canonicalProduct) {
        if (title.isBlank() || canonicalProduct.isBlank()) return false;
        String normalizedTitle = SearchTextUtils.normalizeForMatch(title);
        String normalizedProduct = SearchTextUtils.normalizeForMatch(canonicalProduct);

        // 先按空格/标点拆分
        java.util.List<String> tokens = java.util.Arrays.stream(canonicalProduct.split("[\\s,，、]+"))
                .map(SearchTextUtils::normalizeForMatch)
                .filter(t -> t.length() >= 2)
                .toList();

        // 如果只有一个 token（如"无线鼠标"），尝试按品类后缀拆分
        if (tokens.size() <= 1 && normalizedProduct.length() >= 4) {
            tokens = splitChineseCompound(normalizedProduct);
        }

        if (tokens.isEmpty()) return false;
        // 标题必须包含所有 token
        return tokens.stream().allMatch(normalizedTitle::contains);
    }

    /**
     * 拆分中文复合词，如"无线鼠标" → ["无线", "鼠标"]。
     * 策略：从右往左找已知品类后缀，找到就拆分。
     */
    private java.util.List<String> splitChineseCompound(String normalized) {
        // 常见品类后缀（从长到短匹配）
        String[] suffixes = {"手机壳", "手机膜", "手机套", "保护壳", "保护套", "钢化膜",
                "运动鞋", "跑步鞋", "篮球鞋", "登山鞋",
                "无线鼠标", "有线鼠标", "蓝牙耳机", "降噪耳机",
                "鼠标", "键盘", "耳机", "手机", "手表", "平板",
                "鞋", "包", "杯", "灯", "扇", "椅"};
        for (String suffix : suffixes) {
            if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                String prefix = normalized.substring(0, normalized.length() - suffix.length());
                if (prefix.length() >= 2) {
                    return java.util.List.of(prefix, suffix);
                }
            }
        }
        // 无法拆分时，返回整个词作为单个 token（会退化为 containsNormalized 行为）
        return java.util.List.of(normalized);
    }

    private boolean hasAccessorySuffix(String title, String parentWord) {
        if (parentWord.isBlank() || !title.contains(parentWord)) return false;
        int idx = title.indexOf(parentWord) + parentWord.length();
        if (idx >= title.length()) return false;
        char next = title.charAt(idx);
        return SearchTextUtils.ACCESSORY_SUFFIX_CHARS.indexOf(next) >= 0;
    }
}
