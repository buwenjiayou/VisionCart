package com.visioncart.service.recognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.AttributeCorrectionRequest;
import com.visioncart.api.dto.AttributeCorrectionResult;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.CategoryDto;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.api.dto.SearchFilter;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void localFilterCorrectionDoesNotPersistRecognitionAttributes() {
        RecognitionHistoryRepository historyRepository = mock(RecognitionHistoryRepository.class);
        NlpConversationManager conversationManager = mock(NlpConversationManager.class);
        CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
        CandidateFilterService filterService = mock(CandidateFilterService.class);
        SearchOrchestrator searchOrchestrator = mock(SearchOrchestrator.class);
        AsyncRecognitionTaskManager localTaskManager = mock(AsyncRecognitionTaskManager.class);
        SessionHistoryService sessionHistoryService = mock(SessionHistoryService.class);
        SafeActionExecutor safeActionExecutor = mock(SafeActionExecutor.class);
        SessionContextService sessionContextService = mock(SessionContextService.class);
        AttributeCorrectionService localService = new AttributeCorrectionService(
                historyRepository,
                conversationManager,
                sessionCache,
                filterService,
                searchOrchestrator,
                new ObjectMapper(),
                localTaskManager,
                sessionHistoryService,
                safeActionExecutor,
                sessionContextService);

        ProductCard product = product("p1");
        Map<String, AttributeValue> taskAttributes = Map.of(
                "品牌", new AttributeValue("Apple", 0.90, true));
        RecognitionResult recognition = new RecognitionResult(
                "session-local",
                new CategoryDto("数码", "手机", "", 0.90),
                taskAttributes,
                List.of("手机"),
                0.90);

        when(localTaskManager.belongsToUser("session-local", 1L)).thenReturn(true);
        when(localTaskManager.getStatus("session-local")).thenReturn(new RecognitionTaskResult(
                "session-local", "COMPLETED", recognition, null, Instant.now(), Instant.now()));
        when(historyRepository.findBySessionIdAndUserId("session-local", 1L)).thenReturn(Optional.empty());
        when(conversationManager.getFilterState("session-local")).thenReturn(SearchFilter.empty());
        when(sessionCache.getBestCandidates("session-local")).thenReturn(List.of(product));
        when(filterService.filter(anyList(), any(), anyMap(), eq(50), eq(1)))
                .thenReturn(new CandidateFilterService.FilterResult(List.of(product), 1, 1, false, false));
        when(sessionContextService.resolve("session-local", 1L))
                .thenReturn(SessionContextService.SessionContext.empty("session-local"));
        when(safeActionExecutor.execute(eq("session-local"), anyList(), anyList(), any(), any(), any(), anyList(),
                any(), eq("filter_correction"), any()))
                .thenReturn(new SafeActionExecutor.SafeActionResult(
                        List.of(product), SearchFilter.empty(), List.of(), List.of(),
                        true, false, null, List.of(), List.of(), true));

        ApiResponse<AttributeCorrectionResult> response = localService.correctAttribute(
                "session-local",
                new AttributeCorrectionRequest("session-local", "平台", null, "淘宝"),
                1L);

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.data().updatedAttributes()).isNull();
        assertThat(response.data().productsUpdated()).isTrue();
        assertThat(response.data().previousAttributes()).isNull();
        verify(historyRepository, never()).save(any());
        verify(localTaskManager, never()).updateResult(eq("session-local"), any());
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

    private ProductCard product(String id) {
        return new ProductCard(
                id,
                "测试商品",
                "https://img.example/" + id + ".jpg",
                BigDecimal.valueOf(99),
                null,
                "淘宝",
                false,
                "测试店铺",
                4.8,
                100,
                0.9,
                List.of(),
                "https://detail.example/" + id,
                "Apple",
                "item_rating",
                "100 sold"
        );
    }
}
