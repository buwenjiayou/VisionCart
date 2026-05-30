package com.visioncart.service.recognition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.AttributeCorrectionRequest;
import com.visioncart.api.dto.AttributeCorrectionResult;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.api.dto.SearchRequest;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.service.ai.HashUtils;
import com.visioncart.domain.RecognitionFeedback;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionFeedbackRepository;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.search.SearchOrchestrator;
import com.visioncart.service.search.SearchTextUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    public AttributeCorrectionResult correct(AttributeCorrectionRequest request, Long userId) {
        RecognitionHistory history = (userId == null
                ? historyRepository.findById(request.sessionId())
                : historyRepository.findBySessionIdAndUserId(request.sessionId(), userId))
                .orElseThrow(() -> new IllegalArgumentException("识别会话不存在或已过期"));
        Map<String, AttributeValue> attributes = readAttributes(history.getAttributesJson());
        attributes.put(request.attribute(), new AttributeValue(request.newValue(), 1.0, true));

        history.setAttributesJson(toJson(attributes));
        historyRepository.save(history);

        RecognitionFeedback feedback = new RecognitionFeedback();
        feedback.setSessionId(request.sessionId());
        feedback.setImageHash(history.getImageHash());
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
        ), userId);
        return new AttributeCorrectionResult(attributes, searchResult);
    }

    public AttributeCorrectionResult correct(AttributeCorrectionRequest request) {
        return correct(request, null);
    }

    public List<RecognitionHistory> latestHistory() {
        return historyRepository.findTop20ByOrderByCreatedAtDesc();
    }

    public List<String> attributeOptions(String category, String attribute, String sessionId, Long userId, List<String> defaults) {
        LinkedHashSet<String> options = new LinkedHashSet<>();
        if (SearchTextUtils.ATTR_BRAND.equals(attribute) && sessionId != null && !sessionId.isBlank()) {
            (userId == null
                    ? historyRepository.findById(sessionId)
                    : historyRepository.findBySessionIdAndUserId(sessionId, userId)).ifPresent(history -> {
                Map<String, AttributeValue> attributes = readAttributes(history.getAttributesJson());
                Map<String, String> flatAttributes = new LinkedHashMap<>();
                attributes.forEach((key, value) -> flatAttributes.put(key, value.value()));
                try {
                    SearchResult searchResult = searchOrchestrator.search(new SearchRequest(
                            sessionId, flatAttributes, null, 1, 20, "attribute_options"), userId);
                    searchResult.products().stream()
                            .map(this::brandCandidate)
                            .map(SearchTextUtils::useful)
                            .filter(value -> !value.isBlank())
                            .forEach(options::add);
                } catch (Exception ignored) {
                    // Fallback to static options below.
                }
            });
        }
        if (defaults != null) {
            defaults.stream()
                    .map(SearchTextUtils::useful)
                    .filter(value -> !value.isBlank())
                    .forEach(options::add);
        }
        return List.copyOf(options);
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

    private String brandCandidate(ProductCard product) {
        String brand = SearchTextUtils.useful(product.brand());
        if (!brand.isBlank()) {
            return brand;
        }
        return SearchTextUtils.inferBrand(product.title(), product.shopName());
    }

    private RecognitionResult withSessionId(RecognitionResult result, String sessionId) {
        return new RecognitionResult(
                sessionId,
                result.category(),
                result.attributes(),
                result.keywords(),
                result.overallConfidence(),
                result.platformStats()
        );
    }
}
