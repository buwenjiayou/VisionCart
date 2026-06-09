package com.visioncart.service.nlp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.FilterTag;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class NlpStateStackService {

    private static final Logger log = LoggerFactory.getLogger(NlpStateStackService.class);
    private static final String KEY_PREFIX = "visioncart:nlp:state_stack:";
    private static final Duration TTL = Duration.ofHours(2);
    public static final int MAX_SUCCESSFUL_NLP_FILTERS = 5;
    public static final int MAX_CONTEXT_UTTERANCES_FOR_LLM = 4;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RecognitionHistoryRepository historyRepository;

    public NlpStateStackService(StringRedisTemplate redis,
                                ObjectMapper objectMapper,
                                RecognitionHistoryRepository historyRepository) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.historyRepository = historyRepository;
    }

    public int size(String sessionId) {
        return load(sessionId).states().size();
    }

    public boolean limitReached(String sessionId) {
        return size(sessionId) >= MAX_SUCCESSFUL_NLP_FILTERS;
    }

    public List<String> contextUtterances(String sessionId) {
        List<NlpState> states = load(sessionId).states();
        if (states.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, states.size() - MAX_CONTEXT_UTTERANCES_FOR_LLM);
        return states.subList(start, states.size()).stream()
                .map(NlpState::rawText)
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }

    public String historyText(String sessionId) {
        List<String> utterances = contextUtterances(sessionId);
        if (utterances.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < utterances.size(); i++) {
            text.append(i + 1).append(". ").append(utterances.get(i)).append('\n');
        }
        return text.toString().trim();
    }

    public void push(String sessionId,
                     String actionId,
                     String rawText,
                     SearchFilter filter,
                     List<FilterTag> tags,
                     List<ProductCard> products) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        NlpStateStack stack = load(sessionId);
        List<NlpState> states = new ArrayList<>(stack.states());
        if (states.size() >= MAX_SUCCESSFUL_NLP_FILTERS) {
            log.info("NLP state stack already at limit for session {}, ignoring push", sessionId);
            return;
        }
        states.add(new NlpState(
                actionId,
                rawText,
                filter != null ? filter : SearchFilter.empty(),
                tags != null ? tags : List.of(),
                products != null ? products : List.of(),
                Instant.now().toString()
        ));
        save(sessionId, new NlpStateStack(states));
        persistTopFilter(sessionId, filter != null ? filter : SearchFilter.empty());
    }

    public Optional<NlpState> pop(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        NlpStateStack stack = load(sessionId);
        List<NlpState> states = new ArrayList<>(stack.states());
        if (states.isEmpty()) {
            return Optional.empty();
        }
        NlpState removed = states.remove(states.size() - 1);
        save(sessionId, new NlpStateStack(states));
        SearchFilter topFilter = states.isEmpty()
                ? SearchFilter.empty()
                : states.get(states.size() - 1).filter();
        persistTopFilter(sessionId, topFilter);
        return Optional.of(removed);
    }

    public Optional<NlpState> peek(String sessionId) {
        List<NlpState> states = load(sessionId).states();
        if (states.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(states.get(states.size() - 1));
    }

    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            redis.delete(key(sessionId));
        } catch (Exception e) {
            log.warn("Failed to clear NLP state stack for {}: {}", sessionId, e.getMessage());
        }
        persistTopFilter(sessionId, SearchFilter.empty());
    }

    private NlpStateStack load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new NlpStateStack(List.of());
        }
        try {
            String json = redis.opsForValue().get(key(sessionId));
            if (json == null || json.isBlank()) {
                return new NlpStateStack(List.of());
            }
            NlpStateStack stack = objectMapper.readValue(json, NlpStateStack.class);
            return stack.states() != null ? stack : new NlpStateStack(List.of());
        } catch (Exception e) {
            log.warn("Failed to load NLP state stack for {}: {}", sessionId, e.getMessage());
            return new NlpStateStack(List.of());
        }
    }

    private void save(String sessionId, NlpStateStack stack) {
        try {
            String json = objectMapper.writeValueAsString(stack);
            redis.opsForValue().set(key(sessionId), json, TTL);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize NLP state stack for {}: {}", sessionId, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to save NLP state stack for {}: {}", sessionId, e.getMessage());
        }
    }

    private void persistTopFilter(String sessionId, SearchFilter filter) {
        try {
            Optional<RecognitionHistory> history = historyRepository.findById(sessionId);
            if (history.isPresent()) {
                RecognitionHistory h = history.get();
                h.setAppliedFiltersJson(objectMapper.writeValueAsString(filter != null ? filter : SearchFilter.empty()));
                h.setUpdatedAt(Instant.now());
                historyRepository.save(h);
            }
        } catch (Exception e) {
            log.warn("Failed to persist NLP stack top filter for {}: {}", sessionId, e.getMessage());
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NlpStateStack(List<NlpState> states) {
        public NlpStateStack() {
            this(List.of());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NlpState(
            String actionId,
            String rawText,
            SearchFilter filter,
            List<FilterTag> tags,
            List<ProductCard> products,
            String createdAt
    ) {}
}
