package com.visioncart.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NlpFilterRequest(
        @NotBlank @Size(max = 64) String sessionId,
        @NotBlank @Size(max = 500) String userInput,
        NlpParseRequest.NlpContext context
) {}
