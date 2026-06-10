package com.visioncart.service.recognition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.CategoryDto;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.service.ai.AiTraceService;
import com.visioncart.service.ai.AiJsonUtils;
import com.visioncart.service.ai.PromptLoader;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DoubaoVisionClient implements VisionModelService {
    private static final Logger log = LoggerFactory.getLogger(DoubaoVisionClient.class);
    private final VisionCartProperties properties;
    private final AiTraceService traceService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String arkBaseUrl;
    private final PromptLoader promptLoader;

    @Autowired
    public DoubaoVisionClient(VisionCartProperties properties,
                              AiTraceService traceService,
                              ObjectMapper objectMapper,
                              PromptLoader promptLoader,
                              @Value("${spring.ai.openai.base-url:https://ark.cn-beijing.volces.com/api/v3}") String arkBaseUrl) {
        this(properties, traceService, objectMapper, promptLoader, arkBaseUrl,
                RestClient.builder()
                        .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                            setConnectTimeout(Duration.ofMillis(5000));
                            setReadTimeout(Duration.ofMillis(properties.getRecognition().getTimeoutMs()));
                        }})
                        .build());
    }

    DoubaoVisionClient(VisionCartProperties properties,
                       AiTraceService traceService,
                       ObjectMapper objectMapper,
                       PromptLoader promptLoader,
                       String arkBaseUrl,
                       RestClient restClient) {
        this.properties = properties;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.arkBaseUrl = arkBaseUrl;
        this.restClient = restClient;
    }

    @Override
    public RecognitionResult analyze(MultipartFile image, String region) {
        try {
            return doAnalyze(image.getBytes(), normalizedContentType(image.getContentType(), image.getBytes()), region);
        } catch (Exception e) {
            throw new IllegalStateException("豆包视觉识别失败: " + e.getMessage(), e);
        }
    }

    @Override
    public RecognitionResult analyze(byte[] imageBytes, String contentType, String region) {
        return doAnalyze(imageBytes, contentType, region);
    }

    private RecognitionResult doAnalyze(byte[] imageBytes, String contentType, String region) {
        String apiKey = properties.getAi().getVisionApiKey();
        String model = properties.getAi().getVisionModel();
        String traceId = traceService.start("vision.recognition", Map.of(
                "model", StringUtils.defaultString(model, "unknown"),
                "filename", "upload"
        ));

        if (isPlaceholder(apiKey) || StringUtils.isBlank(model)) {
            throw new IllegalStateException("豆包视觉 API 未配置，请检查 ARK_VISION_API_KEY 和 ARK_VISION_MODEL");
        }

        try {
            String body = restClient.post()
                    .uri(StringUtils.removeEnd(arkBaseUrl, "/") + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(imageBytes, contentType, region, model))
                    .retrieve()
                    .body(String.class);
            RecognitionResult result = parseResponse(body);
            traceService.finish("vision.recognition", traceId, "doubao_vision");
            return result;
        } catch (Exception error) {
            traceService.fail("vision.recognition", traceId, error, "none");
            throw new IllegalStateException("豆包视觉识别失败: " + error.getMessage(), error);
        }
    }

    private Map<String, Object> requestBody(byte[] imageBytes, String contentType, String region, String model) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalStateException("图片数据为空");
        }
        // Base64 encoding expands data by ~33%; cap at 20MB raw to prevent OOM
        if (imageBytes.length > 20 * 1024 * 1024) {
            throw new IllegalStateException("图片数据过大: " + (imageBytes.length / 1024 / 1024) + "MB (最大20MB)");
        }
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String imageUrl = "data:" + contentType + ";base64," + base64;
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", properties.getAi().getTemperature());
        if (properties.getAi().getMaxTokens() != null) {
            body.put("max_tokens", properties.getAi().getMaxTokens());
        }
        if (properties.getAi().getTopP() != null) {
            body.put("top_p", properties.getAi().getTopP());
        }
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", prompt(region)),
                        Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))
                )
        )));
        return body;
    }

    private String prompt(String region) {
        return promptLoader.getPrompt("vision-recognition")
                .formatted(StringUtils.defaultIfBlank(region, "整张图"));
    }

    private String normalizedContentType(String contentType, byte[] imageBytes) {
        return ImageProcessor.normalizedContentType(imageBytes, contentType);
    }

    private RecognitionResult parseResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (StringUtils.isBlank(content)) {
            throw new IllegalStateException("豆包视觉返回为空");
        }
        String json = AiJsonUtils.extractFirstJsonObject(content);
        if (StringUtils.isBlank(json)) {
            log.warn("豆包视觉返回非JSON内容: {}", content.length() > 200 ? content.substring(0, 200) + "..." : content);
            throw new IllegalStateException("视觉模型未返回合法 JSON，原始内容: " +
                    (content.length() > 100 ? content.substring(0, 100) + "..." : content));
        }
        JsonNode parsed = objectMapper.readTree(json);
        JsonNode category = parsed.path("category");
        CategoryDto categoryDto = new CategoryDto(
                textOrDefault(category.path("level1"), "未知"),
                textOrDefault(category.path("level2"), "未知"),
                textOrDefault(category.path("level3"), "未知"),
                confidence(category.path("confidence"), 0.5)
        );

        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        JsonNode attributeNode = parsed.path("attributes");
        if (attributeNode.isObject()) {
            attributeNode.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                attributes.put(entry.getKey(), new AttributeValue(
                        textOrDefault(value.path("value"), "未知"),
                        confidence(value.path("confidence"), 0.5),
                        value.path("verified").asBoolean(false)
                ));
            });
        }
        ensureAttribute(attributes, "品牌");
        ensureAttribute(attributes, "颜色");
        ensureAttribute(attributes, "款式");

        List<String> keywords = new ArrayList<>();
        JsonNode keywordNode = parsed.path("keywords");
        if (keywordNode.isArray()) {
            keywordNode.forEach(item -> {
                if (!item.asText("").isBlank()) {
                    keywords.add(item.asText());
                }
            });
        }
        if (keywords.isEmpty()) {
            attributes.values().stream()
                    .map(AttributeValue::value)
                    .filter(value -> !value.isBlank() && !"未知".equals(value))
                    .forEach(keywords::add);
        }

        return RecognitionResultValidator.validate(new RecognitionResult(
                "",
                categoryDto,
                attributes,
                keywords,
                confidence(parsed.path("overall_confidence"), 0.6)
        ));
    }

    private void ensureAttribute(Map<String, AttributeValue> attributes, String key) {
        attributes.putIfAbsent(key, new AttributeValue("未知", 0.3, false));
    }

    private String textOrDefault(JsonNode node, String defaultValue) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()
                ? defaultValue
                : node.asText();
    }

    private double confidence(JsonNode node, double defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        return AiJsonUtils.clampConfidence(node.asDouble(defaultValue), defaultValue);
    }

    private boolean isPlaceholder(String value) {
        return StringUtils.isBlank(value) || value.startsWith("replace-with");
    }
}
