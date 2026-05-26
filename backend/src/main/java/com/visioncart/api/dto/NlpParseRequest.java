package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record NlpParseRequest(
        @Size(max = 64) String sessionId,
        @NotBlank @Size(max = 500) String userInput,
        NlpContext context
) {
    public record NlpContext(String productName, String category, List<NlpTurn> history) {
    }

    public record NlpTurn(String userInput, SearchFilter filter) {
    }
}
