package com.visioncart.api.dto;

import java.util.List;
import java.util.Map;

public record RecognitionResult(
        String sessionId,
        CategoryDto category,
        Map<String, AttributeValue> attributes,
        List<String> keywords,
        double overallConfidence
) {
}
