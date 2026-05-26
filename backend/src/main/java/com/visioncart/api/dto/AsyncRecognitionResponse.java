package com.visioncart.api.dto;

public record AsyncRecognitionResponse(
        String sessionId,
        String status,
        String websocketTopic,
        long estimatedMs
) {
}
