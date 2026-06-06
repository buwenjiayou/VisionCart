package com.visioncart.service.suggestion;

import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.filter.ActionCompiler;
import com.visioncart.service.filter.NlpUndoService;
import com.visioncart.service.filter.SafeActionExecutor;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.api.dto.SuggestionExecuteResult;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.service.search.BrandMatcher;
import com.visioncart.service.search.ProductSortService;
import com.visioncart.service.search.SearchTextUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class SuggestionService {
    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
    private static final BigDecimal MAX_SORT_PRICE = BigDecimal.valueOf(Long.MAX_VALUE);

    // NOTE: In-memory sessions. Not shared across instances in a multi-instance deployment.
    // Suggestion state is transient (undo/redo, executed actions) and regenerated on each search,
    // so this is acceptable for the current single-instance deployment model.
    private final long sessionTtlMillis;
    private final int maxSessions;
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    // Unified execution support
    private final SafeActionExecutor safeActionExecutor;
    private final ActionCompiler actionCompiler;
    private final NlpUndoService undoService;
    private final ProductSortService productSortService;

    public SuggestionService(VisionCartProperties properties,
                             SafeActionExecutor safeActionExecutor,
                             ActionCompiler actionCompiler,
                             NlpUndoService undoService,
                             ProductSortService productSortService) {
        VisionCartProperties.Suggestion cfg = properties.getSuggestion();
        this.sessionTtlMillis = Duration.ofMinutes(cfg.getSessionTtlMinutes()).toMillis();
        this.maxSessions = cfg.getMaxSessions();
        this.safeActionExecutor = safeActionExecutor;
        this.actionCompiler = actionCompiler;
        this.undoService = undoService;
        this.productSortService = productSortService;
    }

    private static class SessionData {
        final Deque<SearchFilter> undoStack = new ConcurrentLinkedDeque<>();
        final Deque<String> executedActions = new ConcurrentLinkedDeque<>();
        volatile Instant lastAccessed = Instant.now();

        void touch() {
            lastAccessed = Instant.now();
        }
    }

    public List<SuggestionCard> cards(String clientType, List<ProductCard> products, java.util.Collection<String> excludeActions) {
        return cards(clientType, products, Map.of(), null, excludeActions);
    }

    public List<SuggestionCard> cards(String clientType, List<ProductCard> products) {
        return cards(clientType, products, Map.of(), null, null);
    }

    public List<SuggestionCard> cards(String clientType,
                                      List<ProductCard> products,
                                      Map<String, String> attributes,
                                      SearchFilter currentFilter) {
        return cards(clientType, products, attributes, currentFilter, null);
    }

    public List<SuggestionCard> cards(String clientType,
                                      List<ProductCard> products,
                                      Map<String, String> attributes,
                                      SearchFilter currentFilter,
                                      java.util.Collection<String> excludeActions) {
        int maxCards = "overlay".equalsIgnoreCase(clientType) ? 4 : 6;
        List<ProductCard> safeProducts = safeProducts(products);
        if (safeProducts.isEmpty()) {
            return List.of();
        }

        SearchFilter filter = currentFilter == null ? SearchFilter.empty() : currentFilter;
        List<SuggestionCard> candidates = new ArrayList<>();
        List<ProductCard> priced = safeProducts.stream()
                .filter(product -> priceOf(product).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(this::priceOf))
                .toList();

        ProductCard cheapest = priced.isEmpty() ? null : priced.get(0);
        ProductCard mostExpensive = priced.isEmpty() ? null : priced.get(priced.size() - 1);
        BigDecimal minPrice = cheapest == null ? BigDecimal.ZERO : priceOf(cheapest);
        BigDecimal maxPrice = mostExpensive == null ? BigDecimal.ZERO : priceOf(mostExpensive);
        boolean priceVariance = hasPriceVariance(minPrice, maxPrice);

        if (filter.sortBy() != null) {
            add(candidates, "sort_relevance", "回到综合推荐", "按同款相关度重新排序", "spark",
                    "sort_relevance", 98, "综合", "恢复识别相似度、品牌和标题相关性排序",
                    null, "查看推荐", "neutral");
        }

        if (priceVariance && !sortActive(filter, "price", "asc")) {
            add(candidates, "price_compare", "查看同款低价", "先看价格更低的候选", "money",
                    "sort_by_price_asc", 96, "省钱", "当前结果最高价约为最低价的 "
                            + priceRatioLabel(minPrice, maxPrice), "最低 " + money(minPrice), "低价优先", "saving");
        } else if (safeProducts.size() >= 3 && !sortActive(filter, "price", "asc")) {
            add(candidates, "sort_price", "价格从低到高", "快速找到预算内选择", "money",
                    "sort_by_price_asc", 72, "排序", "按当前到手价升序排列",
                    minPrice.compareTo(BigDecimal.ZERO) > 0 ? "最低 " + money(minPrice) : null, "按价格排", "saving");
        }

        long officialCount = safeProducts.stream().filter(ProductCard::selfOperated).count();
        if (officialCount > 0 && !Boolean.TRUE.equals(filter.selfOperated())) {
            add(candidates, "official_only", "只看官方/自营", "减少非官方店铺干扰", "shield",
                    "filter_self_operated", 94, "正品", "保留官方、旗舰店或自营候选",
                    officialCount + " 件", "只看官方", "trust");
        }

        String brand = useful(firstUseful(attributes, SearchTextUtils.ATTR_BRAND, "brand"));
        if (brand.isBlank()) {
            brand = dominantBrand(safeProducts);
        }
        String brandForFilter = brand;
        if (!brandForFilter.isBlank() && !contains(filter.brands(), brandForFilter)) {
            long brandMatches = safeProducts.stream().filter(product -> matchesBrand(product, brandForFilter)).count();
            if (brandMatches > 0 && brandMatches < safeProducts.size()) {
                add(candidates, "brand_filter", "锁定" + brandForFilter + "品牌", "排除其它品牌结果", "brand",
                        "filter_brand:" + brandForFilter, 92, "品牌", "品牌已识别，先把非目标品牌筛掉",
                        brandMatches + " 件", "筛品牌", "trust");
            }
        }

        long couponCount = safeProducts.stream().filter(this::hasCouponSignal).count();
        if (couponCount > 0) {
            add(candidates, "coupon_first", "优先看优惠", "券后价、满减或促销商品靠前", "coupon",
                    "filter_coupon", 90, "优惠", "优先保留带优惠信息的商品",
                    couponCount + " 件", "看优惠", "saving");
        }

        ProductCard topSales = safeProducts.stream()
                .filter(product -> product.sales() > 0)
                .max(Comparator.comparingLong(ProductCard::sales))
                .orElse(null);
        if (topSales != null && !sortActive(filter, "sales", "desc")) {
            add(candidates, "sales_first", "销量优先", "先看更多人购买的结果", "trending",
                    "sort_by_sales_desc", 86, "热卖", "按销量从高到低排序",
                    salesLabel(topSales.sales()), "按销量排", "popularity");
        }

        long highRatingCount = safeProducts.stream().filter(product -> product.rating() >= 4.7).count();
        if (highRatingCount > 0 && !sortActive(filter, "rating", "desc")) {
            add(candidates, "review_quality", "口碑优先", "综合评分、店铺信誉、销量排序", "star",
                    "sort_by_review_quality", 84, "口碑", "综合商品评分、店铺/卖家信誉、销量和相关性排序",
                    highRatingCount + " 件高分", "口碑排序", "trust");
        }

        cheapestPlatform(safeProducts).ifPresent(entry -> {
            if (!contains(filter.platforms(), entry.getKey())) {
                add(candidates, "platform_best_price", entry.getKey() + "低价更多", "切到当前低价平台",
                        "platform", "filter_platform:" + entry.getKey(), 82, "平台",
                        "当前最低价来自 " + entry.getKey(), "最低 " + money(entry.getValue()),
                        "看该平台", "saving");
            }
        });

        String color = useful(firstUseful(attributes, SearchTextUtils.ATTR_COLOR, "color"));
        if (!color.isBlank() && !contains(filter.colors(), color)) {
            long colorMatches = safeProducts.stream().filter(product -> textMatches(product, color)).count();
            if (colorMatches > 0 && colorMatches < safeProducts.size()) {
                add(candidates, "color_filter", "只看" + color + "款", "匹配实物颜色", "filter",
                        "filter_color:" + color, 80, "颜色", "颜色是同款判断的重要特征",
                        colorMatches + " 件", "筛颜色", "filter");
            }
        }

        String spec = useful(firstUseful(attributes, "尺码", "尺寸", "规格", "型号", "系列", "货号"));
        if (!spec.isBlank()) {
            long specMatches = safeProducts.stream().filter(product -> textMatches(product, spec)).count();
            if (specMatches > 0 && specMatches < safeProducts.size()) {
                add(candidates, "spec_filter", "匹配" + spec, "按型号/尺码/规格精筛", "filter",
                        "filter_keyword:" + spec, 78, "规格", "保留标题或标签命中该特征的商品",
                        specMatches + " 件", "筛规格", "filter");
            }
        }

        budgetLimit(priced).ifPresent(limit -> add(candidates, "budget_under", "预算内优先",
                "保留更接近低价区间的选择", "wallet", "filter_budget_under:" + limit,
                76, "预算", "适合先看低价区间再慢慢放宽",
                "≤ " + money(limit), "按预算筛", "saving"));

        long discountCount = safeProducts.stream().filter(this::hasDiscountSignal).count();
        if (discountCount > 0) {
            add(candidates, "discount_first", "折扣力度优先", "先看有原价对比的商品", "discount",
                    "filter_discount", 74, "折扣", "保留显示原价/现价差的结果",
                    discountCount + " 件", "看折扣", "saving");
        }

        long freeShippingCount = safeProducts.stream().filter(this::hasFreeShippingSignal).count();
        if (freeShippingCount > 0 && freeShippingCount < safeProducts.size()) {
            add(candidates, "free_shipping", "只看包邮", "免运费商品更划算", "truck",
                    "filter_free_shipping", 73, "包邮", "包邮商品可省下运费成本",
                    freeShippingCount + " 件", "只看包邮", "saving");
        }

        if (priced.size() >= 3) {
            ProductCard bestValue = bestValueProduct(priced);
            if (bestValue != null) {
                String bvLabel = String.format(Locale.US, "%.1f分 · %s",
                        bestValue.rating(), salesLabel(bestValue.sales()));
                add(candidates, "best_value", "综合最优选", "价格、评分、销量综合考量", "trophy",
                        "highlight_best_value", 71, "优选", "综合价格、评分和销量的加权评分",
                        bvLabel, "查看最优", "trust");
            }
        }

        if (priced.size() >= 2 && minPrice.compareTo(BigDecimal.ZERO) > 0) {
            addFlow(candidates, "set_price_alert", "设降价提醒", "价格合适时通知你", "bell",
                    "action_set_price_alert", 68, "提醒", "对心仪商品设置降价提醒，不遗漏好价",
                    "目标 " + money(minPrice), "设提醒", "popularity");
        }

        // ===== 价格分析建议 =====
        platformPriceSpread(safeProducts).ifPresent(spread ->
                add(candidates, "platform_price_spread", spread.getKey() + "均价更低",
                        "跨平台比价发现差价", "compare",
                        "filter_platform:" + spread.getKey(), 69, "比价",
                        "当前结果中 " + spread.getKey() + " 平台均价更低，可优先查看",
                        "差价 " + money(spread.getValue()), "看低价平台", "saving"));

        // ===== 风险提示建议 =====
        long lowRatingCount = safeProducts.stream()
                .filter(p -> p.rating() > 0 && p.rating() < 4.0).count();
        if (lowRatingCount > 0 && lowRatingCount >= safeProducts.size() / 3) {
            add(candidates, "low_rating_warning", "注意口碑偏低",
                    "部分商品评分低于 4.0", "warning",
                    "sort_by_review_quality", 66, "风险",
                    "有 " + lowRatingCount + " 件商品评分低于 4.0，建议按口碑排序",
                    lowRatingCount + " 件低分", "口碑排序", "warning");
        }

        long suspectCount = safeProducts.stream()
                .filter(p -> p.similarity() > 0 && p.similarity() < 0.3).count();
        if (suspectCount > 0 && suspectCount >= Math.max(2, safeProducts.size() / 4)) {
            add(candidates, "suspect_accessory", "疑似配件/非同款",
                    "部分结果可能不是你要的商品", "warning",
                    "filter_keyword:[exclude_accessory]", 65, "风险",
                    "有 " + suspectCount + " 件商品相似度较低，可能是配件或非同款",
                    suspectCount + " 件可疑", "排除疑似", "warning");
        }

        return candidates.stream()
                .filter(card -> excludeActions == null || !excludeActions.contains(card.action()))
                .filter(card -> StringUtils.isNotBlank(card.title()) && StringUtils.isNotBlank(card.action()))
                .collect(Collectors.toMap(
                        SuggestionCard::action,
                        card -> card,
                        (left, right) -> left.priority() >= right.priority() ? left : right,
                        LinkedHashMap::new))
                .values()
                .stream()
                .sorted(Comparator.comparingInt(SuggestionCard::priority).reversed())
                .limit(maxCards)
                .toList();
    }

    public SuggestionExecuteResult execute(String sessionId, String action,
                                           List<ProductCard> currentProducts,
                                           SearchFilter currentFilter) {
        List<ProductCard> products = safeProducts(currentProducts);

        SessionData session = getOrCreateSession(sessionId);
        session.touch();
        session.executedActions.add(action);

        LegacyActionResult actionResult = applyAction(action, products, currentFilter);

        // Only save undo point if the filter actually changed (zero-result protection)
        boolean filterChanged = actionResult.filter() != null && !actionResult.filter().equals(currentFilter);
        if (filterChanged && currentFilter != null) {
            session.undoStack.push(currentFilter);
        }

        List<SuggestionCard> newCards = cards("app", actionResult.products(), Map.of(),
                actionResult.filter(), session.executedActions);
        boolean canUndo = !session.undoStack.isEmpty();

        return new SuggestionExecuteResult(actionResult.products(), actionResult.filter(),
                newCards, actionResult.toast(), canUndo);
    }

    /**
     * Execute a suggestion action using the unified SafeActionExecutor pipeline.
     * This compiles the action string into FilterClauses and runs through:
     * FilterClause → FilterPlan → FilterExecutionService → ZeroResultGuard → Undo
     *
     * Use this for actions that need semantic evaluation (keyword matching, capability, etc.)
     * For simple structural actions (sort, price range), the existing execute() is sufficient.
     */
    public SuggestionExecuteResult executeSafe(String sessionId, String action,
                                               List<ProductCard> currentProducts,
                                               SearchFilter currentFilter,
                                               String category) {
        List<ProductCard> products = safeProducts(currentProducts);
        if (actionCompiler.isNoOpAction(action)) {
            return execute(sessionId, action, currentProducts, currentFilter);
        }

        SessionData session = getOrCreateSession(sessionId);
        session.touch();
        session.executedActions.add(action);

        // Compile action to FilterClauses
        List<com.visioncart.service.filter.FilterClause> clauses = actionCompiler.compile(action, currentFilter);
        SearchFilter tentativeFilter = actionCompiler.applyActionToFilter(action, currentFilter);

        if (clauses.isEmpty()) {
            // Fallback to legacy execution
            return execute(sessionId, action, currentProducts, currentFilter);
        }

        // Use SafeActionExecutor for unified execution
        SafeActionExecutor.SafeActionResult result = safeActionExecutor.execute(
                sessionId, products, clauses,
                currentFilter,  // baseFilter (for suggestion, current filter IS the base)
                tentativeFilter,
                currentFilter,
                currentProducts,
                category,
                "suggestion",
                action
        );

        // SafeActionExecutor already saves to NlpUndoService — no local stack needed
        List<SuggestionCard> newCards = cards("app", result.products(), Map.of(),
                result.appliedFilter(), session.executedActions);

        return new SuggestionExecuteResult(
                result.products(),
                result.appliedFilter(),
                newCards,
                result.committed()
                        ? (result.message() != null ? result.message() : "已应用筛选")
                        : (result.message() != null ? result.message() : "未找到符合条件的商品，已保留原结果"),
                result.canUndo(),
                result.committed()
        );
    }

    /**
     * Undo the last suggestion action. Uses NlpUndoService (unified) first,
     * falls back to local stack for legacy execute() path.
     */
    public SuggestionExecuteResult undo(String sessionId, List<ProductCard> currentProducts) {
        // Try unified undo service first (populated by SafeActionExecutor)
        if (undoService != null && undoService.canUndo(sessionId)) {
            NlpUndoService.UndoResult undoResult = undoService.undo(sessionId);
            if (undoResult != null) {
                SessionData session = sessions.get(sessionId);
                if (session != null) {
                    session.executedActions.pollLast();
                }
                List<SuggestionCard> newCards = cards("app", currentProducts, Map.of(),
                        undoResult.filter(), session != null ? session.executedActions : new ArrayDeque<>());
                boolean canUndo = undoService.canUndo(sessionId);
                return new SuggestionExecuteResult(
                        currentProducts, undoResult.filter(), newCards,
                        "已撤销: " + (undoResult.undoneQuery() != null ? undoResult.undoneQuery() : ""),
                        canUndo);
            }
        }

        // Fallback: local undo stack (for legacy execute() path)
        SessionData session = sessions.get(sessionId);
        if (session == null || session.undoStack.isEmpty()) {
            return new SuggestionExecuteResult(currentProducts, null, cards("app", currentProducts), "无操作可撤销", false);
        }

        session.touch();
        SearchFilter previous = session.undoStack.pop();

        // Remove the last executed action (Deque preserves insertion order)
        String last = session.executedActions.pollLast();


        List<SuggestionCard> newCards = cards("app", currentProducts, Map.of(), previous, session.executedActions);
        boolean canUndo = !session.undoStack.isEmpty();

        return new SuggestionExecuteResult(currentProducts, previous, newCards, "已撤销", canUndo);
    }

    @Scheduled(fixedDelay = 300_000)
    void cleanupExpiredSessions() {
        if (sessions.isEmpty()) return;

        Instant cutoff = Instant.now().minusMillis(sessionTtlMillis);
        int beforeSize = sessions.size();

        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccessed.isBefore(cutoff));

        // Max-size cap: remove oldest entries down to maxSessions (Bug #7)
        if (sessions.size() > maxSessions) {
            long toRemove = sessions.size() - maxSessions;
            sessions.entrySet().stream()
                    .sorted((a, b) -> a.getValue().lastAccessed.compareTo(b.getValue().lastAccessed))
                    .limit(toRemove)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(sessions::remove);
        }

        int removed = beforeSize - sessions.size();
        if (removed > 0) {
            log.info("Cleaned up {} expired suggestion sessions, {} remaining", removed, sessions.size());
        }
    }

    private LegacyActionResult applyAction(String action, List<ProductCard> products, SearchFilter currentFilter) {
        if (action == null) {
            return unchanged(products, currentFilter, "已应用智能建议");
        }

        switch (action) {
            case "sort_relevance" -> {
                return new LegacyActionResult(
                        products.stream().sorted(Comparator.comparing(ProductCard::similarity).reversed()).toList(),
                        applySort(currentFilter, null, null),
                        "已恢复综合推荐排序");
            }
            case "sort_by_price_asc" -> {
                List<ProductCard> sorted = products.stream().sorted(priceComparator()).toList();
                BigDecimal min = sorted.stream().map(this::priceOf).filter(price -> price.compareTo(BigDecimal.ZERO) > 0)
                        .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                return new LegacyActionResult(sorted, applySort(currentFilter, "price", "asc"),
                        min.compareTo(BigDecimal.ZERO) > 0 ? "已按价格排序，最低 " + money(min) : "已按价格排序");
            }
            case "sort_by_sales_desc" -> {
                return new LegacyActionResult(
                        products.stream().sorted(Comparator.comparingLong(ProductCard::sales).reversed()).toList(),
                        applySort(currentFilter, "sales", "desc"),
                        "已按销量优先排序");
            }
            case "sort_by_rating_desc", "sort_by_review_quality" -> {
                return new LegacyActionResult(
                        productSortService.sortByKey(products, "review_quality"),
                        applySort(currentFilter, "rating", "desc"),
                        "已按口碑优先排序");
            }
            case "filter_self_operated" -> {
                return filterOrKeep(products, ProductCard::selfOperated, applySelfOperated(currentFilter, true), currentFilter,
                        "已筛选官方/自营商品", "当前没有官方/自营结果，已保留全部商品");
            }
            case "filter_coupon" -> {
                return filterOrKeep(products, this::hasCouponSignal, applyKeyword(currentFilter, "[coupon]"), currentFilter,
                        "已优先保留带优惠信息的商品", "当前没有优惠券/促销信息，已保留全部商品");
            }
            case "filter_discount" -> {
                return filterOrKeep(products, this::hasDiscountSignal, applyKeyword(currentFilter, "[discount]"), currentFilter,
                        "已优先保留有折扣对比的商品", "当前没有明显折扣信息，已保留全部商品");
            }
            case "filter_by_attributes" -> {
                return filterOrKeep(products, this::hasAttributeSignal, applyKeyword(currentFilter, "[has_attributes]"), currentFilter,
                        "已筛出带属性标签的商品", "当前商品缺少可筛选属性，已保留全部商品");
            }
            case "filter_free_shipping" -> {
                return filterOrKeep(products, this::hasFreeShippingSignal, applyKeyword(currentFilter, "[free_shipping]"), currentFilter,
                        "已筛选包邮商品", "当前没有包邮结果，已保留全部商品");
            }
            case "set_price_alert" -> {
                // Price alerts are handled client-side via the favorite flow
                return unchanged(products, currentFilter, "请在商品详情页设置降价提醒");
            }
            case "highlight_best_value" -> {
                BigDecimal maxP = products.stream().map(this::priceOf)
                        .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                        .max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
                BigDecimal finalMaxP = maxP;
                List<ProductCard> sorted = products.stream()
                        .sorted(Comparator.<ProductCard, Double>comparing(p -> valueScore(p, finalMaxP)).reversed())
                        .toList();
                return new LegacyActionResult(sorted, currentFilter, "已按综合价值排序");
            }
            default -> {
                if (action.startsWith("filter_brand:")) {
                    String brand = useful(action.substring("filter_brand:".length()));
                    return filterOrKeep(products, product -> matchesBrand(product, brand), applyBrand(currentFilter, brand), currentFilter,
                            "已筛选 " + brand + " 品牌商品", "当前没有命中该品牌的结果，已保留全部商品");
                }
                if (action.startsWith("filter_color:")) {
                    String color = useful(action.substring("filter_color:".length()));
                    return filterOrKeep(products, product -> textMatches(product, color), applyColor(currentFilter, color), currentFilter,
                            "已筛选 " + color + " 款商品", "当前没有命中该颜色的结果，已保留全部商品");
                }
                if (action.startsWith("filter_platform:")) {
                    String platform = useful(action.substring("filter_platform:".length()));
                    return filterOrKeep(products, product -> StringUtils.equalsIgnoreCase(product.platform(), platform),
                            applyPlatform(currentFilter, platform), currentFilter, "已切换到 " + platform + " 结果",
                            "当前没有该平台结果，已保留全部商品");
                }
                if (action.startsWith("filter_budget_under:")) {
                    BigDecimal limit = parsePrice(action.substring("filter_budget_under:".length()));
                    return filterOrKeep(products, product -> priceOf(product).compareTo(BigDecimal.ZERO) > 0
                                    && priceOf(product).compareTo(limit) <= 0,
                            applyPriceMax(currentFilter, limit), currentFilter, "已筛选 " + money(limit) + " 以内商品",
                            "当前没有预算内结果，已保留全部商品");
                }
                if (action.startsWith("filter_keyword:")) {
                    String keyword = useful(action.substring("filter_keyword:".length()));
                    if ("[exclude_accessory]".equals(keyword)) {
                        return filterOrKeep(products, p -> p.similarity() <= 0 || p.similarity() >= 0.3, currentFilter, currentFilter,
                                "已排除疑似配件/非同款商品", "当前没有疑似配件，已保留全部商品");
                    }
                    return filterOrKeep(products, product -> textMatches(product, keyword), applyKeyword(currentFilter, keyword), currentFilter,
                            "已按 " + keyword + " 精筛", "当前没有命中该特征的结果，已保留全部商品");
                }
                if (action.startsWith("filter_price_band:")) {
                    String[] parts = action.substring("filter_price_band:".length()).split(":");
                    if (parts.length == 2) {
                        BigDecimal min = parsePrice(parts[0]);
                        BigDecimal max = parsePrice(parts[1]);
                        SearchFilter updated = applyPriceRange(currentFilter, min, max);
                        return filterOrKeep(products,
                                p -> priceOf(p).compareTo(BigDecimal.ZERO) > 0
                                        && priceOf(p).compareTo(min) >= 0
                                        && priceOf(p).compareTo(max) <= 0,
                                updated, currentFilter,
                                "已筛选 " + money(min) + "～" + money(max) + " 区间",
                                "当前没有该价位区间结果，已保留全部商品");
                    }
                }
                if ("filter_main_product".equals(action)) {
                    Set<String> accessoryRoles = Set.of("accessory", "case", "part", "consumable", "storage");
                    SearchFilter updated = applyExcludeRoles(currentFilter, accessoryRoles);
                    return filterOrKeep(products,
                            p -> !accessoryRoles.contains(p.productRole().toLowerCase(java.util.Locale.ROOT)),
                            updated, currentFilter,
                            "已排除配件，只看主体商品",
                            "当前没有疑似配件，已保留全部商品");
                }
                return unchanged(products, currentFilter, "已应用智能建议");
            }
        }
    }

    private SessionData getOrCreateSession(String sessionId) {
        if (sessionId == null) return new SessionData();
        if (sessions.size() >= maxSessions) {
            cleanupExpiredSessions();
        }
        return sessions.computeIfAbsent(sessionId, k -> new SessionData());
    }

    private void add(List<SuggestionCard> cards,
                     String id,
                     String title,
                     String subtitle,
                     String icon,
                     String action,
                     int priority,
                     String badge,
                     String reason,
                     String metric,
                     String actionLabel,
                     String tone) {
        cards.add(new SuggestionCard(id, title, subtitle, icon, action, priority,
                badge, reason, metric, actionLabel, tone));
    }

    /** Add a FLOW_ACTION card — client should open a UI flow, not call execute API. */
    private void addFlow(List<SuggestionCard> cards,
                         String id,
                         String title,
                         String subtitle,
                         String icon,
                         String action,
                         int priority,
                         String badge,
                         String reason,
                         String metric,
                         String actionLabel,
                         String tone) {
        cards.add(new SuggestionCard(id, title, subtitle, icon, action, priority,
                badge, reason, metric, actionLabel, tone, "FLOW_ACTION"));
    }

    private LegacyActionResult unchanged(List<ProductCard> products, SearchFilter currentFilter, String toast) {
        return new LegacyActionResult(products, currentFilter, toast);
    }

    private LegacyActionResult filterOrKeep(List<ProductCard> products,
                                      Predicate<ProductCard> predicate,
                                      SearchFilter updatedFilter,
                                      SearchFilter previousFilter,
                                      String successToast,
                                      String emptyToast) {
        List<ProductCard> filtered = products.stream().filter(predicate).toList();
        if (filtered.isEmpty()) {
            // Zero-result protection: keep previous filter state, don't commit the new one
            return new LegacyActionResult(products, previousFilter, emptyToast);
        }
        return new LegacyActionResult(filtered, updatedFilter, successToast);
    }

    private List<ProductCard> safeProducts(List<ProductCard> products) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        return products.stream().filter(Objects::nonNull).toList();
    }

    private boolean hasPriceVariance(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice.compareTo(BigDecimal.ZERO) <= 0 || maxPrice.compareTo(minPrice) <= 0) {
            return false;
        }
        BigDecimal ratio = maxPrice.divide(minPrice, 2, RoundingMode.HALF_UP);
        BigDecimal diff = maxPrice.subtract(minPrice);
        return ratio.compareTo(BigDecimal.valueOf(1.15)) >= 0 || diff.compareTo(BigDecimal.valueOf(30)) >= 0;
    }

    private String priceRatioLabel(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return "";
        }
        return maxPrice.divide(minPrice, 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " 倍";
    }

    private java.util.Optional<Map.Entry<String, BigDecimal>> cheapestPlatform(List<ProductCard> products) {
        Map<String, BigDecimal> minByPlatform = products.stream()
                .filter(product -> StringUtils.isNotBlank(product.platform()))
                .filter(product -> priceOf(product).compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toMap(
                        ProductCard::platform,
                        this::priceOf,
                        BigDecimal::min,
                        LinkedHashMap::new));
        if (minByPlatform.size() < 2) {
            return java.util.Optional.empty();
        }
        return minByPlatform.entrySet().stream().min(Map.Entry.comparingByValue());
    }

    /**
     * 计算平台间均价差异：返回均价最低的平台和它与整体均价的差值。
     * 仅在至少 2 个平台、且最低均价明显低于整体均价时返回。
     */
    private java.util.Optional<Map.Entry<String, BigDecimal>> platformPriceSpread(List<ProductCard> products) {
        Map<String, List<ProductCard>> byPlatform = products.stream()
                .filter(p -> StringUtils.isNotBlank(p.platform()) && priceOf(p).compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.groupingBy(ProductCard::platform, LinkedHashMap::new, Collectors.toList()));
        if (byPlatform.size() < 2) {
            return java.util.Optional.empty();
        }
        // 各平台均价
        Map<String, BigDecimal> avgByPlatform = new LinkedHashMap<>();
        byPlatform.forEach((platform, list) -> {
            BigDecimal sum = list.stream().map(this::priceOf).reduce(BigDecimal.ZERO, BigDecimal::add);
            avgByPlatform.put(platform, sum.divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.HALF_UP));
        });
        // 整体均价
        BigDecimal overallAvg = products.stream().map(this::priceOf)
                .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(1, products.size())), 2, RoundingMode.HALF_UP);
        // 找均价最低的平台
        Map.Entry<String, BigDecimal> cheapest = avgByPlatform.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .orElse(null);
        if (cheapest == null || overallAvg.compareTo(BigDecimal.ZERO) <= 0) {
            return java.util.Optional.empty();
        }
        BigDecimal diff = overallAvg.subtract(cheapest.getValue());
        // 差价 >= 10 元或 >= 15% 才提示
        if (diff.compareTo(BigDecimal.valueOf(10)) >= 0
                || (overallAvg.compareTo(BigDecimal.ZERO) > 0
                    && diff.divide(overallAvg, 2, RoundingMode.HALF_UP).compareTo(BigDecimal.valueOf(0.15)) >= 0)) {
            return java.util.Optional.of(Map.entry(cheapest.getKey(), diff));
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<BigDecimal> budgetLimit(List<ProductCard> priced) {
        if (priced.size() < 4) {
            return java.util.Optional.empty();
        }
        BigDecimal min = priceOf(priced.get(0));
        if (min.compareTo(BigDecimal.ZERO) <= 0) {
            return java.util.Optional.empty();
        }
        BigDecimal limit = min.multiply(BigDecimal.valueOf(1.25)).setScale(0, RoundingMode.CEILING);
        long kept = priced.stream().filter(product -> priceOf(product).compareTo(limit) <= 0).count();
        if (kept < 2 || kept >= priced.size()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(limit);
    }

    private Comparator<ProductCard> priceComparator() {
        return Comparator.comparing(product -> {
            BigDecimal price = priceOf(product);
            return price.compareTo(BigDecimal.ZERO) > 0 ? price : MAX_SORT_PRICE;
        });
    }

    private BigDecimal priceOf(ProductCard product) {
        return product == null || product.price() == null ? BigDecimal.ZERO : product.price();
    }

    private BigDecimal parsePrice(String value) {
        try {
            BigDecimal parsed = new BigDecimal(value);
            return parsed.compareTo(BigDecimal.ZERO) > 0 ? parsed : BigDecimal.ZERO;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private String money(BigDecimal value) {
        if (value == null) {
            return "¥0";
        }
        return "¥" + value.setScale(value.scale() > 0 ? 2 : 0, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String salesLabel(long sales) {
        if (sales >= 10_000) {
            return String.format(Locale.US, "%.1f万销量", sales / 10_000.0);
        }
        if (sales >= 1_000) {
            return String.format(Locale.US, "%.1fk销量", sales / 1_000.0);
        }
        return sales + " 销量";
    }

    private boolean sortActive(SearchFilter filter, String sortBy, String sortOrder) {
        if (filter == null) {
            return sortBy == null;
        }
        if (sortBy == null) {
            return filter.sortBy() == null;
        }
        return sortBy.equals(filter.sortBy())
                && (sortOrder == null || sortOrder.equalsIgnoreCase(StringUtils.defaultString(filter.sortOrder())));
    }

    private boolean contains(List<String> values, String expected) {
        String useful = useful(expected);
        if (values == null || useful.isBlank()) {
            return false;
        }
        return values.stream().map(this::useful).anyMatch(useful::equalsIgnoreCase);
    }

    private String dominantBrand(List<ProductCard> products) {
        return products.stream()
                .map(product -> firstNonBlank(product.brand(), BrandMatcher.inferBrand(product.title(), product.shopName())))
                .map(this::useful)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()))
                .entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private boolean matchesBrand(ProductCard product, String brand) {
        return StringUtils.isNotBlank(brand) && (BrandMatcher.productMatchesExpectedBrand(product, brand) || textMatches(product, brand));
    }

    private boolean textMatches(ProductCard product, String expected) {
        String useful = useful(expected);
        if (useful.isBlank() || product == null) {
            return false;
        }
        String haystack = useful(String.join(" ",
                StringUtils.defaultString(product.title()),
                StringUtils.defaultString(product.shopName()),
                StringUtils.defaultString(product.brand()),
                product.tags() == null ? "" : String.join(" ", product.tags())));
        return haystack.toLowerCase(Locale.ROOT).contains(useful.toLowerCase(Locale.ROOT));
    }

    private boolean hasCouponSignal(ProductCard product) {
        String text = searchable(product).toLowerCase(Locale.ROOT);
        return text.contains("券") || text.contains("优惠") || text.contains("coupon")
                || text.contains("满减") || text.contains("促销");
    }

    private boolean hasDiscountSignal(ProductCard product) {
        if (product == null || product.originalPrice() == null) {
            return false;
        }
        return product.originalPrice().compareTo(priceOf(product)) > 0;
    }

    private boolean hasAttributeSignal(ProductCard product) {
        return product != null && ((product.tags() != null && !product.tags().isEmpty())
                || StringUtils.isNotBlank(product.brand()));
    }

    private boolean hasFreeShippingSignal(ProductCard product) {
        if (product == null || product.tags() == null) return false;
        return product.tags().stream()
                .anyMatch(tag -> tag.contains("包邮") || tag.contains("免运费") || tag.contains("free shipping"));
    }

    private ProductCard bestValueProduct(List<ProductCard> priced) {
        if (priced.isEmpty()) return null;
        BigDecimal maxPrice = priceOf(priced.get(priced.size() - 1));
        if (maxPrice.compareTo(BigDecimal.ZERO) <= 0) return null;
        return priced.stream()
                .filter(p -> priceOf(p).compareTo(BigDecimal.ZERO) > 0)
                .max(Comparator.comparingDouble(p -> valueScore(p, maxPrice)))
                .orElse(null);
    }

    private double valueScore(ProductCard product, BigDecimal maxPrice) {
        double priceScore = 1.0 - (priceOf(product).doubleValue() / maxPrice.doubleValue());
        double ratingScore = product.rating() / 5.0;
        double salesScore = Math.min(product.sales() / 10000.0, 1.0);
        return priceScore * 0.4 + ratingScore * 0.35 + salesScore * 0.25;
    }

    private String searchable(ProductCard product) {
        if (product == null) {
            return "";
        }
        return String.join(" ",
                StringUtils.defaultString(product.title()),
                StringUtils.defaultString(product.shopName()),
                StringUtils.defaultString(product.brand()),
                product.tags() == null ? "" : String.join(" ", product.tags()));
    }

    private String firstUseful(Map<String, String> attributes, String... keys) {
        if (attributes == null || attributes.isEmpty()) {
            return "";
        }
        for (String key : keys) {
            String useful = useful(attributes.get(key));
            if (StringUtils.isNotBlank(useful)) {
                return useful;
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String useful(String value) {
        return SearchTextUtils.useful(value);
    }

    // --- Filter helpers ---

    private SearchFilter applySort(SearchFilter filter, String sortBy, String sortOrder) {
        SearchFilter base = filter == null ? SearchFilter.empty() : filter;
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                sortBy, sortBy == null ? "desc" : StringUtils.defaultIfBlank(sortOrder, "desc"), base.keyword(),
                base.attributes() == null ? Map.of() : base.attributes(), safeList(base.excludeRoles()),
                base.capabilities() == null ? Map.of() : base.capabilities());
    }

    private SearchFilter applySelfOperated(SearchFilter filter, boolean selfOperated) {
        SearchFilter base = filter == null ? SearchFilter.empty() : filter;
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), selfOperated,
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? Map.of() : base.attributes(), safeList(base.excludeRoles()),
                base.capabilities() == null ? Map.of() : base.capabilities());
    }

    private SearchFilter applyBrand(SearchFilter filter, String brand) {
        SearchFilter base = filter == null ? SearchFilter.empty() : filter;
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), appendUnique(base.brands(), brand), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? Map.of() : base.attributes(), safeList(base.excludeRoles()),
                base.capabilities() == null ? Map.of() : base.capabilities());
    }

    private SearchFilter applyColor(SearchFilter filter, String color) {
        SearchFilter base = filter == null ? SearchFilter.empty() : filter;
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), base.selfOperated(),
                appendUnique(base.colors(), color), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? Map.of() : base.attributes(), safeList(base.excludeRoles()),
                base.capabilities() == null ? Map.of() : base.capabilities());
    }

    private SearchFilter applyPlatform(SearchFilter filter, String platform) {
        SearchFilter base = filter == null ? SearchFilter.empty() : filter;
        return new SearchFilter(base.priceRange(), appendUnique(base.platforms(), platform), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? Map.of() : base.attributes(), safeList(base.excludeRoles()),
                base.capabilities() == null ? Map.of() : base.capabilities());
    }

    private SearchFilter applyPriceMax(SearchFilter filter, BigDecimal max) {
        SearchFilter base = filter == null ? SearchFilter.empty() : filter;
        PriceRange current = base.priceRange() == null ? new PriceRange(null, null) : base.priceRange();
        return new SearchFilter(new PriceRange(current.min(), max.doubleValue()), safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? Map.of() : base.attributes(), safeList(base.excludeRoles()),
                base.capabilities() == null ? Map.of() : base.capabilities());
    }

    private SearchFilter applyPriceRange(SearchFilter filter, BigDecimal min, BigDecimal max) {
        SearchFilter base = filter == null ? SearchFilter.empty() : filter;
        return new SearchFilter(new PriceRange(min.doubleValue(), max.doubleValue()), safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? Map.of() : base.attributes(), safeList(base.excludeRoles()),
                base.capabilities() == null ? Map.of() : base.capabilities());
    }

    private SearchFilter applyKeyword(SearchFilter filter, String keyword) {
        SearchFilter base = filter == null ? SearchFilter.empty() : filter;
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), keyword,
                base.attributes() == null ? Map.of() : base.attributes(), safeList(base.excludeRoles()),
                base.capabilities() == null ? Map.of() : base.capabilities());
    }

    private SearchFilter applyExcludeRoles(SearchFilter filter, Set<String> roles) {
        SearchFilter base = filter == null ? SearchFilter.empty() : filter;
        List<String> merged = new ArrayList<>(safeList(base.excludeRoles()));
        for (String role : roles) {
            if (merged.stream().noneMatch(r -> r.equalsIgnoreCase(role))) {
                merged.add(role);
            }
        }
        return new SearchFilter(base.priceRange(), safeList(base.platforms()), base.selfOperated(),
                safeList(base.colors()), safeList(base.brands()), base.ratingMin(),
                base.sortBy(), base.sortOrder(), base.keyword(),
                base.attributes() == null ? Map.of() : base.attributes(), merged,
                base.capabilities() == null ? Map.of() : base.capabilities());
    }

    private List<String> appendUnique(List<String> values, String value) {
        String useful = useful(value);
        if (useful.isBlank()) {
            return safeList(values);
        }
        List<String> result = new ArrayList<>(safeList(values));
        if (result.stream().noneMatch(existing -> useful(existing).equalsIgnoreCase(useful))) {
            result.add(value);
        }
        return result;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private record LegacyActionResult(List<ProductCard> products, SearchFilter filter, String toast) {
    }
}
