package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AttributeCorrectionRequest(
        @NotBlank String sessionId,
        @NotBlank String attribute,
        String oldValue,
        @NotBlank String newValue
) {
}
