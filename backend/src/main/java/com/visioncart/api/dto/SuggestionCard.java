package com.visioncart.api.dto;

public record SuggestionCard(
        String id,
        String title,
        String subtitle,
        String icon,
        String action,
        int priority
) {
}
