package com.visioncart.service.suggestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.service.ai.AiTraceService;
import com.visioncart.service.ai.PromptLoader;
import com.visioncart.service.ai.RetryPolicy;
import com.visioncart.service.metrics.PerformanceMetricsService;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 导购分析卡服务。
 * <p>
 * 规则/统计决定"能做什么"（生成 facts），AI 负责"怎么说"（改写文案）。
 * AI 不可用时静默降级为规则文案。
 */
@Service
public class DeepSuggestionService {
    private static final Logger log = LoggerFactory.getLogger(DeepSuggestionService.class);

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final AiTraceService traceService;
    private final PromptLoader promptLoader;
    private final PerformanceMetricsService metricsService;

    public DeepSuggestionService(ObjectProvider<ChatClient.Builder> chatClientBuilder,
                                  ObjectMapper objectMapper,
                                  AiTraceService traceService,
                                  PromptLoader promptLoader,
                                  PerformanceMetricsService metricsService) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
        this.promptLoader = promptLoader;
        this.metricsService = metricsService;
    }

    /**
     * 生成最多 2 张 AI 导购分析卡。
     */
    public List<SuggestionCard> insightCards(String clientType,
                                              List<ProductCard> products,
                                              Map<String, String> attributes,
                                              SearchFilter currentFilter) {
        int maxCards = "overlay".equalsIgnoreCase(clientType) ? 1 : 2;
        List<ProductCard> safe = safeProducts(products);
        if (safe.size() < 3) {
            return List.of();
        }

        // Step 1: 规则生成 facts
        List<GuideInsightFact> facts = generateFacts(safe, attributes, currentFilter);
        if (facts.isEmpty()) {
            return List.of();
        }

        // Step 2: 尝试 AI 改写文案
        Timer.Sample timer = metricsService.startDeepSuggestionTimer();
        List<SuggestionCard> cards = tryAiRewrite(safe, facts, maxCards);

        // Step 3: AI 失败时降级为规则文案
        if (cards == null || cards.isEmpty()) {
            metricsService.recordDeepSuggestionFallback();
            metricsService.stopDeepSuggestionTimer(timer, "fallback");
            cards = fallbackCards(facts, maxCards);
        } else {
            metricsService.recordDeepSuggestionGenerated();
            metricsService.stopDeepSuggestionTimer(timer, "ai");
        }

        return cards;
    }

    // ==================== Fact Generation ====================

    List<GuideInsightFact> generateFacts(List<ProductCard> products,
                                          Map<String, String> attributes,
                                          SearchFilter currentFilter) {
        List<GuideInsightFact> facts = new ArrayList<>();

        priceBandFact(products).ifPresent(facts::add);
        accessoryRiskFact(products, attributes).ifPresent(facts::add);
        platformValueFact(products).ifPresent(facts::add);

        // Fallback: 没有强事实时生成一张通用 AI 导购引导卡
        if (facts.isEmpty()) {
            generalGuidanceFact().ifPresent(facts::add);
        }

        return facts;
    }

    /**
     * 通用 AI 导购引导卡：没有强事实时兜底，引导用户进一步筛选。
     */
    Optional<GuideInsightFact> generalGuidanceFact() {
        return Optional.of(new GuideInsightFact(
                "insight_general_guidance",
                "general_guidance",
                "AI 导购分析",
                "已根据价格、平台和商品类型帮你整理了结果。可以继续输入条件进一步筛选。",
                "focus_nlp_input",
                "继续筛选",
                Map.of()
        ));
    }

    /**
     * 价格区间分析：P25～P75 主流价格区间
     */
    Optional<GuideInsightFact> priceBandFact(List<ProductCard> products) {
        List<BigDecimal> prices = products.stream()
                .map(ProductCard::price)
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .sorted()
                .toList();
        if (prices.size() < 6) {
            return Optional.empty();
        }

        BigDecimal p25 = percentile(prices, 25);
        BigDecimal p75 = percentile(prices, 75);
        if (p25.compareTo(BigDecimal.ZERO) <= 0 || p75.compareTo(p25) <= 0) {
            return Optional.empty();
        }

        long countInRange = prices.stream()
                .filter(p -> p.compareTo(p25) >= 0 && p.compareTo(p75) <= 0)
                .count();

        // 区间内商品占比过低则不生成
        if (countInRange < prices.size() * 0.3) {
            return Optional.empty();
        }

        int minInt = p25.setScale(0, RoundingMode.FLOOR).intValue();
        int maxInt = p75.setScale(0, RoundingMode.CEILING).intValue();

        return Optional.of(new GuideInsightFact(
                "insight_price_band",
                "price_band",
                "主流价格区间",
                countInRange + " 件商品集中在 ¥" + minInt + "～¥" + maxInt,
                "filter_price_band:" + minInt + ":" + maxInt,
                "只看该区间",
                Map.of("min", minInt, "max", maxInt, "count", countInRange)
        ));
    }

    /**
     * 配件风险分析：productRole 为 accessory/case/part/consumable 的商品。
     * 如果搜索意图本身就是配件（如"手机壳""充电线"），则不触发提醒。
     */
    Optional<GuideInsightFact> accessoryRiskFact(List<ProductCard> products, Map<String, String> attributes) {
        // 如果搜索关键词本身就是配件类词汇，不提醒
        if (isAccessorySearch(attributes)) {
            return Optional.empty();
        }

        Set<String> accessoryRoles = Set.of("accessory", "case", "part", "consumable", "storage");
        long suspectCount = products.stream()
                .filter(p -> accessoryRoles.contains(p.productRole().toLowerCase(Locale.ROOT)))
                .count();
        if (suspectCount < 3) {
            return Optional.empty();
        }

        return Optional.of(new GuideInsightFact(
                "insight_accessory_risk",
                "accessory_risk",
                "疑似配件提醒",
                "检测到 " + suspectCount + " 件疑似配件商品",
                "filter_main_product",
                "排除配件",
                Map.of("suspectCount", suspectCount)
        ));
    }

    /**
     * 平台价差分析：最低平台均价比最高平台低 15% 以上
     */
    Optional<GuideInsightFact> platformValueFact(List<ProductCard> products) {
        Map<String, List<BigDecimal>> byPlatform = products.stream()
                .filter(p -> StringUtils.isNotBlank(p.platform()) && p.price() != null && p.price().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.groupingBy(ProductCard::platform, LinkedHashMap::new, Collectors.mapping(ProductCard::price, Collectors.toList())));

        if (byPlatform.size() < 2) {
            return Optional.empty();
        }

        Map<String, BigDecimal> avgByPlatform = new LinkedHashMap<>();
        byPlatform.forEach((platform, prices) -> {
            BigDecimal sum = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            avgByPlatform.put(platform, sum.divide(BigDecimal.valueOf(prices.size()), 2, RoundingMode.HALF_UP));
        });

        Map.Entry<String, BigDecimal> cheapest = avgByPlatform.entrySet().stream().min(Map.Entry.comparingByValue()).orElse(null);
        Map.Entry<String, BigDecimal> mostExpensive = avgByPlatform.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);

        if (cheapest == null || mostExpensive == null || cheapest.getKey().equals(mostExpensive.getKey())) {
            return Optional.empty();
        }

        BigDecimal diff = mostExpensive.getValue().subtract(cheapest.getValue());
        BigDecimal ratio = diff.divide(mostExpensive.getValue(), 2, RoundingMode.HALF_UP);

        if (ratio.compareTo(BigDecimal.valueOf(0.15)) < 0) {
            return Optional.empty();
        }

        int percent = ratio.multiply(BigDecimal.valueOf(100)).intValue();

        return Optional.of(new GuideInsightFact(
                "insight_platform_value",
                "platform_value",
                "平台价差明显",
                cheapest.getKey() + "当前最低价比" + mostExpensive.getKey() + "低约" + percent + "%",
                "filter_platform:" + cheapest.getKey(),
                "只看该平台",
                Map.of("cheapestPlatform", cheapest.getKey(), "diffPercent", percent)
        ));
    }

    // ==================== AI Rewriting ====================

    private List<SuggestionCard> tryAiRewrite(List<ProductCard> products, List<GuideInsightFact> facts, int maxCards) {
        try {
            ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
            if (builder == null) {
                log.debug("ChatClient unavailable, falling back to rule-based insight cards");
                return null;
            }

            String productName = guessProductName(products);
            String factsJson = objectMapper.writeValueAsString(facts);
            String systemPrompt = promptLoader.getPrompt("guide-insight-system");
            String userTemplate = promptLoader.getPrompt("guide-insight-user");
            String userPrompt = userTemplate
                    .replace("{{product_name}}", productName)
                    .replace("{{facts_json}}", factsJson);

            String traceId = traceService.start("deep-suggestion", Map.of("productName", productName));

            String json = RetryPolicy.executeWithRetry(() ->
                    builder.build()
                            .prompt()
                            .system(systemPrompt)
                            .user(userPrompt)
                            .call()
                            .content(),
                    2, 1000L);

            traceService.finish("deep-suggestion", traceId, "ok");
            return validateAiOutput(json, facts, maxCards);
        } catch (Exception e) {
            log.warn("AI insight generation failed, falling back to rules: {}", e.getMessage());
            try { traceService.fail("deep-suggestion", null, e, "fallback_rules"); } catch (Exception ignored) {}
            return null;
        }
    }

    /**
     * 校验 AI 输出：
     * - id 必须存在于 facts
     * - 数量 <= maxCards
     * - title <= 30 字符，reason <= 100 字符，actionLabel <= 12 字符
     * - action 使用 fact 的 action，不使用 AI 输出的 action
     */
    List<SuggestionCard> validateAiOutput(String json, List<GuideInsightFact> facts, int maxCards) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode cardsNode = root.get("cards");
            if (cardsNode == null || !cardsNode.isArray()) {
                log.warn("AI output missing 'cards' array");
                return null;
            }

            Map<String, GuideInsightFact> factMap = facts.stream()
                    .collect(Collectors.toMap(GuideInsightFact::id, f -> f));

            List<SuggestionCard> result = new ArrayList<>();
            for (JsonNode cardNode : cardsNode) {
                if (result.size() >= maxCards) break;

                String id = textOrNull(cardNode, "id");
                if (id == null || !factMap.containsKey(id)) {
                    log.warn("AI output invalid id: {}", id);
                    continue;
                }

                GuideInsightFact fact = factMap.get(id);
                String title = clamp(textOrNull(cardNode, "title"), 30);
                String reason = clamp(textOrNull(cardNode, "reason"), 100);
                String actionLabel = clamp(textOrNull(cardNode, "actionLabel"), 12);

                if (title == null || title.isBlank()) {
                    title = fact.title();
                }
                if (reason == null || reason.isBlank()) {
                    reason = fact.evidence();
                }
                if (actionLabel == null || actionLabel.isBlank()) {
                    actionLabel = fact.actionLabel();
                }

                // 校验 AI 文案中的数字和平台名是否来自 fact，不匹配则降级为规则文案
                if (!isAiTextSupportedByFact(title, fact) || !isAiTextSupportedByFact(reason, fact)) {
                    log.info("AI text for '{}' contains unsupported numbers/platforms, using fallback text", id);
                    title = fact.title();
                    reason = fact.evidence();
                    actionLabel = fact.actionLabel();
                }

                // action 必须来自 fact，不使用 AI 输出
                result.add(new SuggestionCard(
                        fact.id(), title, reason, "insight",
                        fact.action(), 60, "AI 导购分析",
                        reason, null, actionLabel, "insight"
                ));
            }
            return result.isEmpty() ? null : result;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse AI insight output: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Fallback ====================

    List<SuggestionCard> fallbackCards(List<GuideInsightFact> facts, int maxCards) {
        return facts.stream()
                .limit(maxCards)
                .map(fact -> new SuggestionCard(
                        fact.id(), fact.title(), fact.evidence(), "insight",
                        fact.action(), 60, "AI 导购分析",
                        fact.evidence(), null, fact.actionLabel(), "insight"
                ))
                .toList();
    }

    // ==================== Helpers ====================

    private static final Set<String> ACCESSORY_KEYWORDS = Set.of(
            "手机壳", "手机套", "保护壳", "保护套", "手机膜", "钢化膜", "屏幕膜",
            "充电线", "数据线", "充电器", "充电宝", "移动电源", "耳机套", "耳机壳",
            "杯套", "杯盖", "滤网", "支架", "底座", "收纳袋", "收纳盒",
            "case", "cover", "charger", "cable", "screen protector", "film",
            "accessory", "配件", "附件", "耗材", "贴膜", "壳"
    );

    /**
     * 判断搜索意图是否本身就是配件类商品。
     * 通过检查搜索关键词/品类是否包含配件相关词汇。
     */
    private boolean isAccessorySearch(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return false;
        // 检查关键词、品类等字段
        for (String key : List.of("关键词", "keyword", "keywords", "类目", "category")) {
            String value = attributes.get(key);
            if (value != null) {
                String lower = value.toLowerCase(Locale.ROOT);
                for (String kw : ACCESSORY_KEYWORDS) {
                    if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static String guessProductName(List<ProductCard> products) {
        return products.stream()
                .findFirst()
                .map(ProductCard::title)
                .orElse("商品");
    }

    private List<ProductCard> safeProducts(List<ProductCard> products) {
        if (products == null) return List.of();
        return products.stream().filter(Objects::nonNull).toList();
    }

    private static BigDecimal percentile(List<BigDecimal> sorted, int p) {
        if (sorted.isEmpty()) return BigDecimal.ZERO;
        double index = (p / 100.0) * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return sorted.get(lower);
        BigDecimal lowerVal = sorted.get(lower);
        BigDecimal upperVal = sorted.get(upper);
        BigDecimal fraction = BigDecimal.valueOf(index - lower);
        return lowerVal.add(upperVal.subtract(lowerVal).multiply(fraction));
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull()) ? n.asText() : null;
    }

    private String clamp(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    // ==================== AI Output Fact-Checking ====================

    private static final Pattern NUMBER_PATTERN = Pattern.compile("¥?\\d+(\\.\\d+)?%?");
    private static final Set<String> PLATFORM_NAMES = Set.of(
            "拼多多", "淘宝", "天猫", "京东", "eBay", "pdd", "taobao", "tmall", "jd"
    );

    /**
     * 校验 AI 文案中的数字和平台名是否来自 fact 的 metrics。
     * 如果 AI 编造了 fact 中不存在的数字或平台，返回 false。
     */
    boolean isAiTextSupportedByFact(String aiText, GuideInsightFact fact) {
        if (aiText == null || fact == null || fact.metrics() == null) {
            return aiText == null; // null text is fine, empty metrics means strict check
        }

        // 收集 fact.metrics 中的所有数值
        Set<String> factNumbers = new HashSet<>();
        for (Object value : fact.metrics().values()) {
            if (value instanceof Number n) {
                factNumbers.add(String.valueOf(n.intValue()));
                factNumbers.add(String.valueOf(n.doubleValue()));
            }
        }
        // 也把 evidence 中的数字加入可信集合
        extractNumbers(fact.evidence()).forEach(factNumbers::add);

        // 校验 AI 文案中的数字是否在可信集合中
        List<String> aiNumbers = extractNumbers(aiText);
        for (String num : aiNumbers) {
            boolean found = false;
            for (String factNum : factNumbers) {
                if (num.equals(factNum) || factNum.startsWith(num + ".") || num.startsWith(factNum + ".")) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                log.debug("AI text contains unsupported number '{}' (fact numbers: {})", num, factNumbers);
                return false;
            }
        }

        // 校验 AI 文案中的平台名是否在 fact.metrics 中
        Set<String> factPlatforms = new HashSet<>();
        for (Object value : fact.metrics().values()) {
            if (value instanceof String s && isPlatformName(s)) {
                factPlatforms.add(s.toLowerCase(Locale.ROOT));
            }
        }
        for (String platform : PLATFORM_NAMES) {
            if (aiText.toLowerCase(Locale.ROOT).contains(platform.toLowerCase(Locale.ROOT))) {
                if (factPlatforms.isEmpty() || factPlatforms.stream().noneMatch(fp -> fp.contains(platform.toLowerCase(Locale.ROOT)))) {
                    log.debug("AI text contains unsupported platform '{}' (fact platforms: {})", platform, factPlatforms);
                    return false;
                }
            }
        }

        return true;
    }

    private List<String> extractNumbers(String text) {
        if (text == null) return List.of();
        List<String> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            String match = matcher.group().replace("¥", "").replace("%", "");
            if (!match.isEmpty()) {
                numbers.add(match);
            }
        }
        return numbers;
    }

    private boolean isPlatformName(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return PLATFORM_NAMES.stream().anyMatch(p -> lower.contains(p.toLowerCase(Locale.ROOT)));
    }
}
