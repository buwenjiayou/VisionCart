package com.visioncart.service.search.strategy;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 手机壳垂直搜索策略。
 *
 * <p>核心规则：
 * <ul>
 *   <li>手机壳是 ACCESSORY_MAIN，品牌是适配品牌（iQOO、华为等）</li>
 *   <li>金属环、引磁片、指环扣是 RELATED_ACCESSORY，限量出现</li>
 *   <li>手机膜不能混入手机壳结果</li>
 *   <li>手机（主商品）必须 REJECT</li>
 *   <li>Top20 中手机壳至少 16 个，金属环最多 2 个</li>
 * </ul>
 */
@Component
public class PhoneCaseStrategy extends DefaultProductIntentStrategy {
    private static final Logger log = LoggerFactory.getLogger(PhoneCaseStrategy.class);

    /** 手机壳相关主品词 */
    private static final List<String> CASE_PRIMARY = List.of(
            "手机壳", "手机套", "保护壳", "保护套", "手机保护壳", "手机保护套", "case");

    /** 手机膜相关词（不能混入手机壳结果） */
    private static final List<String> FILM_TERMS = List.of(
            "手机膜", "钢化膜", "贴膜", "手机贴膜", "屏幕保护膜", "screen protector");

    /** 手机主品词（搜手机壳时必须 REJECT） */
    private static final List<String> PHONE_TERMS = List.of(
            "手机", "智能手机", "phone", "iPhone");

    /** RELATED_ACCESSORY 词（限量出现） */
    private static final List<String> ACCESSORY_PARTS = List.of(
            "金属环", "磁吸环", "引磁片", "磁吸片", "指环扣", "贴片", "挂绳", "手机绳",
            "MagSafe环", "磁吸金属环");

    /** 磁吸/MagSafe 手机壳同义词。 */
    private static final List<String> MAGNETIC_TERMS = List.of(
            "磁吸", "MagSafe", "magsafe", "磁力", "磁吸式");

    /** RELATED_ACCESSORY 在 Top20 中最多占比 */
    private static final double ACCESSORY_RATIO_TOP20 = 0.15;
    /** RELATED_ACCESSORY 在 Top50 中最多数量 */
    private static final int ACCESSORY_MAX_TOP50 = 5;

    public PhoneCaseStrategy(QueryPlanner queryPlanner, IntentGate intentGate) {
        super(queryPlanner, intentGate);
    }

    @Override
    public boolean supports(ProductIntent intent) {
        return "phone_case".equals(intent.productFamily());
    }

    @Override
    public QueryPlan buildQueryPlan(ProductIntent intent, SearchFilter filter) {
        // 使用通用 QueryPlanner，但追加手机壳专属的 forbidden terms
        QueryPlan base = queryPlanner.plan(intent);

        // 手机壳策略额外禁止的独立搜索词
        List<String> extraForbidden = new ArrayList<>(base.forbiddenStandaloneTerms());
        extraForbidden.addAll(FILM_TERMS); // 手机膜不能单独搜索
        extraForbidden.addAll(PHONE_TERMS); // 手机不能单独搜索

        return new QueryPlan(
                base.primaryQueries(),
                base.secondaryQueries(),
                base.fallbackQueries(),
                extraForbidden.stream().distinct().toList(),
                base.debug()
        );
    }

    @Override
    public MixPolicy policy(ProductIntent intent) {
        return MixPolicy.strictAccessory();
    }

