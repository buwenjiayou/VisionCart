package com.visioncart.api.dto;

import java.util.Map;

public record AttributeCorrectionResult(Map<String, AttributeValue> updatedAttributes, SearchResult products) {
}
