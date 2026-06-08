package com.visioncart.service.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.CandidateLightweight;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.strategy.VerticalSearchStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Session-level candidate cache for NLP filtering.
 * Stores lightweight candidates (no imageUrl/detailUrl) to reduce Redis memory.
 * Full ProductCard is built on-demand for frontend display.
 */
@Service
public class CandidateSessionCache {

    private static final Logger log = LoggerFactory.getLogger(CandidateSessionCache.class);
    private static final String KEY_PREFIX = "visioncart:session:candidates:";
    private static final String SEARCH_IDENTITY_KEY_PREFIX = "visioncart:session:candidates:search:";
    private static final String CLASSIFIED_POOL_KEY_PREFIX = "visioncart:session:classified_pool:";
    private static final int TTL_MINUTES = 30;
    private static final int MAX_CANDIDATES = 1000;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CandidateSessionCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Save ranked candidates for a session as lightweight objects.
     * Truncates to MAX_CANDIDATES.
     * Preserves mainCategoryCode + productRole from ProductCard for NLP filtering.
     */
    public void saveCandidates(String sessionId, List<ProductCard> candidates) {
        saveCandidates(sessionId, candidates, null);
    }

    /**
     * Save ranked candidates and optionally bind them to a search identity.
     * The identity lets lock waiters distinguish fresh results from an older
     * candidate pool that happens to share the same session id.
     */
    public void saveCandidates(String sessionId, List<ProductCard> candidates, String searchIdentity) {
        if (sessionId == null || sessionId.isBlank() || candidates == null || candidates.isEmpty()) {
            return;
        }
        try {
            List<CandidateLightweight> lightweight = candidates.stream()
                    .limit(MAX_CANDIDATES)
                    .map(card -> CandidateLightweight.from(card, card.similarity()))
                    .toList();
            String json = objectMapper.writeValueAsString(lightweight);
            String key = KEY_PREFIX + sessionId;
            redisTemplate.opsForValue().set(key, json, TTL_MINUTES, TimeUnit.MINUTES);
            String identityKey = SEARCH_IDENTITY_KEY_PREFIX + sessionId;
            if (searchIdentity != null && !searchIdentity.isBlank()) {
                redisTemplate.opsForValue().set(identityKey, searchIdentity, TTL_MINUTES, TimeUnit.MINUTES);
            } else {
                redisTemplate.delete(identityKey);
            }
            log.debug("Cached {} lightweight candidates for session {}", lightweight.size(), sessionId);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize candidates for session {}: {}", sessionId, e.getMessage());
        } catch (Exception e) {
            log.warn("Redis unavailable for candidate cache (session {}): {}", sessionId, e.getMessage());
        }
    }

