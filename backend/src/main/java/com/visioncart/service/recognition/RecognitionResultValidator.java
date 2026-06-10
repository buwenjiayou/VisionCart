package com.visioncart.service.recognition;

import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.CategoryDto;
import com.visioncart.api.dto.PlatformPriceStat;
import com.visioncart.api.dto.RecognitionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class RecognitionResultValidator {

    private static final String UNKNOWN = "\u672a\u77e5";
    private static final int MAX_STRING_LENGTH = 100;
    private static final int MAX_ATTRIBUTES = 30;
    private static final int MAX_KEYWORDS = 5;

    private RecognitionResultValidator() {
    }

    public static RecognitionResult validate(RecognitionResult result) {
        if (result == null) {
            return fallback();
        }

        CategoryDto category = validateCategory(result.category());
        Map<String, AttributeValue> attributes = validateAttributes(result.attributes());
        List<String> keywords = validateKeywords(result.keywords(), attributes, category);
        double overallConfidence = clampConfidence(result.overallConfidence(), category.confidence());
        List<PlatformPriceStat> platformStats = result.platformStats();

        return new RecognitionResult(
                cleanSessionId(result.sessionId()),
                category,
                attributes,
                keywords,
                overallConfidence,
                platformStats);
    }

    private static RecognitionResult fallback() {
        return new RecognitionResult(
                "",
                new CategoryDto(UNKNOWN, UNKNOWN, UNKNOWN, 0.0),
                Map.of(),
                List.of(),
                0.0);
    }

    private static CategoryDto validateCategory(CategoryDto category) {
        if (category == null) {
            return new CategoryDto(UNKNOWN, UNKNOWN, UNKNOWN, 0.0);
        }
        return new CategoryDto(
                defaultText(category.level1(), UNKNOWN),
                defaultText(category.level2(), UNKNOWN),
                defaultText(category.level3(), UNKNOWN),
                clampConfidence(category.confidence(), 0.0));
    }

    private static Map<String, AttributeValue> validateAttributes(Map<String, AttributeValue> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, AttributeValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, AttributeValue> entry : attributes.entrySet()) {
            if (result.size() >= MAX_ATTRIBUTES) {
                break;
            }
            String key = clean(entry.getKey());
            if (key == null) {
                continue;
            }
            AttributeValue value = validateAttributeValue(entry.getValue());
            result.put(key, value);
        }
        return result;
    }

    private static AttributeValue validateAttributeValue(AttributeValue attribute) {
        if (attribute == null) {
            return new AttributeValue(UNKNOWN, 0.0, false);
        }
        return new AttributeValue(
                defaultText(attribute.value(), UNKNOWN),
                clampConfidence(attribute.confidence(), 0.0),
                attribute.verified());
    }

    private static List<String> validateKeywords(List<String> keywords,
                                                 Map<String, AttributeValue> attributes,
                                                 CategoryDto category) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (keywords != null) {
            for (String keyword : keywords) {
                String clean = clean(keyword);
                if (clean != null) {
                    result.add(clean);
                }
                if (result.size() >= MAX_KEYWORDS) {
                    break;
                }
            }
        }

        if (result.isEmpty() && attributes != null) {
            for (AttributeValue value : attributes.values()) {
                String text = clean(value == null ? null : value.value());
                if (text != null && !UNKNOWN.equals(text)) {
                    result.add(text);
                }
                if (result.size() >= MAX_KEYWORDS) {
                    break;
                }
            }
        }

        if (result.isEmpty() && category != null) {
            String level3 = clean(category.level3());
            if (level3 != null && !UNKNOWN.equals(level3)) {
                result.add(level3);
            }
        }

        return new ArrayList<>(result).stream().limit(MAX_KEYWORDS).toList();
    }

    private static String cleanSessionId(String value) {
        String text = clean(value);
        return text == null ? "" : text;
    }

    private static String defaultText(String value, String fallback) {
        String text = clean(value);
        return text == null ? fallback : text;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text) || "unknown".equalsIgnoreCase(text)) {
            return null;
        }
        return text.length() > MAX_STRING_LENGTH ? text.substring(0, MAX_STRING_LENGTH) : text;
    }

    private static double clampConfidence(double value, double fallback) {
        double confidence = Double.isFinite(value) ? value : fallback;
        return Math.max(0.0, Math.min(1.0, confidence));
    }
}
