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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
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

        if (conversationManager.isLimitReached(request.sessionId())) {
            return new NlpParseResult(
                    ruleParser.parse(sanitizedInput).filter(), 0.50, false, "limit_reached",
                    "筛选已达上限，请开始新的搜索以继续追加筛选条件");
        }

        List<NlpParseRequest.NlpTurn> history = conversationManager.getHistory(request.sessionId());
        String traceId = traceService.start("nlp.parse", Map.of("input", sanitizedInput));

        try {
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                throw new IllegalStateException("Spring AI ChatClient.Builder is unavailable");
            }

            String json = RetryPolicy.executeWithRetry(() ->
                            builder.build()
                                    .prompt()
                                    .system(systemPrompt())
                                    .user(userPrompt(sanitizedInput, request.context(), history))
                                    .call()
                                    .content(),
                    2, 1000L);

            var parsed = parseAiJsonWithClauses(json);
            SearchFilter ruleFilter = ruleParser.parse(sanitizedInput).filter();
            SearchFilter validated = validate(parsed.filter(), ruleFilter);

            conversationManager.addTurn(request.sessionId(), sanitizedInput, validated);
            traceService.finish("nlp.parse", traceId, "spring_ai_llm");
            return new NlpParseResult(validated, 0.95, false, "spring_ai_llm", null, parsed.clauses());
        } catch (Exception error) {
            traceService.fail("nlp.parse", traceId, error, "none");
            log.warn("LLM unavailable, falling back to rule-based parsing: sessionId={}, reason={}",
                    request.sessionId(), error.getMessage());
            SearchFilter ruleFilter = ruleParser.parse(sanitizedInput).filter();
            return buildFallback(ruleFilter, history);
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
                              List<NlpParseRequest.NlpTurn> history) {
        String productName = context == null ? "" : StringUtils.defaultString(context.productName());
        String category = context == null ? "" : StringUtils.defaultString(context.category());

        StringBuilder historyText = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 3);
            for (int i = start; i < history.size(); i++) {
                NlpParseRequest.NlpTurn turn = history.get(i);
                historyText.append("用户: ").append(turn.userInput());
                if (turn.filter() != null) {
                    historyText.append(" -> 解析: ").append(toJson(turn.filter()));
                }
                historyText.append("\n");
            }
        }

        return promptLoader.getPrompt("nlp-user").formatted(
                productName,
                category,
                historyText.length() > 0 ? historyText.toString() : "（无）",
                sanitizedInput);
    }

    private SearchFilter parseAiJson(String json) throws Exception {
        return parseAiJsonWithClauses(json).filter();
    }

    /**
     * Parse LLM JSON output into SearchFilter + DetectedClauses (capabilities, preferences, exclusions).
     */
    private ParsedAiOutput parseAiJsonWithClauses(String json) throws Exception {
        JsonNode root = objectMapper.readTree(extractJsonObject(json));
        JsonNode filter = root.path("filter");
        JsonNode price = filter.path("price_range");

        Map<String, String> attributes = mapFromJson(filter.path("attributes"));
        Map<String, String> excludeAttrs = mapFromJson(filter.path("exclude_attributes"));
        Map<String, String> mergedAttributes = new java.util.HashMap<>(attributes);
        excludeAttrs.forEach((key, value) -> mergedAttributes.put("exclude_" + key, value));

        SearchFilter raw = new SearchFilter(
                price.isMissingNode() || price.isNull()
                        ? null
                        : new PriceRange(doubleOrNull(price.path("min")), doubleOrNull(price.path("max"))),
                list(filter.path("platforms")),
                filter.path("self_operated").isMissingNode() || filter.path("self_operated").isNull()
                        ? null
                        : filter.path("self_operated").asBoolean(),
                list(filter.path("colors")),
                list(filter.path("brands")),
                doubleOrNull(filter.path("rating_min")),
                textOrNull(filter.path("sort_by")),
                StringUtils.defaultIfBlank(textOrNull(filter.path("sort_order")), "desc"),
                textOrNull(filter.path("keyword")),
                mergedAttributes
        );
        SearchFilter validated = AiOutputValidator.validate(raw);

        // Parse clauses array (capabilities, preferences, exclusions)
        List<NlpParseResult.DetectedClause> clauses = parseClauses(root.path("clauses"));

        return new ParsedAiOutput(validated, clauses);
    }

    private List<NlpParseResult.DetectedClause> parseClauses(JsonNode clausesNode) {
        List<NlpParseResult.DetectedClause> result = new java.util.ArrayList<>();
        if (clausesNode == null || !clausesNode.isArray()) return result;

        for (JsonNode clause : clausesNode) {
            String type = textOrNull(clause.path("type"));
            String field = textOrNull(clause.path("field"));
            double confidence = clause.has("confidence") ? clause.path("confidence").asDouble(0.7) : 0.7;
            String rawText = textOrNull(clause.path("rawText"));

            if (type != null && field != null && !type.isBlank() && !field.isBlank()) {
                // Validate type
                if ("capability".equals(type) || "preference".equals(type) || "exclusion".equals(type)) {
                    result.add(new NlpParseResult.DetectedClause(type, field, confidence, rawText));
                }
            }
        }
        return result;
    }

    private record ParsedAiOutput(SearchFilter filter, List<NlpParseResult.DetectedClause> clauses) {}

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM returned empty response");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM response does not contain a JSON object");
        }
        return text.substring(start, end + 1);
    }

    private Map<String, String> mapFromJson(JsonNode node) {
        Map<String, String> result = new java.util.HashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual() && !entry.getValue().asText().isBlank()) {
                    result.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }
        return result;
    }

    public String extractIntent(String json) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(json));
            String intent = textOrNull(root.path("intent"));
            if ("new_search".equals(intent)) return "new_search";
            if ("chat".equals(intent)) return "chat";
            return "refine";
        } catch (Exception e) {
            return "refine";
        }
    }

    private SearchFilter validate(SearchFilter llm, SearchFilter rule) {
        SearchFilter safeLlm = llm == null ? SearchFilter.empty() : llm;
        SearchFilter safeRule = rule == null ? SearchFilter.empty() : rule;

        PriceRange priceRange = (safeRule.priceRange() != null
                && (safeRule.priceRange().min() != null || safeRule.priceRange().max() != null))
                ? safeRule.priceRange()
                : safeLlm.priceRange();

        List<String> platforms = (safeRule.platforms() != null && !safeRule.platforms().isEmpty())
                ? safeRule.platforms()
                : safeLlm.platforms();

        Boolean selfOperated = safeRule.selfOperated() != null ? safeRule.selfOperated() : safeLlm.selfOperated();
        Double ratingMin = safeRule.ratingMin() != null ? safeRule.ratingMin() : safeLlm.ratingMin();
        String sortBy = StringUtils.defaultIfBlank(safeRule.sortBy(), safeLlm.sortBy());
        String sortOrder = StringUtils.defaultIfBlank(safeRule.sortOrder(),
                StringUtils.defaultIfBlank(safeLlm.sortOrder(), "desc"));
        String keyword = StringUtils.defaultIfBlank(safeLlm.keyword(), safeRule.keyword());
        List<String> colors = mergeLists(safeRule.colors(), safeLlm.colors());
        List<String> brands = (safeLlm.brands() != null && !safeLlm.brands().isEmpty())
                ? safeLlm.brands()
                : (safeRule.brands() != null ? safeRule.brands() : List.of());
        Map<String, String> attributes = (safeLlm.attributes() != null && !safeLlm.attributes().isEmpty())
                ? safeLlm.attributes()
                : (safeRule.attributes() != null ? safeRule.attributes() : Map.of());

        // Preserve capabilities from both sources
        Map<String, Boolean> caps = new java.util.HashMap<>();
        if (safeRule.capabilities() != null) caps.putAll(safeRule.capabilities());
        if (safeLlm.capabilities() != null) safeLlm.capabilities().forEach((k, v) -> caps.putIfAbsent(k, v));

        return new SearchFilter(priceRange, platforms, selfOperated, colors, brands,
                ratingMin, sortBy, sortOrder, keyword, attributes, List.of(), caps);
    }

    private SearchFilter merge(SearchFilter rule, SearchFilter ai) {
        PriceRange range = rule.priceRange() != null && (rule.priceRange().min() != null || rule.priceRange().max() != null)
                ? rule.priceRange()
                : ai.priceRange();
        Map<String, String> mergedAttrs = new java.util.HashMap<>();
        if (rule.attributes() != null) mergedAttrs.putAll(rule.attributes());
        if (ai.attributes() != null) mergedAttrs.putAll(ai.attributes());
        // Preserve capabilities from both sources
        Map<String, Boolean> mergedCaps = new java.util.HashMap<>();
        if (rule.capabilities() != null) mergedCaps.putAll(rule.capabilities());
        if (ai.capabilities() != null) ai.capabilities().forEach((k, v) -> mergedCaps.putIfAbsent(k, v));
        return new SearchFilter(
                range,
                mergeLists(rule.platforms(), ai.platforms()),
                rule.selfOperated() != null ? rule.selfOperated() : ai.selfOperated(),
                mergeLists(rule.colors(), ai.colors()),
                mergeLists(rule.brands(), ai.brands()),
                rule.ratingMin() != null ? rule.ratingMin() : ai.ratingMin(),
                StringUtils.defaultIfBlank(rule.sortBy(), ai.sortBy()),
                StringUtils.defaultIfBlank(rule.sortOrder(), StringUtils.defaultIfBlank(ai.sortOrder(), "desc")),
                StringUtils.defaultIfBlank(rule.keyword(), ai.keyword()),
                mergedAttrs,
                mergeLists(rule.excludeRoles(), ai.excludeRoles()),
                mergedCaps
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
