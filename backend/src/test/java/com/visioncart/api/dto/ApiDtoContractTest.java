package com.visioncart.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiDtoContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void asyncRecognitionResponseUsesAndroidSnakeCaseContract() throws Exception {
        AsyncRecognitionResponse response = new AsyncRecognitionResponse(
                "session-1", "PENDING", "/topic/recognition/session-1", 1200L);

        JsonNode json = objectMapper.valueToTree(response);

        assertThat(json.has("session_id")).isTrue();
        assertThat(json.has("websocket_topic")).isTrue();
        assertThat(json.has("estimated_ms")).isTrue();
        assertThat(json.has("sessionId")).isFalse();

        AsyncRecognitionResponse parsed = objectMapper.readValue("""
                {
                  "session_id": "session-1",
                  "status": "PENDING",
                  "websocket_topic": "/topic/recognition/session-1",
                  "estimated_ms": 1200
                }
                """, AsyncRecognitionResponse.class);

        assertThat(parsed.sessionId()).isEqualTo("session-1");
        assertThat(parsed.websocketTopic()).isEqualTo("/topic/recognition/session-1");
        assertThat(parsed.estimatedMs()).isEqualTo(1200L);
    }

    @Test
    void recognitionResultUsesAndroidSnakeCaseContract() {
        RecognitionResult result = new RecognitionResult(
                "session-1",
                new CategoryDto("服装", "外套", "夹克", 0.9),
                Map.of("品牌", new AttributeValue("Nike", 0.95, true)),
                List.of("Nike", "夹克"),
                0.88
        );

        JsonNode json = objectMapper.valueToTree(result);

        assertThat(json.has("session_id")).isTrue();
        assertThat(json.has("overall_confidence")).isTrue();
        assertThat(json.has("platform_stats")).isTrue();
        assertThat(json.has("overallConfidence")).isFalse();
    }

    @Test
    void searchResultProductFieldsUseAndroidSnakeCaseContract() {
        ProductCard product = new ProductCard(
                "p1",
                "测试商品",
                "https://example.test/image.jpg",
                BigDecimal.valueOf(99.9),
                BigDecimal.valueOf(129.9),
                "京东",
                true,
                "旗舰店",
                4.9,
                1000,
                0.92,
                List.of("自营"),
                "https://example.test/detail"
        );
        SearchResult result = new SearchResult(1, List.of(product), List.of(), List.of());

        JsonNode json = objectMapper.valueToTree(result);
        JsonNode productJson = json.path("products").path(0);

        assertThat(json.has("platform_stats")).isTrue();
        assertThat(json.has("suggestion_cards")).isTrue();
        assertThat(productJson.has("image_url")).isTrue();
        assertThat(productJson.has("original_price")).isTrue();
        assertThat(productJson.has("self_operated")).isTrue();
        assertThat(productJson.has("shop_name")).isTrue();
        assertThat(productJson.has("detail_url")).isTrue();
        assertThat(productJson.has("rating_source")).isTrue();
        assertThat(productJson.has("sales_label")).isTrue();
        assertThat(productJson.has("imageUrl")).isFalse();
    }
}
