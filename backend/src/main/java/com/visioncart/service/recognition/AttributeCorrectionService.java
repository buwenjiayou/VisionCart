package com.visioncart.service.recognition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.*;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.context.SessionContextService;
import com.visioncart.service.filter.FilterClause;
import com.visioncart.service.filter.SafeActionExecutor;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.search.CandidateFilterService;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.search.SearchOrchestrator;
import com.visioncart.service.search.SearchTextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Attribute correction application service.
 * Keeps controller HTTP concerns out of the unified action execution path.
 */
@Service
public class AttributeCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(AttributeCorrectionService.class);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int DEFAULT_RECALL_SIZE = 300;

    /**
     * Only true filter controls stay local. Recognition-panel attributes default
     * to re-search because they usually change product identity or specs.
     */
    private static final Set<String> LOCAL_FILTER_FIELDS = Set.of(
            "platform", "platforms", "平台",
            "price", "price_min", "price_max", "price_range", "budget_under", "预算", "价格",
            "rating", "rating_min", "评分",
            "self_operated", "selfOperated", "selfoperated", "自营"
    );

    private final RecognitionHistoryRepository historyRepository;
    private final NlpConversationManager conversationManager;
    private final CandidateSessionCache sessionCache;
    private final CandidateFilterService filterService;
    private final SearchOrchestrator searchOrchestrator;
    private final ObjectMapper objectMapper;
    private final AsyncRecognitionTaskManager taskManager;
    private final SessionHistoryService sessionHistoryService;
    private final SafeActionExecutor safeActionExecutor;
    private final SessionContextService sessionContextService;

    public AttributeCorrectionService(RecognitionHistoryRepository historyRepository,
                                      NlpConversationManager conversationManager,
                                      CandidateSessionCache sessionCache,
                                      CandidateFilterService filterService,
                                      SearchOrchestrator searchOrchestrator,
                                      ObjectMapper objectMapper,
                                      AsyncRecognitionTaskManager taskManager,
                                      SessionHistoryService sessionHistoryService,
                                      SafeActionExecutor safeActionExecutor,
                                      SessionContextService sessionContextService) {
        this.historyRepository = historyRepository;
        this.conversationManager = conversationManager;
        this.sessionCache = sessionCache;
        this.filterService = filterService;
        this.searchOrchestrator = searchOrchestrator;
        this.objectMapper = objectMapper;
        this.taskManager = taskManager;
        this.sessionHistoryService = sessionHistoryService;
        this.safeActionExecutor = safeActionExecutor;
        this.sessionContextService = sessionContextService;
    }

    public ApiResponse<AttributeCorrectionResult> correctAttribute(
            String sessionId,
            AttributeCorrectionRequest request,
            Long userId) {
        try {
            boolean ownsTask = taskManager.belongsToUser(sessionId, userId);
            Optional<RecognitionHistory> historyOpt = historyRepository.findBySessionIdAndUserId(sessionId, userId);
            if (!ownsTask && historyOpt.isEmpty()) {
                return ApiResponse.fail(403, "无权访问该识别任务");
            }

            Map<String, AttributeValue> parsedAttributes = loadCurrentAttributes(sessionId, historyOpt);

            String field = request.attribute();
            String newValue = request.newValue();
            CorrectionMode mode = getCorrectionMode(field);
            log.info("Attribute correction: session={}, field={}, value={}, mode={}", sessionId, field, newValue, mode);

            if (mode == CorrectionMode.RECALL_REFRESH) {
                return handleRecallRefresh(sessionId, historyOpt, parsedAttributes, field, newValue);
            }
            return handleLocalFilter(sessionId, historyOpt, parsedAttributes, field, newValue, userId);
        } catch (Exception e) {
            log.error("Attribute correction failed for session {}", sessionId, e);
            return ApiResponse.fail(500, "属性修正失败，请稍后重试");
        }
    }

    private ApiResponse<AttributeCorrectionResult> handleRecallRefresh(
            String sessionId,
            Optional<RecognitionHistory> historyOpt,
            Map<String, AttributeValue> parsedAttributes,
            String field,
            String newValue) throws Exception {

        // 1. Update recognition attributes
        Map<String, AttributeValue> updatedAttributes = new HashMap<>(parsedAttributes);
        updatedAttributes.put(field, new AttributeValue(newValue, 1.0, true));

        // 2. Build search attributes from updated recognition (NOT from SearchFilter)
        Map<String, String> searchAttributes = buildSearchAttributes(updatedAttributes);
        searchAttributes.put(SearchTextUtils.ATTR_STRICT_INTENT, "true");
        if (isBrandField(field)) {
            searchAttributes.put(SearchTextUtils.ATTR_BRAND_RELIABLE, "true");
        }

        // 3. Get user's existing filter (price, platform, rating, NLP conditions)
        //    Do NOT add correction fields to SearchFilter — attribute correction changes
        //    search intent, not filter conditions.
        //    IMPORTANT: strip attribute-derived fields (brand, color, keyword) from filter
        //    to avoid conflict with the new searchAttributes.
        SearchFilter userFilter = conversationManager.getFilterState(sessionId);
        SearchFilter cleanFilter = stripAttributeDerivedFilter(userFilter);

        // 4. Persist corrected attributes to DB
        persistAttributes(sessionId, historyOpt, updatedAttributes);

        // 5. Re-search with updated attributes + cleaned filter
        List<ProductCard> previousProducts = sessionCache.getBestCandidates(sessionId);
        SearchRequest searchRequest = new SearchRequest(
                sessionId, searchAttributes, cleanFilter, 1, DEFAULT_PAGE_SIZE, DEFAULT_RECALL_SIZE, "attribute_correction");
        log.info("Attribute correction search: session={}, field={}, value={}, searchAttrs={}, cleanFilter={}",
                sessionId, field, newValue, searchAttributes, cleanFilter);

        SearchResult searchResult;
        try {
            searchResult = searchOrchestrator.search(searchRequest);
        } catch (Exception e) {
            log.warn("Re-search failed for attribute correction: {}", e.getMessage());
            return ApiResponse.ok(new AttributeCorrectionResult(
                    updatedAttributes,
                    new SearchResult(previousProducts.size(), previousProducts, List.of(), List.of()),
                    true,    // correctionApplied
                    false,   // productsUpdated
                    true,    // keptPreviousResults
                    "属性已修正为「" + newValue + "」，但重新搜索失败，已保留原结果",
                    field,
                    newValue,
                    "attribute.updated.search.failed",
                    parsedAttributes  // previousAttributes for undo
            ));
        }

        // 6. If re-search returned results, use them; otherwise keep previous products
        if (searchResult.products() != null && !searchResult.products().isEmpty()) {
            List<ProductCard> newProducts = searchResult.products().stream()
                    .limit(DEFAULT_PAGE_SIZE).toList();
            sessionHistoryService.archiveDisplayedProducts(sessionId, newProducts);

            log.info("Attribute correction re-search succeeded: field={}, value={}, count={}, firstIds={}",
                    field, newValue, newProducts.size(),
                    newProducts.stream().limit(5).map(ProductCard::id).toList());
            return ApiResponse.ok(new AttributeCorrectionResult(
                    updatedAttributes,
                    new SearchResult(newProducts.size(), newProducts, List.of(), List.of(), false,
                            searchResult.searchRunId()),
                    true,    // correctionApplied
                    true,    // productsUpdated
                    false,   // keptPreviousResults
                    null,
                    null,
                    null,
                    "attribute.updated.products.refreshed",
                    parsedAttributes  // previousAttributes for undo
            ));
        }

        // 7. Re-search returned empty — keep previous products, notify user
        log.info("Re-search returned 0 results for field={}, value={}", field, newValue);
        return ApiResponse.ok(new AttributeCorrectionResult(
                updatedAttributes,
                new SearchResult(previousProducts.size(), previousProducts, List.of(), List.of()),
                true,    // correctionApplied
                false,   // productsUpdated
                true,    // keptPreviousResults
                "属性已修正为「" + newValue + "」，但暂未搜索到相关商品，已保留原结果",
                field,
                newValue,
                "attribute.updated.search.empty",
                parsedAttributes  // previousAttributes for undo
        ));
    }

    private boolean isBrandField(String field) {
        return "brand".equals(field) || "品牌".equals(field);
    }

    private Map<String, AttributeValue> loadCurrentAttributes(
            String sessionId,
            Optional<RecognitionHistory> historyOpt) throws Exception {
        Map<String, AttributeValue> historyAttributes = Map.of();
        if (historyOpt.isPresent()) {
            RecognitionHistory history = historyOpt.get();
            if (history.getAttributesJson() != null && !history.getAttributesJson().isBlank()) {
                historyAttributes = objectMapper.readValue(history.getAttributesJson(), new TypeReference<>() {});
            }
        }
        if (historyAttributes != null && !historyAttributes.isEmpty()) {
            return new LinkedHashMap<>(historyAttributes);
        }

        RecognitionTaskResult task = taskManager.getStatus(sessionId);
        if (task != null && task.result() != null && task.result().attributes() != null) {
            Map<String, AttributeValue> taskAttributes = task.result().attributes();
            if (!taskAttributes.isEmpty()) {
                log.info("Loaded {} correction attributes from active task for session {}",
                        taskAttributes.size(), sessionId);
                return new LinkedHashMap<>(taskAttributes);
            }
        }
        return new LinkedHashMap<>();
    }

    private void persistAttributes(String sessionId, Optional<RecognitionHistory> historyOpt,
                                   Map<String, AttributeValue> updatedAttributes) {
        try {
            String updatedJson = objectMapper.writeValueAsString(updatedAttributes);
            historyOpt.ifPresent(history -> {
                history.setAttributesJson(updatedJson);
                history.setUpdatedAt(Instant.now());
                historyRepository.save(history);
            });
        } catch (Exception e) {
            log.warn("Failed to persist updated attributes: {}", e.getMessage());
        }
        persistTaskAttributes(sessionId, updatedAttributes);
    }

    private void persistTaskAttributes(String sessionId, Map<String, AttributeValue> updatedAttributes) {
        try {
            RecognitionTaskResult task = taskManager.getStatus(sessionId);
            if (task == null || task.result() == null) {
                return;
            }
            RecognitionResult current = task.result();
            RecognitionResult updated = new RecognitionResult(
                    current.sessionId(),
                    current.category(),
                    updatedAttributes,
                    current.keywords(),
                    current.overallConfidence(),
                    current.platformStats());
            taskManager.updateResult(sessionId, updated);
        } catch (Exception e) {
            log.warn("Failed to persist active task attributes for session {}: {}", sessionId, e.getMessage());
        }
    }

    private ApiResponse<AttributeCorrectionResult> handleLocalFilter(
            String sessionId,
            Optional<RecognitionHistory> historyOpt,
            Map<String, AttributeValue> parsedAttributes,
            String field,
            String newValue,
            Long userId) throws Exception {

        SearchFilter currentFilter = conversationManager.getFilterState(sessionId);
        SearchFilter updatedFilter = applyAttributeToFilter(currentFilter, field, newValue);
        List<ProductCard> candidatePool = sessionCache.getBestCandidates(sessionId);
        List<ProductCard> previousProducts = filterService.filter(
                candidatePool, currentFilter, Map.of(), DEFAULT_PAGE_SIZE, 1).products();
        List<FilterClause> clauses = compileCorrectionToClause(field, newValue);

        String category = sessionContextService.resolve(sessionId, userId).normalizedCategory();
        SafeActionExecutor.SafeActionResult result = safeActionExecutor.execute(
                sessionId,
                candidatePool,
                clauses,
                currentFilter,
                updatedFilter,
                currentFilter,
                previousProducts,
                category,
                "filter_correction",
                "修正" + field + "=" + newValue
        );

        if (result.committed()) {
            sessionHistoryService.archiveDisplayedProducts(sessionId, result.products());
            log.info("Local filter correction applied: field={}, value={}, results={}",
                    field, newValue, result.products().size());
            return ApiResponse.ok(new AttributeCorrectionResult(
                    null,
                    new SearchResult(result.products().size(), result.products(), List.of(), List.of()),
                    true,
                    true,
                    false,
                    null,
                    null,
                    null,
                    "filter.applied",
                    null
            ));
        }

        log.info("Local filter correction rolled back: field={}, value={}", field, newValue);
        return ApiResponse.ok(new AttributeCorrectionResult(
                null,
                new SearchResult(result.products().size(), result.products(), List.of(), List.of()),
                false,
                false,
                true,
                result.message() != null ? result.message() : "没有找到「" + newValue + "」的商品，已保留原结果",
                field,
                newValue,
                "filter.rollback",
                null
        ));
    }

    /**
     * Compile correction to filter clauses.
     * Only used for LOCAL_FILTER corrections (platform, rating, price).
     * Brand, color, model, material, type, category go through RECALL_REFRESH (re-search).
     */
    private List<FilterClause> compileCorrectionToClause(String field, String newValue) {
        return switch (field) {
            case "platform", "platforms", "平台" -> List.of(
                    FilterClause.structured("correction-platform-" + newValue,
                            newValue, "platform", "eq", newValue));
            case "rating", "rating_min", "评分" -> {
                try {
                    double rating = Double.parseDouble(newValue);
                    yield List.of(FilterClause.structured("correction-rating",
                            "≥" + newValue + "分", "rating_min", "gte", rating));
                } catch (NumberFormatException e) {
                    yield List.of();
                }
            }
            case "price_min" -> {
                Double value = parseDouble(newValue);
                yield value == null ? List.of() : List.of(
                        FilterClause.structured("correction-price-min", "≥¥" + newValue, "price_min", "gte", value));
            }
            case "price", "price_max", "price_range", "budget_under", "预算", "价格" -> {
                Double value = parseDouble(newValue);
                yield value == null ? List.of() : List.of(
                        FilterClause.structured("correction-price-max", "≤¥" + newValue, "price_max", "lte", value));
            }
            default -> List.of(
                    FilterClause.structured("correction-" + field,
                            newValue, field, "eq", newValue));
        };
    }

    private CorrectionMode getCorrectionMode(String field) {
        return isLocalFilterField(field) ? CorrectionMode.LOCAL_FILTER : CorrectionMode.RECALL_REFRESH;
    }

    private boolean isLocalFilterField(String field) {
        if (field == null || field.isBlank()) {
            return false;
        }
        String normalized = field.trim();
        return LOCAL_FILTER_FIELDS.contains(normalized)
                || LOCAL_FILTER_FIELDS.contains(normalized.toLowerCase(Locale.ROOT));
    }

    private Map<String, String> buildSearchAttributes(Map<String, AttributeValue> attributes) {
        Map<String, String> searchAttrs = new LinkedHashMap<>();
        LinkedHashSet<String> keywordValues = new LinkedHashSet<>();
        for (Map.Entry<String, AttributeValue> entry : attributes.entrySet()) {
            String key = entry.getKey();
            AttributeValue attr = entry.getValue();
            String value = attr == null ? "" : SearchTextUtils.useful(attr.value());
            if (value.isBlank()) continue;

            putIfUseful(searchAttrs, key, value);
            String canonicalKey = canonicalAttributeKey(key);
            putIfUseful(searchAttrs, canonicalKey, value);

            if (SearchTextUtils.ATTR_BRAND.equals(canonicalKey)
                    && (attr.verified() || attr.confidence() >= 0.7)) {
                searchAttrs.put(SearchTextUtils.ATTR_BRAND_RELIABLE, "true");
            }
            if (SearchTextUtils.ATTR_KEYWORD.equals(canonicalKey)
                    || isPrecisionAttribute(canonicalKey)
                    || isDescriptiveAttribute(canonicalKey)) {
                keywordValues.add(value);
            }
        }
        if (!keywordValues.isEmpty()) {
            searchAttrs.put(SearchTextUtils.ATTR_KEYWORDS, String.join(",", keywordValues));
        }
        return searchAttrs;
    }

    private void putIfUseful(Map<String, String> target, String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.putIfAbsent(key, value);
    }

    private String canonicalAttributeKey(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "brand", "品牌" -> SearchTextUtils.ATTR_BRAND;
            case "category", "类目", "类别" -> SearchTextUtils.ATTR_CATEGORY;
            case "core_product", "main_product", "product", "商品主体", "商品", "关键词" -> SearchTextUtils.ATTR_KEYWORD;
            case "model", "型号" -> "型号";
            case "series", "系列" -> "系列";
            case "sku", "SKU" -> "SKU";
            case "item_no", "货号" -> "货号";
            case "spec", "规格" -> "规格";
            case "capacity", "容量" -> "容量";
            case "storage", "存储", "内存" -> "存储";
            case "size", "dimension", "尺寸" -> "尺寸";
            case "尺码" -> "尺码";
            case "color", "颜色" -> SearchTextUtils.ATTR_COLOR;
            case "material", "材质" -> "材质";
            case "style", "款式" -> SearchTextUtils.ATTR_STYLE;
            case "pattern", "图案" -> "图案";
            case "type", "类型" -> "类型";
            case "usage", "用途" -> "用途";
            default -> key;
        };
    }

    private boolean isPrecisionAttribute(String key) {
        return Set.of("型号", "系列", "SKU", "货号", "规格", "容量", "存储", "尺寸", "尺码")
                .contains(key);
    }

    private boolean isDescriptiveAttribute(String key) {
        return Set.of(SearchTextUtils.ATTR_COLOR, "材质", SearchTextUtils.ATTR_STYLE, "图案", "类型", "用途")
                .contains(key);
    }

    /**
     * Strip attribute-derived fields from filter to avoid conflict with searchAttributes.
     * Keeps: priceRange, platforms, selfOperated, ratingMin, sortBy, sortOrder, excludeRoles, capabilities
     * Removes: brands, colors, keyword, attributes
     */
    private SearchFilter stripAttributeDerivedFilter(SearchFilter filter) {
        if (filter == null) return SearchFilter.empty();
        return new SearchFilter(
                filter.priceRange(),
                filter.platforms(),
                filter.selfOperated(),
                List.of(),              // colors — remove, will come from searchAttributes
                List.of(),              // brands — remove, will come from searchAttributes
                filter.ratingMin(),
                filter.sortBy(),
                filter.sortOrder(),
                null,                   // keyword — remove, will come from searchAttributes
                Map.of(),               // attributes — remove, will come from searchAttributes
                filter.excludeRoles(),
                filter.capabilities());
    }

    /**
     * Apply a correction to SearchFilter.
     * Only used for LOCAL_FILTER corrections (platform, rating, price) — NOT for
     * recognition attribute corrections (brand, color, model, material, type, category).
     * Recognition attributes go through searchAttributes, not SearchFilter.
     */
    private SearchFilter applyAttributeToFilter(SearchFilter filter, String field, String newValue) {
        if (filter == null) return SearchFilter.empty();
        return switch (field) {
            case "platform", "platforms", "平台" -> new SearchFilter(
                    filter.priceRange(), List.of(newValue), filter.selfOperated(),
                    filter.colors(), filter.brands(), filter.ratingMin(),
                    filter.sortBy(), filter.sortOrder(), filter.keyword(), filter.attributes(),
                    filter.excludeRoles(), filter.capabilities());
            case "rating", "rating_min", "评分" -> new SearchFilter(
                    filter.priceRange(), filter.platforms(), filter.selfOperated(),
                    filter.colors(), filter.brands(), parseDouble(newValue),
                    filter.sortBy(), filter.sortOrder(), filter.keyword(), filter.attributes(),
                    filter.excludeRoles(), filter.capabilities());
            case "price_min" -> {
                PriceRange current = filter.priceRange() != null ? filter.priceRange() : new PriceRange(null, null);
                yield new SearchFilter(new PriceRange(parseDouble(newValue), current.max()), filter.platforms(),
                        filter.selfOperated(), filter.colors(), filter.brands(), filter.ratingMin(),
                        filter.sortBy(), filter.sortOrder(), filter.keyword(), filter.attributes(),
                        filter.excludeRoles(), filter.capabilities());
            }
            case "price", "price_max", "price_range", "budget_under", "预算", "价格" -> {
                PriceRange current = filter.priceRange() != null ? filter.priceRange() : new PriceRange(null, null);
                yield new SearchFilter(new PriceRange(current.min(), parseDouble(newValue)), filter.platforms(),
                        filter.selfOperated(), filter.colors(), filter.brands(), filter.ratingMin(),
                        filter.sortBy(), filter.sortOrder(), filter.keyword(), filter.attributes(),
                        filter.excludeRoles(), filter.capabilities());
            }
            case "self_operated", "selfOperated", "自营" -> new SearchFilter(
                    filter.priceRange(), filter.platforms(), parseBoolean(newValue),
                    filter.colors(), filter.brands(), filter.ratingMin(),
                    filter.sortBy(), filter.sortOrder(), filter.keyword(), filter.attributes(),
                    filter.excludeRoles(), filter.capabilities());
            default -> filter;
        };
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\\d+(?:\\.\\d+)?")
                    .matcher(value);
            return matcher.find() ? Double.parseDouble(matcher.group()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "是".equals(value)
                || "自营".equals(value);
    }

    private enum CorrectionMode {
        RECALL_REFRESH,
        LOCAL_FILTER
    }
}
