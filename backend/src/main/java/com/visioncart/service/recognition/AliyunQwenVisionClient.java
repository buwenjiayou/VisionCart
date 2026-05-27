package com.visioncart.service.recognition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.CategoryDto;
import com.visioncart.api.dto.RecognitionCandidate;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.service.ai.AiTraceService;
import com.visioncart.service.ai.AiJsonUtils;
import com.visioncart.service.ai.PromptLoader;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Primary
@Component
public class AliyunQwenVisionClient implements VisionModelService {

    private final VisionCartProperties properties;
    private final AiTraceService traceService;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final DoubaoVisionClient fallbackClient;
    private final RestClient restClient;
    private final String apiKey;
    private final String baseUrl;

    @Autowired
    public AliyunQwenVisionClient(VisionCartProperties properties,
                                  AiTraceService traceService,
                                  ObjectMapper objectMapper,
                                  PromptLoader promptLoader,
                                  DoubaoVisionClient fallbackClient,
                                  @Value("${aliyun.bailian.api-key:}") String apiKey,
                                  @Value("${aliyun.bailian.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl) {
        this(properties, traceService, objectMapper, promptLoader, fallbackClient, apiKey, baseUrl,
                RestClient.builder()
                        .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                            setConnectTimeout(Duration.ofMillis(5000));
                            setReadTimeout(Duration.ofMillis(properties.getRecognition().getTimeoutMs()));
                        }})
                        .build());
    }

    AliyunQwenVisionClient(VisionCartProperties properties,
                           AiTraceService traceService,
                           ObjectMapper objectMapper,
                           PromptLoader promptLoader,
                           DoubaoVisionClient fallbackClient,
                           String apiKey,
                           String baseUrl,
                           RestClient restClient) {
        this.properties = properties;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.fallbackClient = fallbackClient;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.restClient = restClient;
    }

    @Override
    public RecognitionResult analyze(MultipartFile image, String region) {
        if (!isConfigured()) {
            return fallbackClient.analyze(image, region);
        }
        try {
            byte[] bytes = image.getBytes();
            return extractAttributes(bytes, normalizedContentType(image.getContentType(), bytes), "商品", null, region);
        } catch (Exception e) {
            throw new IllegalStateException("通义千问视觉识别失败: " + e.getMessage(), e);
        }
    }

    @Override
    public RecognitionResult analyze(byte[] imageBytes, String contentType, String region) {
        if (!isConfigured()) {
            return fallbackClient.analyze(imageBytes, contentType, region);
        }
        return extractAttributes(imageBytes, normalizedContentType(contentType, imageBytes), "商品", null, region);
    }

    @Override
    public boolean supportsTwoStageRecognition() {
        return isConfigured()
                && StringUtils.isNotBlank(properties.getAi().getQwenFlashModel())
                && StringUtils.isNotBlank(properties.getAi().getQwenPlusModel());
    }

    @Override
    public List<RecognitionCandidate> detectProducts(byte[] imageBytes, String contentType, String region) {
        String model = properties.getAi().getQwenFlashModel();
        String traceId = traceService.start("vision.qwen.flash", Map.of("model", StringUtils.defaultString(model, "unknown")));
        try {
            String body = call(model, imageBytes, normalizedContentType(contentType, imageBytes), flashPrompt(region));
            List<RecognitionCandidate> candidates = parseDetectionResponse(body, imageBytes);
            traceService.finish("vision.qwen.flash", traceId, "aliyun_qwen_flash");
            return candidates;
        } catch (Exception error) {
            traceService.fail("vision.qwen.flash", traceId, error, "none");
            throw new IllegalStateException("Qwen3-VL-Flash 商品检测失败: " + error.getMessage(), error);
        }
    }

    @Override
    public RecognitionResult extractAttributes(byte[] imageBytes,
                                               String contentType,
                                               String categoryHint,
                                               String brandHint,
                                               String region) {
        String model = properties.getAi().getQwenPlusModel();
        String traceId = traceService.start("vision.qwen.plus", Map.of(
                "model", StringUtils.defaultString(model, "unknown"),
                "category", StringUtils.defaultString(categoryHint, "unknown")
        ));
        try {
            String body = call(model, imageBytes, normalizedContentType(contentType, imageBytes),
                    plusPrompt(categoryHint, brandHint));
            RecognitionResult result = parseRecognitionResponse(body, categoryHint, brandHint);
            traceService.finish("vision.qwen.plus", traceId, "aliyun_qwen_plus");
            return result;
        } catch (Exception error) {
            traceService.fail("vision.qwen.plus", traceId, error, "none");
            throw new IllegalStateException("Qwen3-VL-Plus 属性提取失败: " + error.getMessage(), error);
        }
    }

    List<RecognitionCandidate> parseDetectionResponse(String body, byte[] imageBytes) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (StringUtils.isBlank(content)) {
            throw new IllegalStateException("Qwen3-VL-Flash 返回为空");
        }
        JsonNode parsed = objectMapper.readTree(AiJsonUtils.extractFirstJsonObject(content));
        JsonNode products = parsed.path("products");
        if (!products.isArray()) {
            return List.of();
        }

        int[] size = imageSize(imageBytes);
        List<RecognitionCandidate> candidates = new ArrayList<>();
        int index = 1;
        for (JsonNode product : products) {
            List<Integer> bbox = parseBbox(product.path("bbox"), size[0], size[1]);
            if (bbox.size() == 4) {
                candidates.add(new RecognitionCandidate(
                        "candidate-" + index,
                        bbox,
                        textOrDefault(product.path("category"), "未知"),
                        nullableBrand(product.path("brand")),
                        confidence(product.path("confidence"), 0.5),
                        null
                ));
                index++;
            }
        }
        return candidates;
    }

    RecognitionResult parseRecognitionResponse(String body, String categoryHint, String brandHint) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (StringUtils.isBlank(content)) {
            throw new IllegalStateException("Qwen3-VL-Plus 返回为空");
        }
        JsonNode parsed = objectMapper.readTree(AiJsonUtils.extractFirstJsonObject(content));
        CategoryDto category = parseCategory(parsed.path("category"), categoryHint);

        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        JsonNode attributeNode = parsed.path("attributes");
        if (attributeNode.isObject()) {
            attributeNode.fields().forEachRemaining(entry -> attributes.put(entry.getKey(), parseAttribute(entry.getValue())));
        }

        String topLevelBrand = nullableBrand(parsed.path("brand"));
        String brand = StringUtils.defaultIfBlank(topLevelBrand, StringUtils.defaultIfBlank(brandHint, "未知"));
        ensureAttribute(attributes, "品牌", brand, brandKnown(brand) ? 0.75 : 0.3);
        ensureAttribute(attributes, "颜色", "未知", 0.3);
        ensureAttribute(attributes, "款式", "未知", 0.3);
        ensureAttribute(attributes, "材质", "未知", 0.3);
        ensureAttribute(attributes, "适用人群", "通用", 0.3);
        if (parsed.hasNonNull("style") && !attributes.containsKey("风格")) {
            attributes.put("风格", new AttributeValue(textOrDefault(parsed.path("style"), "未知"), 0.6, false));
        }
        if (parsed.hasNonNull("quality_score") && !attributes.containsKey("质量评分")) {
            attributes.put("质量评分", new AttributeValue(parsed.path("quality_score").asText(), 0.6, false));
        }

        List<String> keywords = parseKeywords(parsed.path("keywords"));
        if (keywords.isEmpty()) {
            keywords.add(StringUtils.defaultIfBlank(category.level3(), StringUtils.defaultIfBlank(categoryHint, "商品")));
            String usefulBrand = SearchBrandSanitizer.usefulBrand(brand);
            if (StringUtils.isNotBlank(usefulBrand)) {
                keywords.add(usefulBrand + category.level3());
            }
        }

        return new RecognitionResult(
                "",
                category,
                attributes,
                keywords.stream().filter(StringUtils::isNotBlank).distinct().limit(3).toList(),
                confidence(parsed.path("overall_confidence"), category.confidence())
        );
    }

    private String call(String model, byte[] imageBytes, String contentType, String prompt) {
        String imageUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> body = new LinkedHashMap<>();
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
                        Map.of("type", "text", "text", prompt),
                        Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))
                )
        )));

        return restClient.post()
                .uri(StringUtils.removeEnd(baseUrl, "/") + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    private String flashPrompt(String region) {
        return promptLoader.getPrompt("qwen-flash-detection")
                .formatted(StringUtils.defaultIfBlank(region, "整张图"));
    }

    private String plusPrompt(String categoryHint, String brandHint) {
        String category = StringUtils.defaultIfBlank(categoryHint, "商品");
        String brandText = SearchBrandSanitizer.usefulBrand(brandHint);
        String brandInstruction = StringUtils.isBlank(brandText)
                ? "未检测到可靠品牌；如果裁剪图中品牌不可见，品牌写\"未知\"。"
                : "Flash 阶段检测到可能品牌【" + brandText + "】；只有裁剪图中能确认时才使用，否则写\"未知\"。";
        return promptLoader.getPrompt("qwen-plus-attributes").formatted(category, brandInstruction);
    }

    private boolean isConfigured() {
        return StringUtils.isNotBlank(apiKey) && !apiKey.startsWith("replace-with");
    }

    private CategoryDto parseCategory(JsonNode node, String categoryHint) {
        if (node != null && node.isTextual()) {
            String category = textOrDefault(node, StringUtils.defaultIfBlank(categoryHint, "未知"));
            return new CategoryDto("商品", category, category, 0.7);
        }
        String fallback = StringUtils.defaultIfBlank(categoryHint, "未知");
        return new CategoryDto(
                textOrDefault(node.path("level1"), "商品"),
                textOrDefault(node.path("level2"), fallback),
                textOrDefault(node.path("level3"), fallback),
                confidence(node.path("confidence"), 0.7)
        );
    }

    private AttributeValue parseAttribute(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new AttributeValue("未知", 0.3, false);
        }
        if (!node.isObject()) {
            return new AttributeValue(textOrDefault(node, "未知"), 0.5, false);
        }
        return new AttributeValue(
                textOrDefault(node.path("value"), "未知"),
                confidence(node.path("confidence"), 0.5),
                node.path("verified").asBoolean(false)
        );
    }

    private void ensureAttribute(Map<String, AttributeValue> attributes, String key, String value, double confidence) {
        attributes.putIfAbsent(key, new AttributeValue(StringUtils.defaultIfBlank(value, "未知"), confidence, false));
    }

    private List<String> parseKeywords(JsonNode node) {
        List<String> keywords = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                String keyword = item.asText("");
                if (StringUtils.isNotBlank(keyword)) {
                    keywords.add(keyword.trim());
                }
            });
        }
        return keywords;
    }

    private List<Integer> parseBbox(JsonNode node, int width, int height) {
        if (!node.isArray() || node.size() < 4) {
            return List.of();
        }
        List<Double> raw = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            raw.add(node.get(i).asDouble(Double.NaN));
        }
        if (raw.stream().anyMatch(value -> Double.isNaN(value))) {
            return List.of();
        }
        boolean normalized = raw.stream().allMatch(value -> value >= 0.0 && value <= 1.0);
        int x1 = toPixel(raw.get(0), normalized ? width : 1);
        int y1 = toPixel(raw.get(1), normalized ? height : 1);
        int x2 = toPixel(raw.get(2), normalized ? width : 1);
        int y2 = toPixel(raw.get(3), normalized ? height : 1);
        return List.of(x1, y1, x2, y2);
    }

    private int toPixel(double value, int scale) {
        return (int) Math.round(value * scale);
    }

    private int[] imageSize(byte[] imageBytes) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image != null) {
                return new int[]{image.getWidth(), image.getHeight()};
            }
        } catch (Exception ignored) {
        }
        return new int[]{1, 1};
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
        return "image/jpeg";
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

    private String nullableBrand(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText("").trim();
        String useful = SearchBrandSanitizer.usefulBrand(text);
        return StringUtils.isBlank(useful) ? null : useful;
    }

    private boolean brandKnown(String brand) {
        return StringUtils.isNotBlank(SearchBrandSanitizer.usefulBrand(brand));
    }

    private static final class SearchBrandSanitizer {
        private SearchBrandSanitizer() {
        }

        static String usefulBrand(String brand) {
            String value = StringUtils.defaultString(brand).trim();
            return value.isBlank()
                    || "未知".equals(value)
                    || "无品牌".equals(value)
                    || "无".equals(value)
                    || "null".equalsIgnoreCase(value)
                    || "unknown".equalsIgnoreCase(value)
                    ? ""
                    : value;
        }
    }
}
