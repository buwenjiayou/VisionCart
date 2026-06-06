package com.visioncart.service.filter.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SemanticActionPlan;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.service.ai.PromptLoader;
import com.visioncart.service.ai.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LlmProductJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmProductJudge.class);
    private static final Set<String> ALLOWED_BUCKETS = Set.of(
            "GOOD_MATCH", "WEAK_MATCH", "RISKY_MATCH", "REJECT");

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final VisionCartProperties properties;

    public LlmProductJudge(ObjectProvider<ChatClient.Builder> chatClientBuilder,
                           ObjectMapper objectMapper,
                           PromptLoader promptLoader,
                           VisionCartProperties properties) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.properties = properties;
    }

    public List<JudgeScore> judge(String userQuery,
                                  String rankingGoal,
                                  SemanticActionPlan.JudgePlan judgePlan,
                                  List<ProductCard> products) {
        if (!properties.getAi().isSemanticJudgeEnabled()
                || judgePlan == null
                || !Boolean.TRUE.equals(judgePlan.required())
                || products == null
                || products.isEmpty()) {
            return List.of();
        }

        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            log.debug("ChatClient unavailable, skip LLM product judge");
            return List.of();
        }

        int candidateLimit = configuredLimit(judgePlan.candidateLimit(),
                properties.getAi().getSemanticJudgeCandidateLimit(), 80);
        List<ProductCard> limited = products.stream().limit(candidateLimit).toList();
        Map<String, ProductCard> productById = limited.stream()
                .filter(p -> p.id() != null)
                .collect(Collectors.toMap(ProductCard::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        if (productById.isEmpty()) {
            return List.of();
        }

        try {
            String productsJson = objectMapper.writeValueAsString(
                    limited.stream().map(ProductSummary::from).toList());
            String systemPrompt = promptLoader.getPrompt("product-judge-system");
            String userPrompt = promptLoader.getPrompt("product-judge-user").formatted(
                    defaultString(firstNonBlank(judgePlan.userMeaning(), userQuery)),
                    defaultString(rankingGoal),
                    objectMapper.writeValueAsString(safeList(judgePlan.positiveSignals())),
                    objectMapper.writeValueAsString(safeList(judgePlan.negativeSignals())),
                    productsJson);

            String raw = RetryPolicy.executeWithRetry(() ->
                            builder.build()
                                    .prompt()
                                    .system(systemPrompt)
                                    .user(userPrompt)
                                    .call()
                                    .content(),
                    properties.getAi().getNlpRetryCount(),
                    properties.getAi().getNlpRetryBaseDelayMs());

            return parseResults(raw, productById.keySet());
        } catch (Exception e) {
            log.warn("LLM product judge failed, falling back to local preference scoring: {}", e.getMessage());
            return List.of();
        }
    }

    List<JudgeScore> parseResults(String raw, Set<String> allowedProductIds) throws Exception {
        String jsonText = extractJsonObject(raw);
        JsonNode root = objectMapper.readTree(jsonText);
        JsonNode resultsNode = root.path("results");
        if (!resultsNode.isArray()) {
            return List.of();
        }

        Map<String, JudgeScore> dedup = new LinkedHashMap<>();
        for (JsonNode item : resultsNode) {
            String productId = textOrNull(pathAny(item, "product_id", "productId"));
            if (productId == null || !allowedProductIds.contains(productId)) {
                continue;
            }

            double score = clampScore(item.path("score").asDouble(Double.NaN));
            if (Double.isNaN(score)) {
                continue;
            }
            String bucket = textOrNull(item.path("bucket"));
            if (bucket == null || !ALLOWED_BUCKETS.contains(bucket)) {
                bucket = score >= 0.75 ? "GOOD_MATCH" : score >= 0.35 ? "WEAK_MATCH" : "REJECT";
            }

            JudgeScore parsed = new JudgeScore(
                    productId,
                    score,
                    bucket,
                    limitedStringList(pathAny(item, "reasons", "reason"), 3, 80),
                    limitedStringList(pathAny(item, "risk_flags", "riskFlags"), 5, 50));
            dedup.merge(productId, parsed, (a, b) -> b.score() > a.score() ? b : a);
        }
        return new ArrayList<>(dedup.values());
    }

    private int configuredLimit(Integer planValue, int configuredValue, int fallback) {
        int value = planValue != null && planValue > 0 ? planValue : configuredValue;
        if (value <= 0) value = fallback;
        return Math.min(Math.max(value, 1), 200);
    }

    private double clampScore(double score) {
        if (Double.isNaN(score)) return Double.NaN;
        return Math.max(0.0, Math.min(1.0, score));
    }

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

    private JsonNode pathAny(JsonNode node, String... names) {
        if (node == null || names == null || names.length == 0) return null;
        for (String name : names) {
            if (node.has(name)) return node.path(name);
        }
        return node.path(names[0]);
    }

    private List<String> limitedStringList(JsonNode node, int maxItems, int maxChars) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                addLimited(values, item.asText(null), maxItems, maxChars);
            }
        } else {
            addLimited(values, node.asText(null), maxItems, maxChars);
        }
        return values;
    }

    private void addLimited(List<String> values, String value, int maxItems, int maxChars) {
        if (values.size() >= maxItems || value == null || value.isBlank()) return;
        String trimmed = value.trim();
        values.add(trimmed.length() > maxChars ? trimmed.substring(0, maxChars) : trimmed);
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()
                ? null : node.asText();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    public int returnLimit(SemanticActionPlan.JudgePlan judgePlan) {
        int value = judgePlan != null && judgePlan.returnLimit() != null && judgePlan.returnLimit() > 0
                ? judgePlan.returnLimit()
                : properties.getAi().getSemanticJudgeReturnLimit();
        if (value <= 0) value = 50;
        return Math.min(Math.max(value, 1), 100);
    }

    public double minScore(SemanticActionPlan.JudgePlan judgePlan) {
        Double value = judgePlan != null ? judgePlan.minScore() : null;
        double configured = value != null ? value : properties.getAi().getSemanticJudgeMinScore();
        return Math.max(0.0, Math.min(1.0, configured));
    }

    public record JudgeScore(
            String productId,
            double score,
            String bucket,
            List<String> reasons,
            List<String> riskFlags
    ) {}

    private record ProductSummary(
            String id,
            String title,
            BigDecimal price,
            String platform,
            String brand,
            String shopName,
            double rating,
            long sales,
            List<String> tags,
            String mainCategoryCode,
            String productRole
    ) {
        static ProductSummary from(ProductCard product) {
            return new ProductSummary(
                    product.id(),
                    product.title(),
                    product.price(),
                    product.platform(),
                    product.brand(),
                    product.shopName(),
                    product.rating(),
                    product.sales(),
                    product.tags(),
                    product.mainCategoryCode(),
                    product.productRole());
        }
    }
}
