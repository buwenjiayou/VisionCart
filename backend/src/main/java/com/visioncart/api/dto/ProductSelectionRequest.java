package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductSelectionRequest(
        @NotBlank String candidateId,
        @Size(max = 20) String regionMode
) {
    public ProductSelectionRequest(String candidateId) {
        this(candidateId, null);
    }
}
