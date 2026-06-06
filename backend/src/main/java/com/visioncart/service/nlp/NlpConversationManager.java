package com.visioncart.service.nlp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.NlpParseRequest;
import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.filter.capability.CapabilitySynonymRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Optional;

@Component
public class NlpConversationManager {

    private static final Logger log = LoggerFactory.getLogger(NlpConversationManager.class);
    private static final String KEY_PREFIX = "visioncart:nlp:history:";
    private static final String FILTER_STATE_PREFIX = "visioncart:nlp:filterstate:";
    private static final Duration TTL = Duration.ofHours(2);
    private static final Duration FILTER_STATE_TTL = Duration.ofMinutes(30);
    private static final int MAX_HISTORY = 3;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RecognitionHistoryRepository recognitionHistoryRepository;
    private final CapabilitySynonymRegistry capabilityRegistry;

    public NlpConversationManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                  RecognitionHistoryRepository recognitionHistoryRepository,
                                  CapabilitySynonymRegistry capabilityRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.recognitionHistoryRepository = recognitionHistoryRepository;
        this.capabilityRegistry = capabilityRegistry;
    }

    // ==================== Filter State Management ====================

    /**
     * Get the current active filter state for a session.
     * Returns empty filter if no state exists.
     */
    public SearchFilter getFilterState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return SearchFilter.empty();
        try {
            String json = redisTemplate.opsForValue().get(filterStateKey(sessionId));
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, SearchFilter.class);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable for filter state ({}), trying MySQL: {}", sessionId, e.getMessage());
        }
        // Fallback: read from MySQL
        try {
            return recognitionHistoryRepository.findById(sessionId)
                    .map(h -> {
                        String json = h.getAppliedFiltersJson();
                        if (json == null || json.isBlank()) return SearchFilter.empty();
                        try {
                            return objectMapper.readValue(json, SearchFilter.class);
                        } catch (Exception parseError) {
                            log.warn("Failed to parse MySQL filter state for {}: {}", sessionId, parseError.getMessage());
                            return SearchFilter.empty();
                        }
                    })
                    .orElse(SearchFilter.empty());
        } catch (Exception dbError) {
            log.warn("MySQL fallback failed for filter state {}: {}", sessionId, dbError.getMessage());
            return SearchFilter.empty();
        }
    }

    /**
     * Update the filter state by merging new filter with existing state.
     * Non-null fields in newFilter override existing fields.
     * Null fields in newFilter preserve existing values (carry-forward).
     */
    /**
     * 如果 Redis 中没有 filter state，用传入的 filter 初始化（兜底非 NLP 路径）。
     * 已有 state 时不覆盖。
     */
    public void ensureFilterState(String sessionId, SearchFilter filter) {
        if (sessionId == null || sessionId.isBlank() || filter == null) return;
        // Use getFilterState which falls back to MySQL — avoid overwriting NLP-derived state
        // when Redis TTL has expired but MySQL still has the persisted filter
        SearchFilter existing = getFilterState(sessionId);
        if (existing == null || existing.equals(SearchFilter.empty())) {
            saveFilterState(sessionId, filter);
        }
    }

    public SearchFilter updateFilterState(String sessionId, SearchFilter newFilter) {
        SearchFilter existing = getFilterState(sessionId);
        SearchFilter merged = mergeFilterState(existing, newFilter);
        saveFilterState(sessionId, merged);
        return merged;
    }

    /**
     * Merge new filter with existing state WITHOUT writing to Redis/MySQL.
     * Used for trial execution — only commit after ZeroResultGuard approves.
     */
    public SearchFilter mergeFilterWithoutCommit(String sessionId, SearchFilter newFilter) {
        SearchFilter existing = getFilterState(sessionId);
        return mergeFilterState(existing, newFilter);
    }

    /**
     * Replace the filter state entirely (used when user explicitly clears/modifies).
     */
    public void setFilterState(String sessionId, SearchFilter filter) {
        saveFilterState(sessionId, filter);
    }

    /**
     * Clear the filter state for a session.
     */
    public void clearFilterState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            redisTemplate.delete(filterStateKey(sessionId));
        } catch (Exception e) {
            log.warn("Failed to clear filter state for {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Remove a specific field from the filter state.
     * Supports nested fields: "price_range.max", "attributes.head_count"
     * Supports value removal: "colors.black" (remove single color from list)
     */
    public SearchFilter removeFilterField(String sessionId, String fieldName) {
        SearchFilter current = getFilterState(sessionId);

        // Handle nested field removal
        if (fieldName.contains(".")) {
            String[] parts = fieldName.split("\\.", 2);
            String parent = parts[0];
            String child = parts[1];
            return removeNestedField(sessionId, current, parent, child);
        }

        // Handle top-level field removal
        SearchFilter cleared = switch (fieldName) {
            case "price_range", "price" -> SearchFilter.fromSource(current,
                    new PriceRange(null, null),
                    current.platforms(), current.selfOperated(), current.colors(),
                    current.brands(), current.ratingMin(), current.sortBy(),
                    current.sortOrder(), current.keyword(), current.attributes());
            case "platforms" -> SearchFilter.fromSource(current,
                    current.priceRange(), List.of(), current.selfOperated(), current.colors(),
                    current.brands(), current.ratingMin(), current.sortBy(),
                    current.sortOrder(), current.keyword(), current.attributes());
            case "self_operated" -> SearchFilter.fromSource(current,
                    current.priceRange(), current.platforms(), null, current.colors(),
                    current.brands(), current.ratingMin(), current.sortBy(),
                    current.sortOrder(), current.keyword(), current.attributes());
            case "colors" -> SearchFilter.fromSource(current,
                    current.priceRange(), current.platforms(), current.selfOperated(), List.of(),
                    current.brands(), current.ratingMin(), current.sortBy(),
                    current.sortOrder(), current.keyword(), current.attributes());
            case "brands" -> SearchFilter.fromSource(current,
                    current.priceRange(), current.platforms(), current.selfOperated(), current.colors(),
                    List.of(), current.ratingMin(), current.sortBy(),
                    current.sortOrder(), current.keyword(), current.attributes());
            case "rating_min" -> SearchFilter.fromSource(current,
                    current.priceRange(), current.platforms(), current.selfOperated(), current.colors(),
                    current.brands(), null, current.sortBy(),
                    current.sortOrder(), current.keyword(), current.attributes());
            case "keyword" -> SearchFilter.fromSource(current,
                    current.priceRange(), current.platforms(), current.selfOperated(), current.colors(),
                    current.brands(), current.ratingMin(), current.sortBy(),
                    current.sortOrder(), null, current.attributes());
            case "sort" -> SearchFilter.fromSource(current,
                    current.priceRange(), current.platforms(), current.selfOperated(), current.colors(),
                    current.brands(), current.ratingMin(), null, null,
                    current.keyword(), current.attributes());
            case "attributes" -> SearchFilter.fromSource(current,
                    current.priceRange(), current.platforms(), current.selfOperated(), current.colors(),
                    current.brands(), current.ratingMin(), current.sortBy(),
                    current.sortOrder(), current.keyword(), Map.of());
            case "exclude_roles", "excludeRoles" -> SearchFilter.fromSource(current,
                    current.priceRange(), current.platforms(), current.selfOperated(), current.colors(),
                    current.brands(), current.ratingMin(), current.sortBy(),
                    current.sortOrder(), current.keyword(), current.attributes());
            default -> {
                // Check if this is a capability removal (by code or display text)
                String capCode = resolveCapabilityCode(fieldName);
                if (capCode != null && current.capabilities() != null && current.capabilities().containsKey(capCode)) {
                    Map<String, Boolean> newCaps = new java.util.HashMap<>(current.capabilities());
                    newCaps.remove(capCode);
                    yield new SearchFilter(current.priceRange(), current.platforms(), current.selfOperated(),
                            current.colors(), current.brands(), current.ratingMin(), current.sortBy(),
                            current.sortOrder(), current.keyword(), current.attributes(),
                            current.excludeRoles(), newCaps);
                }
                yield current;
            }
        };
        saveFilterState(sessionId, cleared);
        return cleared;
    }

    private SearchFilter removeNestedField(String sessionId, SearchFilter current, String parent, String child) {
        SearchFilter updated = switch (parent) {
            case "price_range", "price" -> {
                PriceRange pr = current.priceRange();
                if (pr == null) yield current;
                yield switch (child) {
                    case "max" -> SearchFilter.fromSource(current, new PriceRange(pr.min(), null),
                            current.platforms(), current.selfOperated(), current.colors(),
                            current.brands(), current.ratingMin(), current.sortBy(),
                            current.sortOrder(), current.keyword(), current.attributes());
                    case "min" -> SearchFilter.fromSource(current, new PriceRange(null, pr.max()),
                            current.platforms(), current.selfOperated(), current.colors(),
                            current.brands(), current.ratingMin(), current.sortBy(),
                            current.sortOrder(), current.keyword(), current.attributes());
                    default -> current;
                };
            }
            case "colors" -> {
                List<String> safeColors = current.colors() != null ? current.colors() : List.of();
                List<String> newColors = safeColors.stream()
                        .filter(c -> !c.equalsIgnoreCase(child))
                        .toList();
                yield SearchFilter.fromSource(current, current.priceRange(), current.platforms(), current.selfOperated(),
                        newColors, current.brands(), current.ratingMin(), current.sortBy(),
                        current.sortOrder(), current.keyword(), current.attributes());
            }
            case "brands" -> {
                List<String> safeBrands = current.brands() != null ? current.brands() : List.of();
                List<String> newBrands = safeBrands.stream()
                        .filter(b -> !b.equalsIgnoreCase(child))
                        .toList();
                yield SearchFilter.fromSource(current, current.priceRange(), current.platforms(), current.selfOperated(),
                        current.colors(), newBrands, current.ratingMin(), current.sortBy(),
                        current.sortOrder(), current.keyword(), current.attributes());
            }
            case "platforms" -> {
                List<String> safePlatforms = current.platforms() != null ? current.platforms() : List.of();
                List<String> newPlatforms = safePlatforms.stream()
                        .filter(p -> !p.equalsIgnoreCase(child))
                        .toList();
                yield SearchFilter.fromSource(current, current.priceRange(), newPlatforms, current.selfOperated(),
                        current.colors(), current.brands(), current.ratingMin(), current.sortBy(),
                        current.sortOrder(), current.keyword(), current.attributes());
            }
            case "attributes" -> {
                Map<String, String> safeAttrs = current.attributes() != null ? current.attributes() : Map.of();
                Map<String, String> newAttrs = new java.util.HashMap<>(safeAttrs);
                newAttrs.remove(child);
                yield SearchFilter.fromSource(current, current.priceRange(), current.platforms(), current.selfOperated(),
                        current.colors(), current.brands(), current.ratingMin(), current.sortBy(),
                        current.sortOrder(), current.keyword(), newAttrs);
            }
            case "exclude_roles", "excludeRoles" -> {
                List<String> safeRoles = current.excludeRoles() != null ? current.excludeRoles() : List.of();
                List<String> newRoles = safeRoles.stream()
                        .filter(r -> !r.equalsIgnoreCase(child))
                        .toList();
                yield new SearchFilter(current.priceRange(), current.platforms(), current.selfOperated(),
                        current.colors(), current.brands(), current.ratingMin(), current.sortBy(),
                        current.sortOrder(), current.keyword(), current.attributes(), newRoles, current.capabilities());
            }
            case "capabilities" -> {
                // Dot-notation capability removal: "capabilities.airplane_allowed" → remove airplane_allowed
                Map<String, Boolean> safeCaps = current.capabilities() != null ? current.capabilities() : Map.of();
                if (safeCaps.containsKey(child)) {
                    Map<String, Boolean> newCaps = new java.util.HashMap<>(safeCaps);
                    newCaps.remove(child);
                    yield new SearchFilter(current.priceRange(), current.platforms(), current.selfOperated(),
                            current.colors(), current.brands(), current.ratingMin(), current.sortBy(),
                            current.sortOrder(), current.keyword(), current.attributes(), current.excludeRoles(), newCaps);
                }
                yield current;
            }
            default -> current;
        };
        saveFilterState(sessionId, updated);
        return updated;
    }

    private void saveFilterState(String sessionId, SearchFilter filter) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            String json = objectMapper.writeValueAsString(filter);
            // Save to Redis (short-term, fast access)
            redisTemplate.opsForValue().set(filterStateKey(sessionId), json, FILTER_STATE_TTL);
            // Persist to MySQL (long-term, survives Redis expiry)
            try {
                Optional<RecognitionHistory> history = recognitionHistoryRepository.findById(sessionId);
                if (history.isPresent()) {
                    RecognitionHistory h = history.get();
                    h.setAppliedFiltersJson(json);
                    h.setUpdatedAt(Instant.now());
                    recognitionHistoryRepository.save(h);
                }
            } catch (Exception dbError) {
                log.warn("Failed to persist filter state to MySQL for {}: {}", sessionId, dbError.getMessage());
            }
        } catch (Exception e) {
            log.warn("Failed to save filter state for {}: {}", sessionId, e.getMessage());
        }
    }

    private SearchFilter mergeFilterState(SearchFilter existing, SearchFilter newFilter) {
        if (newFilter == null) return existing;
        if (existing == null) return newFilter;

        // PriceRange: field-by-field merge (not atomic replacement)
        PriceRange existingPrice = existing.priceRange();
        PriceRange newPrice = newFilter.priceRange();
        Double mergedMin = (newPrice != null && newPrice.min() != null) ? newPrice.min()
                : (existingPrice != null ? existingPrice.min() : null);
        Double mergedMax = (newPrice != null && newPrice.max() != null) ? newPrice.max()
                : (existingPrice != null ? existingPrice.max() : null);
        PriceRange priceRange = new PriceRange(mergedMin, mergedMax);

        List<String> platforms = (newFilter.platforms() != null && !newFilter.platforms().isEmpty())
                ? newFilter.platforms()
                : existing.platforms();

        Boolean selfOperated = newFilter.selfOperated() != null
                ? newFilter.selfOperated()
                : existing.selfOperated();

        List<String> colors = (newFilter.colors() != null && !newFilter.colors().isEmpty())
                ? newFilter.colors()
                : existing.colors();

        List<String> brands = (newFilter.brands() != null && !newFilter.brands().isEmpty())
                ? newFilter.brands()
                : existing.brands();

        Double ratingMin = newFilter.ratingMin() != null
                ? newFilter.ratingMin()
                : existing.ratingMin();

        String sortBy = newFilter.sortBy() != null && !newFilter.sortBy().isBlank()
                ? newFilter.sortBy()
                : existing.sortBy();

        String sortOrder = newFilter.sortOrder() != null && !newFilter.sortOrder().isBlank()
                ? newFilter.sortOrder()
                : existing.sortOrder();

        String keyword = newFilter.keyword() != null && !newFilter.keyword().isBlank()
                ? newFilter.keyword()
                : existing.keyword();

        // Merge attributes: new non-empty map overrides, otherwise keep existing
        Map<String, String> attributes = (newFilter.attributes() != null && !newFilter.attributes().isEmpty())
                ? newFilter.attributes()
                : (existing.attributes() != null ? existing.attributes() : Map.of());

        // Merge excludeRoles: new non-empty list overrides, otherwise keep existing
        List<String> excludeRoles = (newFilter.excludeRoles() != null && !newFilter.excludeRoles().isEmpty())
                ? newFilter.excludeRoles()
                : (existing.excludeRoles() != null ? existing.excludeRoles() : List.of());

        // Merge capabilities: new non-empty map overrides, otherwise keep existing
        Map<String, Boolean> capabilities = (newFilter.capabilities() != null && !newFilter.capabilities().isEmpty())
                ? newFilter.capabilities()
                : (existing.capabilities() != null ? existing.capabilities() : Map.of());

        return new SearchFilter(priceRange, platforms, selfOperated, colors, brands,
                ratingMin, sortBy, sortOrder, keyword, attributes, excludeRoles, capabilities);
    }

    /**
     * Resolve a field name (display text or code) to a capability code.
     * Returns null if not a known capability.
     * Delegates to centralized CapabilitySynonymRegistry.
     */
    public String resolveCapabilityCode(String fieldName) {
        return capabilityRegistry.resolveToCode(fieldName);
    }

    private String filterStateKey(String sessionId) {
        return FILTER_STATE_PREFIX + sessionId;
    }

    /**
     * 获取对话历史（最近 N 轮）
     */
    public List<NlpParseRequest.NlpTurn> getHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        try {
            String json = redisTemplate.opsForValue().get(key(sessionId));
            if (json == null) return List.of();
            TurnList turns = objectMapper.readValue(json, TurnList.class);
            return turns.turns != null ? turns.turns : List.of();
        } catch (Exception e) {
            log.warn("Redis unavailable for NLP history ({}), LLM will lose multi-turn context: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 是否已达追加上限
     */
    public boolean isLimitReached(String sessionId) {
        return getHistory(sessionId).size() >= MAX_HISTORY;
    }

    /**
     * 追加一轮对话（带短锁防并发写入，短暂 retry 避免静默丢轮次）
     */
    public void addTurn(String sessionId, String userInput, SearchFilter filter) {
        if (sessionId == null || sessionId.isBlank()) return;
        String lockKey = key(sessionId) + ":lock";
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS);
                if (Boolean.FALSE.equals(locked)) {
                    if (attempt < maxRetries - 1) {
                        try { Thread.sleep(50L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                        continue;
                    }
                    log.warn("NLP history lock held for {} after {} attempts, dropping turn", sessionId, maxRetries);
                    return;
                }
                try {
                    List<NlpParseRequest.NlpTurn> history = new ArrayList<>(getHistory(sessionId));
                    history.add(new NlpParseRequest.NlpTurn(userInput, filter));
                    if (history.size() > MAX_HISTORY) {
                        history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY, history.size()));
                    }
                    String json = objectMapper.writeValueAsString(new TurnList(history));
                    redisTemplate.opsForValue().set(key(sessionId), json, TTL);
                } finally {
                    redisTemplate.delete(lockKey);
                }
                return;
            } catch (Exception e) {
                log.warn("Failed to save NLP history for {}: {}", sessionId, e.getMessage());
                return;
            }
        }
    }

    /**
     * 清除对话历史
     */
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            redisTemplate.delete(key(sessionId));
        } catch (Exception e) {
            log.warn("Failed to clear NLP history for {}: {}", sessionId, e.getMessage());
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private record TurnList(List<NlpParseRequest.NlpTurn> turns) {
        TurnList() { this(List.of()); }
    }
}
