package com.visioncart.service.suggestion;

import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.api.dto.SuggestionExecuteResult;
import com.visioncart.config.VisionCartProperties;
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
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SuggestionService {
    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);

    // NOTE: In-memory sessions. Not shared across instances in a multi-instance deployment.
    // Suggestion state is transient (undo/redo, executed actions) and regenerated on each search,
    // so this is acceptable for the current single-instance deployment model.
    private final long sessionTtlMillis;
    private final int maxSessions;
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    public SuggestionService(VisionCartProperties properties) {
        VisionCartProperties.Suggestion cfg = properties.getSuggestion();
        this.sessionTtlMillis = Duration.ofMinutes(cfg.getSessionTtlMinutes()).toMillis();
        this.maxSessions = cfg.getMaxSessions();
    }

    private static class SessionData {
        final Deque<SearchFilter> undoStack = new ArrayDeque<>();
        final Set<String> executedActions = ConcurrentHashMap.newKeySet();
        volatile Instant lastAccessed = Instant.now();

        void touch() {
            lastAccessed = Instant.now();
        }
    }

    public List<SuggestionCard> cards(String clientType, List<ProductCard> products, Set<String> excludeActions) {
        int maxCards = "overlay".equalsIgnoreCase(clientType) ? 3 : 5;

        boolean hasOfficial = false;
        boolean hasTags = false;
        double minRating = Double.MAX_VALUE;
        double maxRating = Double.MIN_VALUE;
        BigDecimal minPrice = BigDecimal.valueOf(Double.MAX_VALUE);
        BigDecimal maxPrice = BigDecimal.ZERO;
        int selfOperatedCount = 0;

        for (ProductCard p : products) {
            if (p.selfOperated()) { hasOfficial = true; selfOperatedCount++; }
            if (p.tags() != null && !p.tags().isEmpty()) hasTags = true;
            if (p.rating() < minRating) minRating = p.rating();
            if (p.rating() > maxRating) maxRating = p.rating();
            if (p.price().compareTo(minPrice) < 0) minPrice = p.price();
            if (p.price().compareTo(maxPrice) > 0) maxPrice = p.price();
        }

        boolean ratingGap = products.size() > 1 && (maxRating - minRating) > 0.5;
        double selfOperatedRatio = products.isEmpty() ? 0 : (double) selfOperatedCount / products.size();
        boolean fewSelfOperated = hasOfficial && selfOperatedRatio < 0.3;
        boolean hasPriceVariance = !products.isEmpty() && minPrice.compareTo(BigDecimal.ZERO) > 0
                && maxPrice.divide(minPrice, 2, RoundingMode.HALF_UP).compareTo(BigDecimal.valueOf(1.2)) > 0;

        List<SuggestionCard> candidates = new ArrayList<>();

        if (hasPriceVariance) {
            candidates.add(new SuggestionCard("price_compare", "同款低价", "跨平台比价，最低 ¥" + minPrice, "money", "sort_by_price_asc", 10));
        }
        if (fewSelfOperated) {
            candidates.add(new SuggestionCard("official_only", "官方旗舰店", "正品保障", "shield", "filter_self_operated", 9));
        }
        candidates.add(new SuggestionCard("similar_style", "相似风格", "按相似度推荐，发现更多好物", "palette", "search_similar_style", 7));
        if (ratingGap) {
            candidates.add(new SuggestionCard("high_rating", "高分好评", "筛出 4.8 分以上商品", "star", "filter_rating_4_8", 7));
        }
        if (hasTags) {
            candidates.add(new SuggestionCard("attributes_filter", "筛选属性", "按颜色/品牌/尺码精筛", "filter", "filter_by_attributes", 8));
        }

        if (excludeActions != null) {
            candidates = candidates.stream()
                    .filter(c -> !excludeActions.contains(c.action()))
                    .toList();
        }

        return candidates.stream()
                .filter(c -> c.priority() >= 7)
                .sorted(Comparator.comparingInt(SuggestionCard::priority).reversed())
                .limit(Math.max(2, Math.min(candidates.size(), maxCards)))
                .toList();
    }

    public List<SuggestionCard> cards(String clientType, List<ProductCard> products) {
        return cards(clientType, products, null);
    }

    public SuggestionExecuteResult execute(String sessionId, String action,
                                           List<ProductCard> currentProducts,
                                           SearchFilter currentFilter) {
        List<ProductCard> products = currentProducts == null ? List.of() : currentProducts;

        SessionData session = getOrCreateSession(sessionId);
        session.touch();
        session.executedActions.add(action);

        if (currentFilter != null) {
            session.undoStack.push(currentFilter);
        }

        String toast;
        List<ProductCard> updatedProducts;
        SearchFilter updatedFilter;

        switch (action) {
            case "sort_by_price_asc" -> {
                updatedProducts = products.stream().sorted(Comparator.comparing(ProductCard::price)).toList();
                BigDecimal min = updatedProducts.stream().map(ProductCard::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                toast = "已按价格排序，最低 ¥" + min;
                updatedFilter = applySort(currentFilter, "price", "asc");
            }
            case "filter_self_operated" -> {
                List<ProductCard> filtered = products.stream().filter(ProductCard::selfOperated).toList();
                if (filtered.isEmpty()) {
                    toast = "当前没有官方/自营结果，已保留全部商品";
                    updatedProducts = products;
                    updatedFilter = currentFilter;
                } else {
                    toast = "已筛选官方/自营商品";
                    updatedProducts = filtered;
                    updatedFilter = applySelfOperated(currentFilter, true);
                }
            }
            case "filter_rating_4_8" -> {
                List<ProductCard> filtered = products.stream().filter(p -> p.rating() >= 4.8).toList();
                if (filtered.isEmpty()) {
                    toast = "当前没有 4.8 分以上商品，已保留全部";
                    updatedProducts = products;
                    updatedFilter = currentFilter;
                } else {
                    toast = "已筛选 4.8 分以上商品";
                    updatedProducts = filtered;
                    updatedFilter = applyRatingMin(currentFilter, 4.8);
                }
            }
            case "search_similar_style" -> {
                toast = "已按相似度重新排序，发现更多好物";
                updatedProducts = products.stream().sorted(Comparator.comparing(ProductCard::similarity).reversed()).toList();
                updatedFilter = applySort(currentFilter, null, null);
            }
            case "filter_by_attributes" -> {
                List<ProductCard> filtered = products.stream()
                        .filter(p -> p.tags() != null && !p.tags().isEmpty())
                        .toList();
                if (filtered.isEmpty()) {
                    toast = "当前商品缺少属性标签，无法筛选";
                    updatedProducts = products;
                    updatedFilter = currentFilter;
                } else {
                    long multiTag = filtered.stream().filter(p -> p.tags().size() >= 2).count();
                    toast = "已筛出带属性标签的商品（" + multiTag + " 件多标签匹配）";
                    updatedProducts = filtered;
                    updatedFilter = currentFilter;
                }
            }
            default -> {
                toast = "已应用智能建议";
                updatedProducts = products;
                updatedFilter = currentFilter;
            }
        }

        Set<String> exclude = session.executedActions;
        List<SuggestionCard> newCards = cards("app", updatedProducts, exclude);
        boolean canUndo = !session.undoStack.isEmpty();

        return new SuggestionExecuteResult(updatedProducts, updatedFilter, newCards, toast, canUndo);
    }

    public SuggestionExecuteResult undo(String sessionId, List<ProductCard> currentProducts) {
        SessionData session = sessions.get(sessionId);
        if (session == null || session.undoStack.isEmpty()) {
            return new SuggestionExecuteResult(currentProducts, null, cards("app", currentProducts), "无操作可撤销", false);
        }

        session.touch();
        SearchFilter previous = session.undoStack.pop();

        String last = session.executedActions.stream().reduce((a, b) -> b).orElse(null);
        if (last != null) session.executedActions.remove(last);

        List<SuggestionCard> newCards = cards("app", currentProducts, session.executedActions);
        boolean canUndo = !session.undoStack.isEmpty();

        return new SuggestionExecuteResult(currentProducts, previous, newCards, "已撤销", canUndo);
    }

    @Scheduled(fixedDelay = 300_000)
    void cleanupExpiredSessions() {
        if (sessions.isEmpty()) return;

        Instant cutoff = Instant.now().minusMillis(sessionTtlMillis);
        int beforeSize = sessions.size();

        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccessed.isBefore(cutoff));

        // Max-size cap: remove oldest entries if still too large
        if (sessions.size() > maxSessions) {
            sessions.entrySet().stream()
                    .sorted((a, b) -> a.getValue().lastAccessed.compareTo(b.getValue().lastAccessed))
                    .limit(sessions.size() - maxSessions / 2)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(sessions::remove);
        }

        int removed = beforeSize - sessions.size();
        if (removed > 0) {
            log.info("Cleaned up {} expired suggestion sessions, {} remaining", removed, sessions.size());
        }
    }

    private SessionData getOrCreateSession(String sessionId) {
        if (sessionId == null) return new SessionData();
        return sessions.computeIfAbsent(sessionId, k -> new SessionData());
    }

    // --- Filter helpers ---

    private SearchFilter applySort(SearchFilter filter, String sortBy, String sortOrder) {
        if (filter == null) return SearchFilter.empty();
        return new SearchFilter(filter.priceRange(), filter.platforms(), filter.selfOperated(),
                filter.colors(), filter.brands(), filter.ratingMin(),
                sortBy, sortOrder != null ? sortOrder : "desc", filter.keyword());
    }

    private SearchFilter applySelfOperated(SearchFilter filter, boolean selfOperated) {
        if (filter == null) return SearchFilter.empty();
        return new SearchFilter(filter.priceRange(), filter.platforms(), selfOperated,
                filter.colors(), filter.brands(), filter.ratingMin(),
                filter.sortBy(), filter.sortOrder(), filter.keyword());
    }

    private SearchFilter applyRatingMin(SearchFilter filter, double ratingMin) {
        if (filter == null) return SearchFilter.empty();
        return new SearchFilter(filter.priceRange(), filter.platforms(), filter.selfOperated(),
                filter.colors(), filter.brands(), ratingMin,
                filter.sortBy(), filter.sortOrder(), filter.keyword());
    }
}
