package com.visioncart.service.nlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.SemanticActionPlan;
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

/**
 * LLM-first semantic planner service.
 * Calls the LLM with a prompt that directly requests SemanticActionPlan JSON output,
 * bypassing the old NlpParseResult intermediate format.
 *
 * Falls back to null on any failure — the caller (LlmSemanticPlanner) will then
 * fall back to the legacy NlpParseResult → SemanticActionPlan conversion path.
 */
@Service
public class SemanticPlannerModelService {

    private static final Logger log = LoggerFactory.getLogger(SemanticPlannerModelService.class);

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;

    public SemanticPlannerModelService(ObjectProvider<ChatClient.Builder> chatClientBuilder,
                                       ObjectMapper objectMapper,
                                       PromptLoader promptLoader) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
    }

    /**
     * Plan directly from LLM output as SemanticActionPlan.
     *
     * @return SemanticActionPlan or null if LLM unavailable / parse failure
     */
    public SemanticActionPlan plan(String sanitizedInput, String category,
                                   String productName, String historyText) {
        try {
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                log.debug("ChatClient.Builder unavailable, skipping direct semantic planning");
                return null;
            }

            String json = RetryPolicy.executeWithRetry(() ->
                            builder.build()
                                    .prompt()
                                    .system(systemPrompt())
                                    .user(userPrompt(productName, category, historyText, sanitizedInput))
                                    .call()
                                    .content(),
                    2, 1000L);

            return sanitize(parseSemanticPlanJson(json));
        } catch (Exception e) {
            log.warn("Direct semantic planning failed, will fall back to legacy path: {}", e.getMessage());
            return null;
        }
    }

    /** Max candidate limit for synchronous LLM Judge — prevents timeout on large pools. */
    private static final int MAX_SYNC_JUDGE_CANDIDATES = 12;

    /**
     * Sanitize LLM output: clamp judge limits and strip judge from inappropriate modes.
     * Prevents the LLM from sending 80+ products to the judge.
     */
    private SemanticActionPlan sanitize(SemanticActionPlan plan) {
        if (plan == null) return null;

        // PREFERENCE_RERANK must never carry a judge
        if ("PREFERENCE_RERANK".equals(plan.executionMode()) && plan.judge() != null) {
            log.info("Sanitize: stripping judge from PREFERENCE_RERANK plan");
            return stripJudge(plan);
        }

        // Clamp judge candidate/return limits
        if (plan.judge() != null && Boolean.TRUE.equals(plan.judge().required())) {
            var judge = plan.judge();
            int clampedCandidate = Math.min(
                    judge.candidateLimit() != null && judge.candidateLimit() > 0 ? judge.candidateLimit() : MAX_SYNC_JUDGE_CANDIDATES,
                    MAX_SYNC_JUDGE_CANDIDATES);
            int clampedReturn = Math.min(
                    judge.returnLimit() != null && judge.returnLimit() > 0 ? judge.returnLimit() : MAX_SYNC_JUDGE_CANDIDATES,
                    MAX_SYNC_JUDGE_CANDIDATES);

            if (clampedCandidate != judge.candidateLimit() || clampedReturn != judge.returnLimit()) {
                log.info("Sanitize: clamped judge limits candidate={}→{}, return={}→{}",
                        judge.candidateLimit(), clampedCandidate, judge.returnLimit(), clampedReturn);
            }

            var sanitizedJudge = new SemanticActionPlan.JudgePlan(
                    judge.required(), judge.userMeaning(),
                    judge.positiveSignals(), judge.negativeSignals(),
                    clampedCandidate, clampedReturn, judge.minScore());
            return new SemanticActionPlan(
                    plan.intent(), plan.executionMode(), plan.targetProduct(),
                    plan.hardFilters(), plan.semanticFilters(), plan.preferences(),
                    plan.exclusions(), plan.sort(), plan.zeroResultPolicy(),
                    plan.criteria(), plan.negativeCriteria(),
                    sanitizedJudge, plan.rankingGoal(), plan.resultStrategy());
        }

        return plan;
    }

    private SemanticActionPlan stripJudge(SemanticActionPlan plan) {
        return new SemanticActionPlan(
                plan.intent(), plan.executionMode(), plan.targetProduct(),
                plan.hardFilters(), plan.semanticFilters(), plan.preferences(),
                plan.exclusions(), plan.sort(), plan.zeroResultPolicy(),
                plan.criteria(), plan.negativeCriteria(),
                null, plan.rankingGoal(), plan.resultStrategy());
    }

    private String systemPrompt() {
        return promptLoader.getPrompt("semantic-planner-system");
    }

    private String userPrompt(String productName, String category,
                              String historyText, String sanitizedInput) {
        return promptLoader.getPrompt("semantic-planner-user").formatted(
                StringUtils.defaultString(productName),
                StringUtils.defaultString(category),
                StringUtils.defaultIfBlank(historyText, "（无）"),
                sanitizedInput);
    }

    /**
     * Parse LLM JSON output into SemanticActionPlan.
     */
    private SemanticActionPlan parseSemanticPlanJson(String raw) throws Exception {
        String jsonText = extractJsonObject(raw);
        JsonNode root = objectMapper.readTree(jsonText);

        String intent = textOrNull(root.path("intent"));
        if (intent == null) intent = "filter_current_results";

        String executionMode = textOrNull(root.path("execution_mode"));
        if (executionMode == null) executionMode = "STRICT_FILTER";

        String zeroResultPolicy = textOrNull(root.path("zero_result_policy"));
        if (zeroResultPolicy == null) zeroResultPolicy = "KEEP_PREVIOUS_RESULTS";

        // Parse hard_filters
        List<SemanticActionPlan.HardFilter> hardFilters = parseHardFilters(root.path("hard_filters"));

        // Parse semantic_filters
        List<SemanticActionPlan.SemanticFilter> semanticFilters = parseSemanticFilters(root.path("semantic_filters"));

        // Parse preferences
        List<SemanticActionPlan.PreferenceRule> preferences = parsePreferences(root.path("preferences"));

        // Parse exclusions
        List<SemanticActionPlan.ExclusionRule> exclusions = parseExclusions(root.path("exclusions"));

        // Parse sort
        SemanticActionPlan.SortRule sortRule = parseSort(root.path("sort"));

        List<SemanticActionPlan.DynamicCriterion> criteria = parseCriteria(root.path("criteria"));
        List<SemanticActionPlan.NegativeCriterion> negativeCriteria = parseNegativeCriteria(
                pathAny(root, "negative_criteria", "negativeCriteria"));
        SemanticActionPlan.JudgePlan judge = parseJudge(root.path("judge"));
        String rankingGoal = textOrNull(pathAny(root, "ranking_goal", "rankingGoal"));
        SemanticActionPlan.ResultStrategy resultStrategy = parseResultStrategy(
                pathAny(root, "result_strategy", "resultStrategy"));

        return new SemanticActionPlan(
                intent,
                executionMode,
                null, // targetProduct — resolved from context by caller
                hardFilters.isEmpty() ? null : hardFilters,
                semanticFilters.isEmpty() ? null : semanticFilters,
                preferences.isEmpty() ? null : preferences,
                exclusions.isEmpty() ? null : exclusions,
                sortRule,
                zeroResultPolicy,
                criteria.isEmpty() ? null : criteria,
                negativeCriteria.isEmpty() ? null : negativeCriteria,
                judge,
                rankingGoal,
                resultStrategy
        );
    }

    private List<SemanticActionPlan.HardFilter> parseHardFilters(JsonNode node) {
        List<SemanticActionPlan.HardFilter> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;

        for (JsonNode hf : node) {
            String field = textOrNull(hf.path("field"));
            String operator = textOrNull(hf.path("operator"));
            if (field == null) continue;

            JsonNode valueNode = hf.path("value");
            Object value = extractValue(valueNode);

            result.add(new SemanticActionPlan.HardFilter(field, operator, value));
        }
        return result;
    }

    private List<SemanticActionPlan.SemanticFilter> parseSemanticFilters(JsonNode node) {
        List<SemanticActionPlan.SemanticFilter> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;

        for (JsonNode sf : node) {
            String code = textOrNull(sf.path("code"));
            if (code == null) continue;

            String userMeaning = textOrNull(sf.path("userMeaning"));
            String matchLogic = textOrNull(sf.path("matchLogic"));
            if (matchLogic == null) matchLogic = "EVIDENCE_OR_SCORE";

            // The LLM doesn't output evidence rules — those are built by LlmSemanticPlanner
            // based on the capability code. So we create a SemanticFilter with empty evidence
            // and let the planner fill in the domain-specific evidence rules.
            result.add(new SemanticActionPlan.SemanticFilter(
                    code, userMeaning, matchLogic, null, null, "KEEP_AS_SECONDARY"));
        }
        return result;
    }

    private List<SemanticActionPlan.PreferenceRule> parsePreferences(JsonNode node) {
        List<SemanticActionPlan.PreferenceRule> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;

        for (JsonNode pref : node) {
            String code = textOrNull(pref.path("code"));
            if (code == null) continue;

            String userMeaning = textOrNull(pref.path("userMeaning"));
            double weight = pref.has("weight") ? pref.path("weight").asDouble(0.7) : 0.7;

            result.add(new SemanticActionPlan.PreferenceRule(code, userMeaning, weight));
        }
        return result;
    }

    private List<SemanticActionPlan.ExclusionRule> parseExclusions(JsonNode node) {
        List<SemanticActionPlan.ExclusionRule> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;

        for (JsonNode ex : node) {
            String code = textOrNull(ex.path("code"));
            if (code == null) continue;

            String userMeaning = textOrNull(ex.path("userMeaning"));
            List<String> keywords = stringList(ex.path("keywords"));
            List<String> roles = stringList(ex.path("roles"));

            result.add(new SemanticActionPlan.ExclusionRule(code, userMeaning, keywords, roles));
        }
        return result;
    }

    private SemanticActionPlan.SortRule parseSort(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;

        String field = textOrNull(node.path("field"));
        String order = textOrNull(node.path("order"));
        if (field == null) return null;

        return new SemanticActionPlan.SortRule(field, order != null ? order : "desc");
    }

    private List<SemanticActionPlan.DynamicCriterion> parseCriteria(JsonNode node) {
        List<SemanticActionPlan.DynamicCriterion> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;

        for (JsonNode item : node) {
            String name = textOrNull(item.path("name"));
            if (name == null) continue;

            String type = textOrNull(item.path("type"));
            if (type == null) type = "evidence_or_score";
            Boolean required = booleanOrNull(item.path("required"));
            Double weight = doubleOrNull(item.path("weight"));
            List<SemanticActionPlan.CriterionSignal> signals = parseSignals(item.path("signals"));
            String unknownPolicy = textOrNull(pathAny(item, "unknown_policy", "unknownPolicy"));
            String userMeaning = textOrNull(pathAny(item, "user_meaning", "userMeaning"));

            result.add(new SemanticActionPlan.DynamicCriterion(
                    name, type, required, weight,
                    signals.isEmpty() ? null : signals,
                    unknownPolicy,
                    userMeaning));
        }
        return result;
    }

    private List<SemanticActionPlan.NegativeCriterion> parseNegativeCriteria(JsonNode node) {
        List<SemanticActionPlan.NegativeCriterion> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;

        for (JsonNode item : node) {
            String name = textOrNull(item.path("name"));
            if (name == null) continue;

            String action = textOrNull(item.path("action"));
            if (action == null) action = "reject";
            List<SemanticActionPlan.CriterionSignal> signals = parseSignals(item.path("signals"));
            String userMeaning = textOrNull(pathAny(item, "user_meaning", "userMeaning"));

            result.add(new SemanticActionPlan.NegativeCriterion(
                    name, action, signals.isEmpty() ? null : signals, userMeaning));
        }
        return result;
    }

    private List<SemanticActionPlan.CriterionSignal> parseSignals(JsonNode node) {
        List<SemanticActionPlan.CriterionSignal> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;

        for (JsonNode signal : node) {
            String kind = textOrNull(signal.path("kind"));
            String field = textOrNull(signal.path("field"));
            String operator = textOrNull(signal.path("operator"));
            Object value = extractValue(signal.path("value"));
            List<String> values = stringList(signal.path("values"));
            String formula = textOrNull(signal.path("formula"));
            Double score = doubleOrNull(signal.path("score"));
            String description = textOrNull(signal.path("description"));
            String bucket = textOrNull(signal.path("bucket"));

            if (kind == null && field == null && operator == null && value == null && values.isEmpty()) {
                continue;
            }
            result.add(new SemanticActionPlan.CriterionSignal(
                    kind, field, operator, value,
                    values.isEmpty() ? null : values,
                    formula, score, description, bucket));
        }
        return result;
    }

    private SemanticActionPlan.JudgePlan parseJudge(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;

        Boolean required = booleanOrNull(node.path("required"));
        String userMeaning = textOrNull(pathAny(node, "user_meaning", "userMeaning"));
        List<String> positiveSignals = stringList(pathAny(node, "positive_signals", "positiveSignals"));
        List<String> negativeSignals = stringList(pathAny(node, "negative_signals", "negativeSignals"));
        Integer candidateLimit = intOrNull(pathAny(node, "candidate_limit", "candidateLimit"));
        Integer returnLimit = intOrNull(pathAny(node, "return_limit", "returnLimit"));
        Double minScore = doubleOrNull(pathAny(node, "min_score", "minScore"));

        boolean empty = required == null
                && userMeaning == null
                && positiveSignals.isEmpty()
                && negativeSignals.isEmpty()
                && candidateLimit == null
                && returnLimit == null
                && minScore == null;
        if (empty) return null;

        return new SemanticActionPlan.JudgePlan(
                required,
                userMeaning,
                positiveSignals.isEmpty() ? null : positiveSignals,
                negativeSignals.isEmpty() ? null : negativeSignals,
                candidateLimit,
                returnLimit,
                minScore);
    }

    private SemanticActionPlan.ResultStrategy parseResultStrategy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;

        String unknownPolicy = textOrNull(pathAny(node, "unknown_policy", "unknownPolicy"));
        String fallbackPolicy = textOrNull(pathAny(node, "fallback_policy", "fallbackPolicy"));
        Boolean preservePreviousOnEmpty = booleanOrNull(
                pathAny(node, "preserve_previous_on_empty", "preservePreviousOnEmpty"));
        if (unknownPolicy == null && fallbackPolicy == null && preservePreviousOnEmpty == null) {
            return null;
        }
        return new SemanticActionPlan.ResultStrategy(unknownPolicy, fallbackPolicy, preservePreviousOnEmpty);
    }

    /**
     * Extract a JSON object from LLM response, stripping markdown fences.
     */
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

    private Object extractValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.doubleValue();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            List<String> list = new ArrayList<>();
            node.forEach(item -> {
                if (!item.isNull() && !item.asText().isBlank()) list.add(item.asText());
            });
            return list;
        }
        return node.asText();
    }

    private List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;
        node.forEach(item -> {
            if (!item.isNull() && !item.asText().isBlank()) result.add(item.asText());
        });
        return result;
    }

    private JsonNode pathAny(JsonNode node, String... names) {
        if (node == null || names == null || names.length == 0) {
            return null;
        }
        for (String name : names) {
            if (node.has(name)) {
                return node.path(name);
            }
        }
        return node.path(names[0]);
    }

    private Boolean booleanOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isTextual()) {
            String text = node.asText();
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        return null;
    }

    private Double doubleOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.asDouble();
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.asInt();
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()
                ? null : node.asText();
    }
}
