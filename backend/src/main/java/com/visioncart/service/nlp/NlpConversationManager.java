package com.visioncart.service.nlp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.NlpParseRequest;
import com.visioncart.api.dto.SearchFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class NlpConversationManager {

    private static final Logger log = LoggerFactory.getLogger(NlpConversationManager.class);
    private static final String KEY_PREFIX = "visioncart:nlp:history:";
    private static final Duration TTL = Duration.ofHours(2);
    private static final int MAX_HISTORY = 3;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public NlpConversationManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
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
            log.warn("Failed to read NLP history for {}: {}", sessionId, e.getMessage());
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
     * 追加一轮对话
     */
    public void addTurn(String sessionId, String userInput, SearchFilter filter) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            List<NlpParseRequest.NlpTurn> history = new ArrayList<>(getHistory(sessionId));
            history.add(new NlpParseRequest.NlpTurn(userInput, filter));
            // 只保留最近 MAX_HISTORY 轮
            if (history.size() > MAX_HISTORY) {
                history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY, history.size()));
            }
            String json = objectMapper.writeValueAsString(new TurnList(history));
            redisTemplate.opsForValue().set(key(sessionId), json, TTL);
        } catch (JsonProcessingException e) {
            log.warn("Failed to save NLP history for {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 清除对话历史
     */
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        redisTemplate.delete(key(sessionId));
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private record TurnList(List<NlpParseRequest.NlpTurn> turns) {
        TurnList() { this(List.of()); }
    }
}
