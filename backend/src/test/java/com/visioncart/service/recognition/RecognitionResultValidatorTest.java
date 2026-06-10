package com.visioncart.service.recognition;

import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.CategoryDto;
import com.visioncart.api.dto.RecognitionResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecognitionResultValidatorTest {

    @Test
    void validResultPassesThrough() {
        RecognitionResult input = new RecognitionResult(
                "session-1",
                new CategoryDto("Digital", "Mouse", "Wireless Mouse", 0.92),
                Map.of("Brand", new AttributeValue("Logitech", 0.9, true)),
                List.of("Logitech Wireless Mouse"),
                0.88);

        RecognitionResult result = RecognitionResultValidator.validate(input);

        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.category().level3()).isEqualTo("Wireless Mouse");
        assertThat(result.category().confidence()).isEqualTo(0.92);
        assertThat(result.attributes().get("Brand").value()).isEqualTo("Logitech");
        assertThat(result.attributes().get("Brand").confidence()).isEqualTo(0.9);
        assertThat(result.keywords()).containsExactly("Logitech Wireless Mouse");
        assertThat(result.overallConfidence()).isEqualTo(0.88);
    }

    @Test
    void clampsConfidenceAndCleansBlankValues() {
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        attributes.put(" ", new AttributeValue("ignored", 0.8, true));
        attributes.put("Brand", new AttributeValue("unknown", -2.0, true));
        attributes.put("Model", new AttributeValue("x".repeat(160), 3.0, false));

        RecognitionResult input = new RecognitionResult(
                null,
                new CategoryDto(" ", "unknown", "y".repeat(160), 2.0),
                attributes,
                List.of(" ", "unknown", "z".repeat(160)),
                Double.NaN);

        RecognitionResult result = RecognitionResultValidator.validate(input);

        assertThat(result.sessionId()).isEmpty();
        assertThat(result.category().level1()).isEqualTo("\u672a\u77e5");
        assertThat(result.category().level2()).isEqualTo("\u672a\u77e5");
        assertThat(result.category().level3()).hasSize(100);
        assertThat(result.category().confidence()).isEqualTo(1.0);
        assertThat(result.attributes()).doesNotContainKey(" ");
        assertThat(result.attributes().get("Brand").value()).isEqualTo("\u672a\u77e5");
        assertThat(result.attributes().get("Brand").confidence()).isEqualTo(0.0);
        assertThat(result.attributes().get("Model").value()).hasSize(100);
        assertThat(result.attributes().get("Model").confidence()).isEqualTo(1.0);
        assertThat(result.keywords()).containsExactly("z".repeat(100));
        assertThat(result.overallConfidence()).isEqualTo(1.0);
    }

    @Test
    void fillsKeywordsFromAttributesThenCategoryWhenMissing() {
        RecognitionResult fromAttributes = new RecognitionResult(
                "",
                new CategoryDto("Digital", "Mouse", "Wireless Mouse", 0.7),
                Map.of("Brand", new AttributeValue("Logitech", 0.9, true)),
                List.of(),
                0.7);

        RecognitionResult attrResult = RecognitionResultValidator.validate(fromAttributes);

        assertThat(attrResult.keywords()).containsExactly("Logitech");

        RecognitionResult fromCategory = new RecognitionResult(
                "",
                new CategoryDto("Digital", "Mouse", "Wireless Mouse", 0.7),
                Map.of(),
                null,
                0.7);

        RecognitionResult categoryResult = RecognitionResultValidator.validate(fromCategory);

        assertThat(categoryResult.keywords()).containsExactly("Wireless Mouse");
    }

    @Test
    void nullResultReturnsFallback() {
        RecognitionResult result = RecognitionResultValidator.validate(null);

        assertThat(result.sessionId()).isEmpty();
        assertThat(result.category().level1()).isEqualTo("\u672a\u77e5");
        assertThat(result.attributes()).isEmpty();
        assertThat(result.keywords()).isEmpty();
        assertThat(result.overallConfidence()).isEqualTo(0.0);
    }
}
