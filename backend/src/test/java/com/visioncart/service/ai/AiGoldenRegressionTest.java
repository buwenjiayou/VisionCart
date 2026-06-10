package com.visioncart.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.NlpParseRequest;
import com.visioncart.api.dto.NlpParseResult;
import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.RecognitionCandidate;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SemanticActionPlan;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.service.filter.semantic.LlmProductJudge;
import com.visioncart.service.metrics.PerformanceMetricsService;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.nlp.RuleBasedNlpParser;
import com.visioncart.service.nlp.SemanticPlannerModelService;
import com.visioncart.service.nlp.SpringAiNlpService;
import com.visioncart.service.recognition.AliyunQwenVisionClient;
import com.visioncart.service.recognition.DoubaoVisionClient;
import com.visioncart.service.suggestion.DeepSuggestionService;
import com.visioncart.service.suggestion.GuideInsightFact;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiGoldenRegressionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void offlineAiGoldenCasesPassAndWriteQualityReport() throws Exception {
        JsonNode root;
        try (var input = getClass().getClassLoader().getResourceAsStream("ai-golden/cases.json")) {
            assertThat(input).as("ai-golden/cases.json").isNotNull();
            root = objectMapper.readTree(input);
        }

        List<CaseOutcome> outcomes = new ArrayList<>();
        for (JsonNode testCase : root.path("cases")) {
            String id = requiredText(testCase, "id");
            String type = requiredText(testCase, "type");
            String promptName = requiredText(testCase, "promptName");
            String promptVersion = requiredText(testCase, "promptVersion");
            boolean required = !testCase.has("required") || testCase.path("required").asBoolean(true);
            try {
                Map<String, Object> details = runCase(testCase);
                outcomes.add(new CaseOutcome(id, type, promptName, promptVersion, required, true, null, details));
            } catch (Throwable error) {
                outcomes.add(new CaseOutcome(id, type, promptName, promptVersion, required, false,
                        error.getMessage(), Map.of()));
            }
        }

        writeReport(outcomes);

        List<String> requiredFailures = outcomes.stream()
                .filter(outcome -> outcome.required() && !outcome.passed())
                .map(outcome -> outcome.id() + ": " + outcome.failure())
                .toList();
        assertThat(requiredFailures).as("required AI golden failures").isEmpty();
    }

    private Map<String, Object> runCase(JsonNode testCase) throws Exception {
        return switch (requiredText(testCase, "type")) {
            case "semantic_planner" -> runSemanticPlannerCase(testCase);
            case "legacy_nlp" -> runLegacyNlpCase(testCase);
            case "qwen_flash_detection" -> runQwenFlashDetectionCase(testCase);
            case "product_judge" -> runProductJudgeCase(testCase);
            case "guide_insight" -> runGuideInsightCase(testCase);
            default -> throw new IllegalArgumentException("Unknown AI golden case type: " + requiredText(testCase, "type"));
        };
    }

    private Map<String, Object> runSemanticPlannerCase(JsonNode testCase) {
        SemanticPlannerModelService service = new SemanticPlannerModelService(
                chatClientProvider(llmOutput(testCase)),
                objectMapper,
                semanticPromptLoader());
        JsonNode input = testCase.path("input");
        SemanticActionPlan plan = service.plan(
                input.path("userInput").asText(),
                input.path("category").asText(),
                input.path("productName").asText(),
                input.path("historyText").asText());
        assertThat(plan).as(testCase.path("id").asText()).isNotNull();

        JsonNode expected = testCase.path("expected");
        if (expected.hasNonNull("executionMode")) {
            assertThat(plan.executionMode()).isEqualTo(expected.path("executionMode").asText());
        }
        if (expected.has("hardFilter")) {
            assertHardFilterPresent(plan, expected.path("hardFilter"));
        }
        if (expected.has("absentHardFilter")) {
            assertHardFilterAbsent(plan, expected.path("absentHardFilter"));
        }
        if (expected.has("hardFilterCount")) {
            assertThat(plan.hardFilters() == null ? 0 : plan.hardFilters().size())
                    .isEqualTo(expected.path("hardFilterCount").asInt());
        }
        if (expected.hasNonNull("preferenceCode")) {
            assertThat(plan.preferences()).isNotNull();
            assertThat(plan.preferences().stream().map(SemanticActionPlan.PreferenceRule::code))
                    .contains(expected.path("preferenceCode").asText());
        }

        return Map.of(
                "executionMode", plan.executionMode(),
                "hardFilters", plan.hardFilters() == null ? 0 : plan.hardFilters().size(),
                "preferences", plan.preferences() == null ? 0 : plan.preferences().size());
    }

    private Map<String, Object> runLegacyNlpCase(JsonNode testCase) {
        RuleBasedNlpParser ruleParser = mock(RuleBasedNlpParser.class);
        NlpConversationManager conversationManager = mock(NlpConversationManager.class);
        AiTraceService traceService = mock(AiTraceService.class);
        when(traceService.start(anyString(), any())).thenReturn("ai-golden-trace");
        when(conversationManager.isLimitReached(anyString())).thenReturn(false);
        when(conversationManager.getHistory(anyString())).thenReturn(List.of());

        JsonNode input = testCase.path("input");
        JsonNode expected = testCase.path("expected");
        SearchFilter ruleFilter = SearchFilter.empty();
        if (expected.has("fallbackPriceMax")) {
            ruleFilter = new SearchFilter(new PriceRange(null, expected.path("fallbackPriceMax").asDouble()),
                    List.of(), null, List.of(), List.of(), null, null, null, null);
        }
        when(ruleParser.parse(input.path("userInput").asText()))
                .thenReturn(new RuleBasedNlpParser.ParsedFilter(ruleFilter, false));

        SpringAiNlpService service = new SpringAiNlpService(
                ruleParser,
                conversationManager,
                chatClientProvider(llmOutput(testCase)),
                objectMapper,
                traceService,
                legacyPromptLoader());

        NlpParseResult result = service.parse(new NlpParseRequest(
                input.path("sessionId").asText(),
                input.path("userInput").asText(),
                null));

        assertThat(result.decision()).isEqualTo(expected.path("decision").asText());
        if (expected.has("sortBy")) {
            assertThat(result.filter().sortBy()).isEqualTo(expected.path("sortBy").asText());
        }
        if (expected.has("clauseField")) {
            assertThat(result.clauses()).isNotNull();
            assertThat(result.clauses().stream().map(NlpParseResult.DetectedClause::field))
                    .contains(expected.path("clauseField").asText());
        }
        if (expected.has("fallbackPriceMax")) {
            assertThat(result.filter().priceRange().max())
                    .isEqualTo(expected.path("fallbackPriceMax").asDouble());
        }
        return Map.of("decision", result.decision());
    }

    private Map<String, Object> runQwenFlashDetectionCase(JsonNode testCase) throws Exception {
        AliyunQwenVisionClient client = qwenClient();
        Method parse = AliyunQwenVisionClient.class
                .getDeclaredMethod("parseDetectionResponse", String.class, byte[].class);
        parse.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<RecognitionCandidate> candidates = (List<RecognitionCandidate>) parse.invoke(
                client,
                openAiBody(llmOutput(testCase)),
                imageBytes(1000, 800));

        JsonNode expected = testCase.path("expected");
        assertThat(candidates).hasSizeGreaterThanOrEqualTo(expected.path("minCandidates").asInt());
        long lowConfidence = candidates.stream().filter(candidate -> candidate.confidence() < 0.55).count();
        assertThat(lowConfidence).isGreaterThanOrEqualTo(expected.path("minLowConfidenceCandidates").asLong());
        return Map.of("candidates", candidates.size(), "lowConfidenceCandidates", lowConfidence);
    }

    private AliyunQwenVisionClient qwenClient() throws Exception {
        var constructor = AliyunQwenVisionClient.class.getDeclaredConstructor(
                VisionCartProperties.class,
                AiTraceService.class,
                ObjectMapper.class,
                PromptLoader.class,
                DoubaoVisionClient.class,
                String.class,
                String.class,
                RestClient.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                new VisionCartProperties(),
                mock(AiTraceService.class),
                objectMapper,
                mock(PromptLoader.class),
                mock(DoubaoVisionClient.class),
                "test-key",
                "http://localhost",
                RestClient.create());
    }

    private Map<String, Object> runProductJudgeCase(JsonNode testCase) throws Exception {
        LlmProductJudge judge = new LlmProductJudge(
                mock(ObjectProvider.class),
                objectMapper,
                mock(PromptLoader.class),
                new VisionCartProperties(),
                Executors.newSingleThreadExecutor(),
                mock(PerformanceMetricsService.class));
        Method parse = LlmProductJudge.class.getDeclaredMethod("parseResults", String.class, Set.class);
        parse.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<LlmProductJudge.JudgeScore> scores = (List<LlmProductJudge.JudgeScore>) parse.invoke(
                judge,
                llmOutput(testCase),
                Set.of("p1", "p2"));

        JsonNode expected = testCase.path("expected");
        assertThat(scores).hasSize(expected.path("count").asInt());
        assertThat(scores.stream().filter(score -> "GOOD_MATCH".equals(score.bucket())).map(LlmProductJudge.JudgeScore::productId))
                .contains(expected.path("goodProductId").asText());
        assertThat(scores.stream().filter(score -> "REJECT".equals(score.bucket())).map(LlmProductJudge.JudgeScore::productId))
                .contains(expected.path("rejectProductId").asText());
        return Map.of("scores", scores.size());
    }

    private Map<String, Object> runGuideInsightCase(JsonNode testCase) throws Exception {
        PerformanceMetricsService metricsService = mock(PerformanceMetricsService.class);
        when(metricsService.startDeepSuggestionTimer()).thenReturn(mock(Timer.Sample.class));
        DeepSuggestionService service = new DeepSuggestionService(
                mock(ObjectProvider.class),
                objectMapper,
                mock(AiTraceService.class),
                mock(PromptLoader.class),
                metricsService);
        Method validate = DeepSuggestionService.class
                .getDeclaredMethod("validateAiOutput", String.class, List.class, int.class);
        validate.setAccessible(true);

        List<GuideInsightFact> facts = List.of(new GuideInsightFact(
                "insight_price_band",
                "price_band",
                "Price band",
                "36 items cluster between 80 and 130",
                "filter_price_band:80:130",
                "Filter",
                Map.of("min", 80, "max", 130, "count", 36)));
        @SuppressWarnings("unchecked")
        List<SuggestionCard> cards = (List<SuggestionCard>) validate.invoke(service, llmOutput(testCase), facts, 2);

        JsonNode expected = testCase.path("expected");
        assertThat(cards).isNotNull();
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).id()).isEqualTo(expected.path("cardId").asText());
        assertThat(cards.get(0).action()).isEqualTo(expected.path("action").asText());
        return Map.of("cards", cards.size(), "action", cards.get(0).action());
    }

    private void assertHardFilterPresent(SemanticActionPlan plan, JsonNode expected) {
        assertThat(plan.hardFilters()).isNotNull();
        assertThat(plan.hardFilters()).anySatisfy(filter -> {
            assertThat(filter.field()).isEqualTo(expected.path("field").asText());
            assertThat(filter.operator()).isEqualTo(expected.path("operator").asText());
            if (expected.has("value")) {
                assertThat(asDouble(filter.value())).isEqualTo(expected.path("value").asDouble());
            }
        });
    }

    private void assertHardFilterAbsent(SemanticActionPlan plan, JsonNode expected) {
        List<SemanticActionPlan.HardFilter> filters = plan.hardFilters() == null ? List.of() : plan.hardFilters();
        assertThat(filters).noneSatisfy(filter -> {
            assertThat(filter.field()).isEqualTo(expected.path("field").asText());
            assertThat(filter.operator()).isEqualTo(expected.path("operator").asText());
        });
    }

    private ObjectProvider<ChatClient.Builder> chatClientProvider(String response) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(provider.getIfAvailable()).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(response);
        return provider;
    }

    private PromptLoader semanticPromptLoader() {
        PromptLoader promptLoader = mock(PromptLoader.class);
        when(promptLoader.getPrompt("semantic-planner-system")).thenReturn("system");
        when(promptLoader.getPrompt("semantic-planner-user"))
                .thenReturn("Product: %s, Category: %s, History: %s, Input: %s");
        return promptLoader;
    }

    private PromptLoader legacyPromptLoader() {
        PromptLoader promptLoader = mock(PromptLoader.class);
        when(promptLoader.getPrompt("nlp-system")).thenReturn("system");
        when(promptLoader.getPrompt("nlp-user"))
                .thenReturn("Product: %s, Category: %s, History: %s, Input: %s");
        return promptLoader;
    }

    private String llmOutput(JsonNode testCase) {
        if (testCase.hasNonNull("llmOutputText")) {
            return testCase.path("llmOutputText").asText();
        }
        try {
            return objectMapper.writeValueAsString(testCase.path("llmOutput"));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid llmOutput for " + testCase.path("id").asText(), e);
        }
    }

    private String openAiBody(String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("content", content)
                ))
        ));
    }

    private byte[] imageBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        assertThat(value).as(field).isNotBlank();
        return value;
    }

    private double asDouble(Object value) {
        assertThat(value).isInstanceOf(Number.class);
        return ((Number) value).doubleValue();
    }

    private void writeReport(List<CaseOutcome> outcomes) throws Exception {
        Path reportDir = Path.of("build/reports/ai-golden");
        Files.createDirectories(reportDir);

        long passed = outcomes.stream().filter(CaseOutcome::passed).count();
        long failed = outcomes.size() - passed;
        long requiredFailed = outcomes.stream().filter(outcome -> outcome.required() && !outcome.passed()).count();

        Map<String, Object> byPrompt = new LinkedHashMap<>();
        outcomes.stream()
                .map(outcome -> outcome.promptName() + "@" + outcome.promptVersion())
                .distinct()
                .sorted()
                .forEach(prompt -> {
                    List<CaseOutcome> promptCases = outcomes.stream()
                            .filter(outcome -> prompt.equals(outcome.promptName() + "@" + outcome.promptVersion()))
                            .toList();
                    byPrompt.put(prompt, Map.of(
                            "total", promptCases.size(),
                            "passed", promptCases.stream().filter(CaseOutcome::passed).count(),
                            "failed", promptCases.stream().filter(outcome -> !outcome.passed()).count()));
                });

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", outcomes.size());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("requiredFailed", requiredFailed);
        summary.put("byPrompt", byPrompt);
        summary.put("cases", outcomes);

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(reportDir.resolve("summary.json").toFile(), summary);
        Files.writeString(reportDir.resolve("summary.md"), markdown(outcomes, passed, failed, requiredFailed));
    }

    private String markdown(List<CaseOutcome> outcomes, long passed, long failed, long requiredFailed) {
        StringBuilder out = new StringBuilder();
        out.append("# AI Golden Report\n\n");
        out.append("- total: ").append(outcomes.size()).append('\n');
        out.append("- passed: ").append(passed).append('\n');
        out.append("- failed: ").append(failed).append('\n');
        out.append("- requiredFailed: ").append(requiredFailed).append("\n\n");
        out.append("| id | type | prompt | required | result |\n");
        out.append("| --- | --- | --- | --- | --- |\n");
        for (CaseOutcome outcome : outcomes) {
            out.append("| ")
                    .append(outcome.id()).append(" | ")
                    .append(outcome.type()).append(" | ")
                    .append(outcome.promptName()).append("@").append(outcome.promptVersion()).append(" | ")
                    .append(outcome.required()).append(" | ")
                    .append(outcome.passed() ? "PASS" : "FAIL: " + safe(outcome.failure()))
                    .append(" |\n");
        }
        return out.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private record CaseOutcome(
            String id,
            String type,
            String promptName,
            String promptVersion,
            boolean required,
            boolean passed,
            String failure,
            Map<String, Object> details
    ) {}
}
