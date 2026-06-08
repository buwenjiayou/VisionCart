package com.visioncart.service.filter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.search.SearchCandidatePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Unified undo service for ALL user actions (NLP, Suggestion, AttributeCorrection, TagDelete).
 * Stores undo points in Redis with full filter + product snapshot.
 */
@Service
public class NlpUndoService {

    private static final Logger log = LoggerFactory.getLogger(NlpUndoService.class);
    private static final String UNDO_KEY_PREFIX = "filter_undo:";
    private static final int UNDO_STACK_TTL_HOURS = 2;
    private static final int MAX_UNDO_DEPTH = 10;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public NlpUndoService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Save the current state before applying a new filter.
     * Backward-compatible overload (source defaults to "nlp").
     */
    public String saveUndoPoint(String sessionId, SearchFilter previousFilter,
                                List<ProductCard> previousProducts, String rawQuery) {
        return saveUndoPoint(sessionId, previousFilter, previousProducts, rawQuery, "nlp", null, null);
    }

    /**
     * Backward-compatible overload for callers that do not store attribute snapshots.
     */
    public String saveUndoPoint(String sessionId, SearchFilter previousFilter,
                                List<ProductCard> previousProducts, String rawQuery,
                                String source, String actionId) {
        return saveUndoPoint(sessionId, previousFilter, previousProducts, rawQuery, source, actionId, null);
    }

    /**
     * Save the current state before applying a new filter.
     * Unified entry point — all action sources call this.
     *
     * @param sessionId          The session ID
     * @param previousFilter     The filter state before this action
     * @param previousProducts   The product list before this action
     * @param rawQuery           The user-visible text (NLP query, suggestion title, etc.)
     * @param source             The action source: "nlp", "suggestion", "correction", "tag_delete", "sort"
     * @param actionId           Optional action identifier (e.g. suggestion action string)
     * @param previousAttributes Optional snapshot of recognition attributes before this action
     */
    public String saveUndoPoint(String sessionId, SearchFilter previousFilter,
                                List<ProductCard> previousProducts, String rawQuery,
                                String source, String actionId,
                                Map<String, Object> previousAttributes) {
        return saveUndoPoint(sessionId, previousFilter, previousProducts, rawQuery,
                source, actionId, previousAttributes, null);
    }

    /**
     * Save the current state with classifiedPool snapshot.
     * 用于需要恢复 classifiedPool + displayPage 的场景（排序、NLP、标签删除）。
     */
    public String saveUndoPoint(String sessionId, SearchFilter previousFilter,
                                List<ProductCard> previousProducts, String rawQuery,
                                String source, String actionId,
                                Map<String, Object> previousAttributes,
                                SearchCandidatePool previousClassifiedPool) {
        String undoToken = sessionId + ":" + System.currentTimeMillis();
        UndoEntry entry = new UndoEntry(
                sessionId, previousFilter, previousProducts, rawQuery,
                source != null ? source : "nlp",
                actionId,
                System.currentTimeMillis(),
                previousAttributes,
                previousClassifiedPool,
                previousProducts,  // previousDisplayPage = previousProducts
                previousFilter != null ? previousFilter.sortBy() : null
        );

        try {
            String json = objectMapper.writeValueAsString(entry);
            String key = UNDO_KEY_PREFIX + sessionId;
            redis.opsForList().leftPush(key, json);
            redis.expire(key, UNDO_STACK_TTL_HOURS, TimeUnit.HOURS);
            redis.opsForList().trim(key, 0, MAX_UNDO_DEPTH - 1);

            log.info("Saved undo point [{}] for session {}: query='{}', {} previous products",
                    entry.source(), sessionId, rawQuery,
                    previousProducts != null ? previousProducts.size() : 0);
            return undoToken;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize undo entry for session {}", sessionId, e);
            return null;
        }
    }

    /**
     * Undo the last action and return the previous state.
     */
    public UndoResult undo(String sessionId) {
        String key = UNDO_KEY_PREFIX + sessionId;
        String json = redis.opsForList().leftPop(key);

        if (json == null) {
            log.info("No undo point found for session {}", sessionId);
            return null;
        }

        try {
            UndoEntry entry = objectMapper.readValue(json, UndoEntry.class);
            log.info("Undone [{}] for session {}: restored '{}' with {} products",
                    entry.source(), sessionId, entry.rawQuery(),
                    entry.previousProducts() != null ? entry.previousProducts().size() : 0);
            return new UndoResult(
                    entry.previousFilter(),
                    entry.previousProducts(),
                    entry.rawQuery(),
                    entry.source(),
                    entry.previousAttributes(),
                    entry.previousClassifiedPool(),
                    entry.previousDisplayPage(),
                    entry.previousSortKey()
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize undo entry for session {}", sessionId, e);
            return null;
        }
    }

    /**
     * Peek at the last undo point without removing it.
     */
    public UndoResult peekUndo(String sessionId) {
        String key = UNDO_KEY_PREFIX + sessionId;
        String json = redis.opsForList().index(key, 0);

        if (json == null) return null;

        try {
            UndoEntry entry = objectMapper.readValue(json, UndoEntry.class);
            return new UndoResult(
                    entry.previousFilter(),
                    entry.previousProducts(),
                    entry.rawQuery(),
                    entry.source(),
                    entry.previousAttributes(),
                    entry.previousClassifiedPool(),
                    entry.previousDisplayPage(),
                    entry.previousSortKey()
            );
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public boolean canUndo(String sessionId) {
        Long size = redis.opsForList().size(UNDO_KEY_PREFIX + sessionId);
        return size != null && size > 0;
    }

    public void clearUndoStack(String sessionId) {
        redis.delete(UNDO_KEY_PREFIX + sessionId);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UndoEntry(
            String sessionId,
            SearchFilter previousFilter,
            List<ProductCard> previousProducts,
            String rawQuery,
            String source,
            String actionId,
            long createdAt,
            /** Snapshot of recognition attributes before the action (for correction undo) */
            Map<String, Object> previousAttributes,
            /** classifiedPool 快照（Top300 带 tier），用于恢复完整候选池 */
            SearchCandidatePool previousClassifiedPool,
            /** 当时的 displayPage 快照（Top50），用于精确恢复用户看到的页面 */
            List<ProductCard> previousDisplayPage,
            /** 当时的排序方式 */
            String previousSortKey
    ) {}

    public record UndoResult(
            SearchFilter filter,
            List<ProductCard> products,
            String undoneQuery,
            String undoneSource,
            /** Restored recognition attributes (null if not applicable) */
            Map<String, Object> previousAttributes,
            /** 恢复的 classifiedPool（Top300 带 tier） */
            SearchCandidatePool restoredClassifiedPool,
            /** 恢复的 displayPage（Top50） */
            List<ProductCard> restoredDisplayPage,
            /** 恢复的排序方式 */
            String restoredSortKey
    ) {}
}
