package com.visioncart.service.nlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.NlpParseRequest;
import com.visioncart.api.dto.NlpParseResult;
import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.ai.AiTraceService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SpringAiNlpService {
    private final RuleBasedNlpParser ruleParser;
    private final CacheKeyGenerator cacheKeyGenerator;
    private final NlpCacheManager cacheManager;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final AiTraceService traceService;

    public SpringAiNlpService(RuleBasedNlpParser ruleParser,
                              CacheKeyGenerator cacheKeyGenerator,
                              NlpCacheManager cacheManager,
                              ObjectProvider<ChatClient.Builder> chatClientBuilder,
                              ObjectMapper objectMapper,
                              AiTraceService traceService) {
        this.ruleParser = ruleParser;
        this.cacheKeyGenerator = cacheKeyGenerator;
        this.cacheManager = cacheManager;
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
    }

    public NlpParseResult parse(NlpParseRequest request) {
        RuleBasedNlpParser.ParsedFilter parsed = ruleParser.parse(request.userInput());
        String semanticKey = parsed.complete() ? cacheKeyGenerator.key(parsed.filter()) : cacheKeyGenerator.textKey(request.userInput());
        var cached = cacheManager.get(semanticKey);
        if (cached.isPresent()) {
            return new NlpParseResult(cached.get(), 0.99, true, "cache_hit");
        }

        if (parsed.complete()) {
            cacheManager.put(cacheKeyGenerator.key(parsed.filter()), parsed.filter());
            return new NlpParseResult(parsed.filter(), 0.92, false, "rule_primary");
        }

        Instant started = traceService.start("nlp.parse", Map.of("input", request.userInput()));
        try {
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                throw new IllegalStateException("Spring AI ChatClient.Builder is unavailable");
            }
            String json = builder.build()
                    .prompt()
                    .system(systemPrompt())
                    .user(userPrompt(request, parsed.filter()))
                    .call()
                    .content();
            SearchFilter filter = merge(parsed.filter(), parseAiJson(json));
            cacheManager.put(cacheKeyGenerator.key(filter), filter);
            traceService.finish("nlp.parse", started, "spring_ai_llm");
            return new NlpParseResult(filter, 0.95, false, "spring_ai_llm");
        } catch (Exception error) {
            traceService.fail("nlp.parse", started, error, "none");
            throw new IllegalStateException("LLM 语义解析失败: " + error.getMessage(), error);
        }
    }

    private String systemPrompt() {
        return """
                你是电商筛选意图解析器。将用户自然语言转为严格 JSON。
                只提取用户明确提到的筛选条件，不要编造品牌或价格。
                sort_by 只能是 price、sales、rating、reviews 或 null。
                平台只能是 京东、淘宝、天猫、拼多多。
                输出字段必须是 filter、confidence，不要输出 markdown。
                """;
    }

    private String userPrompt(NlpParseRequest request, SearchFilter preExtracted) {
        String productName = request.context() == null ? "" : StringUtils.defaultString(request.context().productName());
        String category = request.context() == null ? "" : StringUtils.defaultString(request.context().category());
        return """
                当前商品：%s
                类目：%s
                规则已提取：%s
                用户输入：%s
                输出格式：
                {"filter":{"price_range":{"min":null,"max":null},"platforms":[],"self_operated":null,"colors":[],"brands":[],"rating_min":null,"sort_by":null,"sort_order":"desc","keyword":null},"confidence":0.95}
                """.formatted(productName, category, cacheKeyGenerator.toJson(preExtracted), request.userInput());
    }

    private SearchFilter parseAiJson(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode filter = root.path("filter");
        JsonNode price = filter.path("price_range");
        return new SearchFilter(
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
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            for (String item : right) {
                if (!merged.contains(item)) {
                    merged.add(item);
                }
            }
        }
        return merged;
    }

    private List<String> list(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (!item.isNull() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
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
}
