package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ProductSelectionRequest(
        @NotBlank String candidateId
) {
}
