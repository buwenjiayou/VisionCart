package com.visioncart.service.recognition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.AttributeCorrectionRequest;
import com.visioncart.api.dto.AttributeCorrectionResult;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.api.dto.SearchRequest;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.service.ai.HashUtils;
import com.visioncart.domain.RecognitionFeedback;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionFeedbackRepository;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.search.SearchOrchestrator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RecognitionService {
    private final VisionModelService visionClient;
    private final RecognitionHistoryRepository historyRepository;
    private final RecognitionFeedbackRepository feedbackRepository;
    private final SearchOrchestrator searchOrchestrator;
    private final ObjectMapper objectMapper;

    public RecognitionService(VisionModelService visionClient,
                              RecognitionHistoryRepository historyRepository,
                              RecognitionFeedbackRepository feedbackRepository,
                              SearchOrchestrator searchOrchestrator,
                              ObjectMapper objectMapper) {
        this.visionClient = visionClient;
        this.historyRepository = historyRepository;
        this.feedbackRepository = feedbackRepository;
        this.searchOrchestrator = searchOrchestrator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RecognitionResult analyze(MultipartFile image, String region) {
        RecognitionResult result = visionClient.analyze(image, region);
        RecognitionHistory history = new RecognitionHistory();
        history.setSessionId(result.sessionId());
        history.setImageUrl("upload://" + result.sessionId());
        history.setImageHash(HashUtils.sha256Hex(image.getOriginalFilename() + ":" + image.getSize()));
        history.setCategoryJson(toJson(result.category()));
        history.setAttributesJson(toJson(result.attributes()));
        history.setKeywords(String.join(",", result.keywords()));
        history.setConfidence(result.overallConfidence());
        history.setCreatedAt(Instant.now());
        historyRepository.save(history);
        return result;
    }

    @Transactional
    public AttributeCorrectionResult correct(AttributeCorrectionRequest request) {
        RecognitionHistory history = historyRepository.findById(request.sessionId()).orElse(null);
        Map<String, AttributeValue> attributes = readAttributes(history == null ? null : history.getAttributesJson());
        attributes.put(request.attribute(), new AttributeValue(request.newValue(), 1.0, true));

        if (history != null) {
            history.setAttributesJson(toJson(attributes));
            historyRepository.save(history);
        }

        RecognitionFeedback feedback = new RecognitionFeedback();
        feedback.setSessionId(request.sessionId());
        feedback.setAttributeName(request.attribute());
        feedback.setVlmOutput(request.oldValue());
        feedback.setUserCorrection(request.newValue());
        feedbackRepository.save(feedback);

        Map<String, String> flatAttributes = new LinkedHashMap<>();
        attributes.forEach((key, value) -> flatAttributes.put(key, value.value()));
        SearchResult searchResult = searchOrchestrator.search(new SearchRequest(
                request.sessionId(),
                flatAttributes,
                null,
                1,
                20,
                "app"
        ));
        return new AttributeCorrectionResult(attributes, searchResult);
    }

    public List<RecognitionHistory> latestHistory() {
        return historyRepository.findTop20ByOrderByCreatedAtDesc();
    }

    private Map<String, AttributeValue> readAttributes(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, AttributeValue.class));
        } catch (JsonProcessingException error) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }
}
