package com.visioncart.api.dto;

import com.visioncart.domain.RecognitionHistory;

import java.util.List;

public record HistoryResult(long total, List<RecognitionHistory> items) {
}
