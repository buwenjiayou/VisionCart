package com.visioncart.api.dto;

public record NlpParseResult(SearchFilter filter, double confidence, boolean fromCache, String decision, String message) {
}
