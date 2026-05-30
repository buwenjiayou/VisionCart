package com.visioncart.service.recognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.*;
import com.visioncart.domain.RecognitionFeedback;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionFeedbackRepository;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.search.SearchOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecognitionServiceTest {

    private VisionModelService visionClient;
    private RecognitionHistoryRepository historyRepository;
    private RecognitionFeedbackRepository feedbackRepository;
    private SearchOrchestrator searchOrchestrator;
    private ObjectMapper objectMapper;
    private RecognitionService recognitionService;

    @BeforeEach
    void setUp() {
        visionClient = mock(VisionModelService.class);
        historyRepository = mock(RecognitionHistoryRepository.class);
        feedbackRepository = mock(RecognitionFeedbackRepository.class);
        searchOrchestrator = mock(SearchOrchestrator.class);
        objectMapper = new ObjectMapper();

        recognitionService = new RecognitionService(
                visionClient, historyRepository, feedbackRepository, searchOrchestrator, objectMapper);
    }

    @Test
    void correctUpdatesHistoryAndSavesFeedback() {
        RecognitionHistory history = new RecognitionHistory();
        history.setSessionId("session-1");
        Map<String, AttributeValue> existingAttrs = new LinkedHashMap<>();
        existingAttrs.put("品牌", new AttributeValue("未知", 0.3, false));
        history.setAttributesJson(toJson(existingAttrs));

        when(historyRepository.findById("session-1")).thenReturn(Optional.of(history));
        when(searchOrchestrator.search(any())).thenReturn(new SearchResult(0, List.of(), List.of(), List.of()));

        AttributeCorrectionRequest request = new AttributeCorrectionRequest(
                "session-1", "品牌", "未知", "Nike");

        AttributeCorrectionResult result = recognitionService.correct(request);

        assertThat(result.updatedAttributes()).containsKey("品牌");
        assertThat(result.updatedAttributes().get("品牌").value()).isEqualTo("Nike");
        assertThat(result.updatedAttributes().get("品牌").verified()).isTrue();
        verify(feedbackRepository).save(any(RecognitionFeedback.class));
        verify(historyRepository).save(history);
    }

    @Test
    void correctHandlesMissingHistory() {
        when(historyRepository.findById("missing")).thenReturn(Optional.empty());
        when(searchOrchestrator.search(any())).thenReturn(new SearchResult(0, List.of(), List.of(), List.of()));

        AttributeCorrectionRequest request = new AttributeCorrectionRequest(
                "missing", "品牌", "未知", "Nike");

        assertThatThrownBy(() -> recognitionService.correct(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("识别会话不存在");
        verify(feedbackRepository, never()).save(any(RecognitionFeedback.class));
    }

    @Test
    void latestHistoryReturnsRecentItems() {
        RecognitionHistory h1 = new RecognitionHistory();
        h1.setSessionId("s1");
        RecognitionHistory h2 = new RecognitionHistory();
        h2.setSessionId("s2");
        when(historyRepository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of(h1, h2));

        List<RecognitionHistory> result = recognitionService.latestHistory();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSessionId()).isEqualTo("s1");
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