    @Override
    public IntentGate.IntentTier classify(ProductIntent intent, ProductCard product) {
        if (product == null || product.title() == null || product.title().isBlank()) {
            return IntentGate.IntentTier.REJECT;
        }

        String title = SearchTextUtils.normalizeForMatch(product.title());
        String brand = intent.bestBrand();
        boolean wantsMagnetic = wantsMagnetic(intent);

        // 1. 手机主品 → REJECT（搜手机壳不要手机）
        for (String phone : PHONE_TERMS) {
            if (containsNormalized(title, phone) && !containsCaseTerm(title)) {
                return IntentGate.IntentTier.REJECT;
            }
        }

        // 2. 手机膜 → REJECT（搜手机壳不要手机膜）
        for (String film : FILM_TERMS) {
            if (containsNormalized(title, film)) {
                return IntentGate.IntentTier.REJECT;
            }
        }

        // 3. 手机壳/保护壳 → 主品匹配
        boolean hasCaseTerm = containsCaseTerm(title);
        if (hasCaseTerm) {
            if (!brand.isBlank() && intent.hasReliableBrand()) {
                if (BrandMatcher.productMatchesExpectedBrand(product, brand)) {
                    return wantsMagnetic && !hasMagneticTerm(title)
                            ? IntentGate.IntentTier.SAME_FAMILY
                            : IntentGate.IntentTier.EXACT_MAIN;
                }
                if (BrandMatcher.hasConflictingBrand(product, brand)) {
                    return IntentGate.IntentTier.REJECT;
                }
                return IntentGate.IntentTier.SAME_FAMILY;
            }
            if (wantsMagnetic) {
                return hasMagneticTerm(title)
                        ? IntentGate.IntentTier.EXACT_MAIN
                        : IntentGate.IntentTier.SAME_FAMILY;
            }
            return IntentGate.IntentTier.EXACT_MAIN;
        }

        // 4. RELATED_ACCESSORY 词（金属环等）
        for (String part : ACCESSORY_PARTS) {
            if (containsNormalized(title, part)) {
                return IntentGate.IntentTier.RELATED_ACCESSORY;
            }
        }

        // 5. 其他 → REJECT
        return IntentGate.IntentTier.REJECT;
    }

    @Override
    public List<ProductCard> mix(ProductIntent intent, List<ClassifiedProduct> classified, int pageSize) {
        if (classified != null) {
            List<ProductCard> result = ResultMixer.mix(classified, policy(intent), pageSize);
            log.info("PhoneCaseStrategy mix: {} classified -> {} total", classified.size(), result.size());
            return result;
        }
        Map<IntentGate.IntentTier, List<ProductCard>> byTier = classified.stream()
                .collect(Collectors.groupingBy(
                        ClassifiedProduct::tier,
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(ClassifiedProduct::product, Collectors.toList())));

        List<ProductCard> exactMain = byTier.getOrDefault(IntentGate.IntentTier.EXACT_MAIN, List.of());
        List<ProductCard> compatibleMain = byTier.getOrDefault(IntentGate.IntentTier.COMPATIBLE_MAIN, List.of());
        List<ProductCard> sameFamily = byTier.getOrDefault(IntentGate.IntentTier.SAME_FAMILY, List.of());
        List<ProductCard> relatedAccessory = byTier.getOrDefault(IntentGate.IntentTier.RELATED_ACCESSORY, List.of());

        // 强信号层
        List<ProductCard> strong = new ArrayList<>();
        strong.addAll(exactMain);
        strong.addAll(compatibleMain);
        strong.addAll(sameFamily);

        // RELATED_ACCESSORY 限量：Top20 最多 3 个，Top50 最多 5 个
        int accessoryCap = Math.min(ACCESSORY_MAX_TOP50,
                Math.max(3, (int) (pageSize * ACCESSORY_RATIO_TOP20)));

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

        log.info("PhoneCaseStrategy mix: {} exact + {} compatible + {} family + {} accessory = {} total",
                exactMain.size(), compatibleMain.size(), sameFamily.size(),
                result.size() - strong.size(), result.size());
        return result;
    }

    private boolean containsCaseTerm(String normalizedTitle) {
        for (String term : CASE_PRIMARY) {
            if (SearchTextUtils.containsNormalized(normalizedTitle, term)) {
                return true;
            }
        }
        return false;
    }

    private boolean wantsMagnetic(ProductIntent intent) {
        if (intent == null) return false;
        return intent.featureTerms().stream().anyMatch(this::isMagneticTerm)
                || intent.softAttributes().values().stream().anyMatch(this::isMagneticTerm)
                || intent.hardAttributes().values().stream().anyMatch(this::isMagneticTerm);
    }

    private boolean hasMagneticTerm(String normalizedTitle) {
        return MAGNETIC_TERMS.stream().anyMatch(term -> containsNormalized(normalizedTitle, term));
    }

    private boolean isMagneticTerm(String value) {
        return MAGNETIC_TERMS.stream().anyMatch(term -> SearchTextUtils.containsNormalized(value, term));
    }

    private boolean containsNormalized(String text, String token) {
        return SearchTextUtils.containsNormalized(text, token);
    }
}
