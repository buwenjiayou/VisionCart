package com.visioncart.service.recognition;

import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.api.dto.RecognitionCandidate;

import java.time.Instant;
import java.util.List;

public record RecognitionTaskResult(
        String sessionId,
        String status,
        RecognitionResult result,
        String error,
        Instant createdAt,
        Instant completedAt,
        List<RecognitionCandidate> candidates,
        String progressStep,
        String confidenceHint
) {
    public RecognitionTaskResult(String sessionId,
                                 String status,
                                 RecognitionResult result,
                                 String error,
                                 Instant createdAt,
                                 Instant completedAt) {
        this(sessionId, status, result, error, createdAt, completedAt, null, null, null);
    }

    public RecognitionTaskResult(String sessionId,
                                 String status,
                                 RecognitionResult result,
                                 String error,
                                 Instant createdAt,
                                 Instant completedAt,
                                 List<RecognitionCandidate> candidates) {
        this(sessionId, status, result, error, createdAt, completedAt, candidates, null, null);
    }

    public RecognitionTaskResult(String sessionId,
                                 String status,
                                 RecognitionResult result,
                                 String error,
                                 Instant createdAt,
                                 Instant completedAt,
                                 List<RecognitionCandidate> candidates,
                                 String progressStep) {
        this(sessionId, status, result, error, createdAt, completedAt, candidates, progressStep, null);
    }
}
