package com.visioncart.service.nlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.NlpParseRequest;
import com.visioncart.api.dto.NlpParseResult;
import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.ai.AiTraceService;
import com.visioncart.service.ai.PromptLoader;
import com.visioncart.service.ai.RetryPolicy;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SpringAiNlpService implements NlpModelService {
    private static final Logger log = LoggerFactory.getLogger(SpringAiNlpService.class);
    private final RuleBasedNlpParser ruleParser;
    private final NlpConversationManager conversationManager;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final AiTraceService traceService;
    private final PromptLoader promptLoader;

    public SpringAiNlpService(RuleBasedNlpParser ruleParser,
                              NlpConversationManager conversationManager,
                              ObjectProvider<ChatClient.Builder> chatClientBuilder,
                              ObjectMapper objectMapper,
                              AiTraceService traceService,
                              PromptLoader promptLoader) {
        this.ruleParser = ruleParser;
        this.conversationManager = conversationManager;
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
        this.promptLoader = promptLoader;
    }

    @Override
    public NlpParseResult parse(NlpParseRequest request) {
        // Prompt injection protection
        String sanitizedInput = PromptSanitizer.sanitize(request.userInput());
        if (sanitizedInput.isBlank()) {
            return new NlpParseResult(SearchFilter.empty(), 0.0, false, "input_rejected",
                    "输入无法识别，请重新描述您的需求");
        }
        if (PromptSanitizer.containsInjection(request.userInput())) {
            log.warn("Potential prompt injection detected, sessionId={}, originalLength={}",
                    request.sessionId(), request.userInput().length());
            return new NlpParseResult(SearchFilter.empty(), 0.0, false, "input_rejected",
                    "输入包含不允许的内容，请重新描述您的需求");
        }

        // 检查追加上限
        if (conversationManager.isLimitReached(request.sessionId())) {
            return new NlpParseResult(
                    ruleParser.parse(sanitizedInput).filter(), 0.50, false, "limit_reached",
                    "筛选已达上限（3轮），请开始新的搜索以继续追加筛选条件");
        }

        RuleBasedNlpParser.ParsedFilter parsed = ruleParser.parse(sanitizedInput);

        // 从 Redis 获取服务端存储的历史
        List<NlpParseRequest.NlpTurn> history = conversationManager.getHistory(request.sessionId());

        // 规则解析完整 → 直接返回
        if (parsed.complete()) {
            conversationManager.addTurn(request.sessionId(), sanitizedInput, parsed.filter());
            return new NlpParseResult(parsed.filter(), 0.92, false, "rule_primary", null);
        }

        // 规则不完整 → 调 LLM 补全
        String traceId = traceService.start("nlp.parse", Map.of("input", sanitizedInput));
        try {
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                throw new IllegalStateException("Spring AI ChatClient.Builder is unavailable");
            }
            String system = systemPrompt();
            String user = userPrompt(sanitizedInput, request.context(), parsed.filter(), history);
            String json = RetryPolicy.executeWithRetry(() ->
                    builder.build()
                            .prompt()
                            .system(system)
                            .user(user)
                            .call()
                            .content(),
                    2, 1000L);
            SearchFilter filter = merge(parsed.filter(), parseAiJson(json));
            conversationManager.addTurn(request.sessionId(), sanitizedInput, filter);
            traceService.finish("nlp.parse", traceId, "spring_ai_llm");
            return new NlpParseResult(filter, 0.95, false, "spring_ai_llm", null);
        } catch (Exception error) {
            traceService.fail("nlp.parse", traceId, error, "none");
            log.warn("LLM unavailable, building best-effort fallback: {}", error.getMessage());
            return buildFallback(parsed.filter(), history);
        }
    }

    private NlpParseResult buildFallback(SearchFilter ruleResult, List<NlpParseRequest.NlpTurn> history) {
        if (history == null || history.isEmpty()) {
            return new NlpParseResult(ruleResult, 0.60, false, "rule_fallback",
                    "智能解析暂不可用，已按基础规则筛选");
        }

        NlpParseRequest.NlpTurn lastTurn = history.get(history.size() - 1);
        SearchFilter lastFilter = lastTurn.filter();
        if (lastFilter == null) {
            return new NlpParseResult(ruleResult, 0.60, false, "rule_fallback",
                    "智能解析暂不可用，已按基础规则筛选");
        }

        SearchFilter merged = merge(ruleResult, lastFilter);
        return new NlpParseResult(merged, 0.70, false, "rule_history_fallback",
                "智能解析暂不可用，已结合上次筛选条件");
    }

    private String systemPrompt() {
        return promptLoader.getPrompt("nlp-system");
    }

    private String userPrompt(String sanitizedInput, NlpParseRequest.NlpContext context,
                              SearchFilter preExtracted, List<NlpParseRequest.NlpTurn> history) {
        String productName = context == null ? "" : StringUtils.defaultString(context.productName());
        String category = context == null ? "" : StringUtils.defaultString(context.category());

        StringBuilder historyText = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 3);
            for (int i = start; i < history.size(); i++) {
                NlpParseRequest.NlpTurn turn = history.get(i);
                historyText.append("用户: ").append(turn.userInput());
                if (turn.filter() != null) {
                    historyText.append(" → 解析: ").append(toJson(turn.filter()));
                }
                historyText.append("\n");
            }
        }

        return promptLoader.getPrompt("nlp-user").formatted(productName, category,
                historyText.length() > 0 ? historyText.toString() : "（无）",
                toJson(preExtracted), sanitizedInput);
    }

    private SearchFilter parseAiJson(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode filter = root.path("filter");
        JsonNode price = filter.path("price_range");
        SearchFilter raw = new SearchFilter(
                new PriceRange(doubleOrNull(price.path("min")), doubleOrNull(price.path("max"))),
                list(filter.path("platforms")),
                filter.path("self_operated").isMissingNode() || filter.path("self_operated").isNull() ? null : filter.path("self_operated").asBoolean(),
                list(filter.path("colors")),
                list(filter.path("brands")),
                doubleOrNull(filter.path("rating_min")),
                textOrNull(filter.path("sort_by")),
                StringUtils.defaultIfBlank(textOrNull(filter.path("sort_order")), "desc"),
                textOrNull(filter.path("keyword"))
        );
        return AiOutputValidator.validate(raw);
    }

    private SearchFilter merge(SearchFilter rule, SearchFilter ai) {
        PriceRange range = rule.priceRange() != null && (rule.priceRange().min() != null || rule.priceRange().max() != null)
                ? rule.priceRange()
                : ai.priceRange();
        return new SearchFilter(
                range,
                mergeLists(rule.platforms(), ai.platforms()),
                rule.selfOperated() != null ? rule.selfOperated() : ai.selfOperated(),
                mergeLists(rule.colors(), ai.colors()),
                mergeLists(rule.brands(), ai.brands()),
                rule.ratingMin() != null ? rule.ratingMin() : ai.ratingMin(),
                StringUtils.defaultIfBlank(rule.sortBy(), ai.sortBy()),
                StringUtils.defaultIfBlank(rule.sortOrder(), StringUtils.defaultIfBlank(ai.sortOrder(), "desc")),
                StringUtils.defaultIfBlank(rule.keyword(), ai.keyword())
        );
    }

    private List<String> mergeLists(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>();
        if (left != null) merged.addAll(left);
        if (right != null) {
            for (String item : right) {
                if (!merged.contains(item)) merged.add(item);
            }
        }
        return merged;
    }

    private List<String> list(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (!item.isNull() && !item.asText().isBlank()) values.add(item.asText());
            });
        }
        return values;
    }

    private Double doubleOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asDouble();
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    private String toJson(SearchFilter filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (Exception e) {
            return "{}";
        }
    }
}
