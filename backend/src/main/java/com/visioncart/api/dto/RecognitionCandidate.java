package com.visioncart.api.dto;

import java.util.List;

public record RecognitionCandidate(
        String candidateId,
        List<Integer> bbox,
        String category,
        String brand,
        double confidence,
        String previewImageUrl
) {
}
