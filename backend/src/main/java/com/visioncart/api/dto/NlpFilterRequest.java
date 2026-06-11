package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NlpFilterRequest(
        @NotBlank @Size(max = 64) String sessionId,
        @NotBlank @Size(max = 500) String userInput,
        NlpParseRequest.NlpContext context,
        @Size(max = 20) String regionMode
) {
    public NlpFilterRequest(String sessionId, String userInput, NlpParseRequest.NlpContext context) {
        this(sessionId, userInput, context, null);
    }
}
