package com.visioncart.service.recognition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.CategoryDto;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.service.ai.AiTraceService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DoubaoVisionClient implements VisionModelService {
    private final VisionCartProperties properties;
    private final AiTraceService traceService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String arkBaseUrl;

    public DoubaoVisionClient(VisionCartProperties properties,
                              AiTraceService traceService,
                              ObjectMapper objectMapper,
                              @Value("${spring.ai.openai.base-url:https://ark.cn-beijing.volces.com/api/v3}") String arkBaseUrl) {
        this.properties = properties;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
        this.arkBaseUrl = arkBaseUrl;
    }

    @Override
    public RecognitionResult analyze(MultipartFile image, String region) {
        String apiKey = properties.getAi().getVisionApiKey();
        String model = properties.getAi().getVisionModel();
        Instant started = traceService.start("vision.recognition", Map.of(
                "model", StringUtils.defaultString(model, "unknown"),
                "filename", StringUtils.defaultString(image.getOriginalFilename(), "upload")
        ));

        if (isPlaceholder(apiKey) || StringUtils.isBlank(model)) {
            throw new IllegalStateException("豆包视觉 API 未配置，请检查 ARK_VISION_API_KEY 和 ARK_VISION_MODEL");
        }

        try {
            String body = restClient.post()
                    .uri(StringUtils.removeEnd(arkBaseUrl, "/") + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(image, region, model))
                    .retrieve()
                    .body(String.class);
            RecognitionResult result = parseResponse(body);
            traceService.finish("vision.recognition", started, "doubao_vision");
            return result;
        } catch (Exception error) {
            traceService.fail("vision.recognition", started, error, "none");
            throw new IllegalStateException("豆包视觉识别失败: " + error.getMessage(), error);
        }
    }

    private Map<String, Object> requestBody(MultipartFile image, String region, String model) throws Exception {
        byte[] imageBytes = image.getBytes();
        String contentType = normalizedContentType(image.getContentType(), imageBytes);
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String imageUrl = "data:" + contentType + ";base64," + base64;
        return Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", prompt(region)),
                                Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))
                        )
                ))
        );
    }

    private String prompt(String region) {
        return """
                你是电商商品识别模型。请识别图片中的主要可购买商品，输出严格 JSON，不要 markdown。
                JSON 格式：
                {
                  "category":{"level1":"一级类目","level2":"二级类目","level3":"三级类目","confidence":0.0},
                  "attributes":{"品牌":{"value":"品牌或未知","confidence":0.0,"verified":false},"颜色":{"value":"颜色","confidence":0.0,"verified":false},"款式":{"value":"款式","confidence":0.0,"verified":false},"材质":{"value":"材质","confidence":0.0,"verified":false},"适用人群":{"value":"适用人群","confidence":0.0,"verified":false}},
                  "keywords":["适合搜索商品的关键词"],
                  "overall_confidence":0.0
                }
                如果品牌看不清，品牌值写“未知”。区域提示：%s
                """.formatted(StringUtils.defaultIfBlank(region, "整张图"));
    }

    private String normalizedContentType(String contentType, byte[] imageBytes) {
        String normalized = StringUtils.defaultString(contentType).toLowerCase();
        if (normalized.matches("image/(jpeg|jpg|png|webp)")) {
            return "image/jpg".equals(normalized) ? "image/jpeg" : normalized;
        }
        if (imageBytes.length >= 4
                && (imageBytes[0] & 0xFF) == 0x89
                && imageBytes[1] == 0x50
                && imageBytes[2] == 0x4E
                && imageBytes[3] == 0x47) {
            return "image/png";
        }
        if (imageBytes.length >= 3
                && (imageBytes[0] & 0xFF) == 0xFF
                && (imageBytes[1] & 0xFF) == 0xD8
                && (imageBytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (imageBytes.length >= 12
                && imageBytes[0] == 0x52
                && imageBytes[1] == 0x49
                && imageBytes[2] == 0x46
                && imageBytes[3] == 0x46
                && imageBytes[8] == 0x57
                && imageBytes[9] == 0x45
                && imageBytes[10] == 0x42
                && imageBytes[11] == 0x50) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private RecognitionResult parseResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (StringUtils.isBlank(content)) {
            throw new IllegalStateException("豆包视觉返回为空");
        }
        JsonNode parsed = objectMapper.readTree(extractJson(content));
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

        return new RecognitionResult(
                UUID.randomUUID().toString(),
                categoryDto,
                attributes,
                keywords,
                confidence(parsed.path("overall_confidence"), 0.6)
        );
    }

    private void ensureAttribute(Map<String, AttributeValue> attributes, String key) {
        attributes.putIfAbsent(key, new AttributeValue("未知", 0.3, false));
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
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
        return Math.max(0, Math.min(1, node.asDouble(defaultValue)));
    }

    private boolean isPlaceholder(String value) {
        return StringUtils.isBlank(value) || value.startsWith("replace-with");
    }
}