    /**
     * Retrieve cached candidates as lightweight objects.
     */
    public List<CandidateLightweight> getLightweightCandidates(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        try {
            String key = KEY_PREFIX + sessionId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to read candidate cache for session {}: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Retrieve cached candidates converted to ProductCard (without imageUrl/detailUrl).
     * Use this for filtering; build full ProductCard for frontend display.
     */
    public List<ProductCard> getCandidates(String sessionId) {
        return getLightweightCandidates(sessionId).stream()
                .map(CandidateLightweight::toProductCard)
                .toList();
    }

    /**
     * 获取当前最佳候选池：优先 classifiedPool（Top300），fallback 到旧 candidates（Top50）。
     * 用于排序、筛选、NLP、标签删除等操作，确保基于最大候选池重算。
     */
    public List<ProductCard> getBestCandidates(String sessionId) {
        return getClassifiedPool(sessionId)
                .filter(pool -> !pool.classifiedPool().isEmpty())
                .map(SearchCandidatePool::toProductCards)
                .orElseGet(() -> getCandidates(sessionId));
    }

    public boolean matchesSearchIdentity(String sessionId, String searchIdentity) {
        if (sessionId == null || sessionId.isBlank()
                || searchIdentity == null || searchIdentity.isBlank()) {
            return false;
        }
        try {
            String stored = redisTemplate.opsForValue().get(SEARCH_IDENTITY_KEY_PREFIX + sessionId);
            return searchIdentity.equals(stored);
        } catch (Exception e) {
            log.warn("Failed to read candidate search identity for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * Check if candidates exist for a session.
     */
    public boolean exists(String sessionId) {
        return !getBestCandidates(sessionId).isEmpty();
    }

    /**
     * Delete cached candidates and classifiedPool for a session.
     */
    public void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
            redisTemplate.delete(SEARCH_IDENTITY_KEY_PREFIX + sessionId);
            redisTemplate.delete(CLASSIFIED_POOL_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("Failed to delete candidate cache for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Get the count of cached candidates.
     */
    public int getCandidateCount(String sessionId) {
        return getBestCandidates(sessionId).size();
    }

    // ==================== classifiedPool 存取 ====================

    /**
     * 保存完整 classifiedPool（Top300 带 tier 分类）。
     * 作为搜索会话的"事实源"，所有排序/筛选/NLP 都基于此池重算 displayPage。
     */
    public void saveClassifiedPool(String sessionId, SearchCandidatePool pool) {
        if (sessionId == null || sessionId.isBlank() || pool == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(pool);
            String key = CLASSIFIED_POOL_KEY_PREFIX + sessionId;
            redisTemplate.opsForValue().set(key, json, TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("Saved classifiedPool ({} products) for session {}", pool.poolSize(), sessionId);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize classifiedPool for session {}: {}", sessionId, e.getMessage());
        } catch (Exception e) {
            log.warn("Redis unavailable for classifiedPool cache (session {}): {}", sessionId, e.getMessage());
        }
    }

    /**
     * 获取 classifiedPool。
     */
    /**
     * Promote a restored or legacy product snapshot into the unified SearchRun pool.
     */
    public void saveDefaultClassifiedPool(String sessionId, List<ProductCard> products) {
        saveDefaultClassifiedPool(sessionId, products, SearchFilter.empty(), null);
    }

    public void saveDefaultClassifiedPool(String sessionId, List<ProductCard> products,
                                          SearchFilter filter, String searchIdentity) {
        if (sessionId == null || sessionId.isBlank() || products == null || products.isEmpty()) {
            return;
        }
        SearchFilter effectiveFilter = filter != null ? filter : SearchFilter.empty();
        List<ProductCard> poolProducts = products.stream()
                .limit(MAX_CANDIDATES)
                .toList();
        List<VerticalSearchStrategy.ClassifiedProduct> classified = poolProducts.stream()
                .map(product -> new VerticalSearchStrategy.ClassifiedProduct(
                        product, IntentGate.IntentTier.EXACT_MAIN))
                .toList();
        ProductIntent defaultIntent = new ProductIntent(
                sessionId,
                "",
                "",
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "",
                "",
                "",
                false,
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                0.0,
                "default_pool"
        );
        SearchCandidatePool pool = new SearchCandidatePool(
                sessionId,
                UUID.randomUUID().toString(),
                searchIdentity,
                defaultIntent,
                "DefaultProductIntentStrategy",
                effectiveFilter,
                classified,
                products.size(),
                poolProducts,
                effectiveFilter,
                effectiveFilter.sortBy(),
                poolProducts
        );
        saveClassifiedPool(sessionId, pool);
        saveCandidates(sessionId, poolProducts, searchIdentity);
    }

    public Optional<SearchCandidatePool> getClassifiedPool(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            String key = CLASSIFIED_POOL_KEY_PREFIX + sessionId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, SearchCandidatePool.class));
        } catch (Exception e) {
            log.warn("Failed to read classifiedPool for session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 删除 classifiedPool。
     */
    public void deleteClassifiedPool(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(CLASSIFIED_POOL_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("Failed to delete classifiedPool for session {}: {}", sessionId, e.getMessage());
        }
    }
}
