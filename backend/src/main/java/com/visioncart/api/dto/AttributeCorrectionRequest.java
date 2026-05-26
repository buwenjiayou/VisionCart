package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AttributeCorrectionRequest(
        @NotBlank @Size(max = 64) String sessionId,
        @NotBlank @Size(max = 50) String attribute,
        @Size(max = 200) String oldValue,
        @NotBlank @Size(max = 200) String newValue
) {
}
