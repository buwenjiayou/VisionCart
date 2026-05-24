package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record NlpParseRequest(
        String sessionId,
        @NotBlank String userInput,
        NlpContext context
) {
    public record NlpContext(String productName, String category, List<NlpTurn> history) {
    }

    public record NlpTurn(String userInput, SearchFilter filter) {
    }
}
