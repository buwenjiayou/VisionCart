package com.visioncart.service.recognition;

import com.visioncart.api.dto.RecognitionResult;

import java.time.Instant;

public record RecognitionTaskResult(
        String sessionId,
        String status,
        RecognitionResult result,
        String error,
        Instant createdAt,
        Instant completedAt
) {
}
