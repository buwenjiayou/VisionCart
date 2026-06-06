package com.visioncart.service.recognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.CategoryDto;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.context.SessionContextService;
import com.visioncart.service.filter.SafeActionExecutor;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.search.CandidateFilterService;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.search.SearchOrchestrator;
import com.visioncart.service.search.SearchTextUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttributeCorrectionServiceTest {

    private AsyncRecognitionTaskManager taskManager;
    private AttributeCorrectionService service;

    @BeforeEach
    void setUp() {
        taskManager = mock(AsyncRecognitionTaskManager.class);
        service = new AttributeCorrectionService(
                mock(RecognitionHistoryRepository.class),
                mock(NlpConversationManager.class),
                mock(CandidateSessionCache.class),
                mock(CandidateFilterService.class),
                mock(SearchOrchestrator.class),
                new ObjectMapper(),
                taskManager,
                mock(SessionHistoryService.class),
                mock(SafeActionExecutor.class),
                mock(SessionContextService.class));
    }

    @Test
    void nonFilterAttributesDefaultToRecallRefresh() throws Exception {
        assertThat(correctionMode("存储")).isEqualTo("RECALL_REFRESH");
        assertThat(correctionMode("型号")).isEqualTo("RECALL_REFRESH");
        assertThat(correctionMode("平台")).isEqualTo("LOCAL_FILTER");
        assertThat(correctionMode("rating_min")).isEqualTo("LOCAL_FILTER");
    }

    @Test
    void buildSearchAttributesPreservesRawKeysAndCanonicalAliases() throws Exception {
        Map<String, AttributeValue> attributes = Map.of(
                "存储", new AttributeValue("256GB", 0.95, true),
                "颜色", new AttributeValue("黑色", 0.90, true),
                "商品主体", new AttributeValue("无线鼠标", 0.90, true)
        );

        Map<String, String> searchAttributes = buildSearchAttributes(attributes);

        assertThat(searchAttributes).containsEntry("存储", "256GB");
        assertThat(searchAttributes).containsEntry(SearchTextUtils.ATTR_COLOR, "黑色");
        assertThat(searchAttributes).containsEntry(SearchTextUtils.ATTR_KEYWORD, "无线鼠标");
        assertThat(searchAttributes.get(SearchTextUtils.ATTR_KEYWORDS)).contains("256GB", "黑色", "无线鼠标");
    }

    @Test
    void loadCurrentAttributesFallsBackToActiveTask() throws Exception {
        Map<String, AttributeValue> taskAttributes = Map.of(
                "商品主体", new AttributeValue("耳机", 0.91, true),
                "型号", new AttributeValue("AirPods Pro", 0.88, false)
        );
        RecognitionResult recognition = new RecognitionResult(
                "session-1",
                new CategoryDto("数码", "耳机", "", 0.90),
                taskAttributes,
                List.of("耳机"),
                0.90);
        when(taskManager.getStatus("session-1")).thenReturn(new RecognitionTaskResult(
                "session-1", "COMPLETED", recognition, null, Instant.now(), Instant.now()));

        Map<String, AttributeValue> loaded = loadCurrentAttributes("session-1");

        assertThat(loaded).containsEntry("型号", taskAttributes.get("型号"));
        assertThat(loaded).containsEntry("商品主体", taskAttributes.get("商品主体"));
    }

    private String correctionMode(String field) throws Exception {
        Method method = AttributeCorrectionService.class.getDeclaredMethod("getCorrectionMode", String.class);
        method.setAccessible(true);
        return String.valueOf(method.invoke(service, field));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> buildSearchAttributes(Map<String, AttributeValue> attributes) throws Exception {
        Method method = AttributeCorrectionService.class.getDeclaredMethod("buildSearchAttributes", Map.class);
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(service, attributes);
    }

    @SuppressWarnings("unchecked")
    private Map<String, AttributeValue> loadCurrentAttributes(String sessionId) throws Exception {
        Method method = AttributeCorrectionService.class.getDeclaredMethod("loadCurrentAttributes", String.class, Optional.class);
        method.setAccessible(true);
        return (Map<String, AttributeValue>) method.invoke(service, sessionId, Optional.empty());
    }
}
