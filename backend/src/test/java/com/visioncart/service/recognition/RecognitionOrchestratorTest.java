package com.visioncart.service.recognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.RecognitionCandidate;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.api.dto.CategoryDto;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.search.RegionResolver;
import com.visioncart.service.search.SearchOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecognitionOrchestratorTest {

    private ImageProcessor imageProcessor;
    private VisionModelService visionClient;
    private AsyncRecognitionTaskManager taskManager;
    private RecognitionHistoryRepository historyRepository;
    private SimpMessagingTemplate messagingTemplate;
    private ObjectMapper objectMapper;
    private ExecutorService executorService;
    private VisionCartProperties properties;
    private RecognitionImageStorage imageStorage;
    private SearchOrchestrator searchOrchestrator;
    private RegionResolver regionResolver;
    private RecognitionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        imageProcessor = mock(ImageProcessor.class);
        visionClient = mock(VisionModelService.class);
        taskManager = mock(AsyncRecognitionTaskManager.class);
        historyRepository = mock(RecognitionHistoryRepository.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        objectMapper = new ObjectMapper();
        executorService = Executors.newSingleThreadExecutor();
        properties = new VisionCartProperties();
        properties.getRecognition().setTimeoutMs(5000L);
        properties.getRecognition().setRetryCount(1);
        imageStorage = mock(RecognitionImageStorage.class);
        when(imageStorage.historyImageUrl(anyString())).thenAnswer(invocation ->
                "/api/v1/history/" + invocation.getArgument(0, String.class) + "/image");
        searchOrchestrator = mock(SearchOrchestrator.class);
        regionResolver = mock(RegionResolver.class);
        when(regionResolver.isDomestic()).thenReturn(true);

        orchestrator = new RecognitionOrchestrator(
                imageProcessor, visionClient, taskManager, historyRepository,
                messagingTemplate, objectMapper, executorService, properties, imageStorage, searchOrchestrator, regionResolver
        );
    }

    @Test
    void shouldSubmitAsyncAndReturnProcessingStatus() {
        when(imageProcessor.process(any())).thenReturn(new byte[]{0});

        org.springframework.mock.web.MockMultipartFile image =
                new org.springframework.mock.web.MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{0});

        var response = orchestrator.submitAsync(image, "整张图", 1L);

        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.estimatedMs()).isGreaterThan(0);
    }

    @Test
    void shouldCompleteRecognitionOnSuccess() {
        RecognitionResult expectedResult = new RecognitionResult(
                "test-session",
                new CategoryDto("服装", "外套", "夹克", 0.9),
                Map.of(
                        "品牌", new AttributeValue("Nike", 0.95, true),
                        "颜色", new AttributeValue("黑色", 0.9, true),
                        "款式", new AttributeValue("运动", 0.85, false)
                ),
                List.of("Nike", "夹克"),
                0.88
        );

        when(imageProcessor.process(any())).thenReturn(new byte[]{0});
        when(visionClient.analyze(any(byte[].class), anyString(), anyString())).thenReturn(expectedResult);

        org.springframework.mock.web.MockMultipartFile image =
                new org.springframework.mock.web.MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{0});

        var response = orchestrator.submitAsync(image, "整张图", 1L);

        // Wait for async processing
        verify(taskManager, timeout(5000)).markCompleted(eq(response.sessionId()), any(RecognitionResult.class));
    }

    @Test
    void shouldPauseForSelectionWhenMultipleProductsDetected() {
        when(imageProcessor.process(any())).thenReturn(new byte[]{0});
        when(imageProcessor.cropToJpeg(any(), any())).thenReturn(
                new ImageProcessor.CroppedImage(new byte[]{1, 2, 3}, 80, 80, 6400)
        );
        when(visionClient.supportsTwoStageRecognition()).thenReturn(true);
        when(visionClient.detectProducts(any(byte[].class), anyString(), anyString())).thenReturn(List.of(
                new RecognitionCandidate("raw-1", List.of(0, 0, 80, 80), "鼠标", "罗技", 0.92, null),
                new RecognitionCandidate("raw-2", List.of(10, 10, 70, 70), "键盘", null, 0.88, null)
        ));

        org.springframework.mock.web.MockMultipartFile image =
                new org.springframework.mock.web.MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{0});

        var response = orchestrator.submitAsync(image, "整张图", 1L);

        verify(taskManager, timeout(5000)).markMultiProductPending(eq(response.sessionId()), any(), any());
    }

    @Test
    void shouldRetryOnRetryableFailure() {
        RecognitionResult expectedResult = new RecognitionResult(
                "test-session",
                new CategoryDto("未知", "未知", "未知", 0.3),
                Map.of(
                        "品牌", new AttributeValue("未知", 0.3, false),
                        "颜色", new AttributeValue("未知", 0.3, false),
                        "款式", new AttributeValue("未知", 0.3, false)
                ),
                List.of("test"),
                0.3
        );

        when(imageProcessor.process(any())).thenReturn(new byte[]{0});
        when(visionClient.analyze(any(byte[].class), anyString(), anyString()))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(expectedResult);

        org.springframework.mock.web.MockMultipartFile image =
                new org.springframework.mock.web.MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{0});

        var response = orchestrator.submitAsync(image, "整张图", 1L);

        // Should succeed after retry
        verify(taskManager, timeout(10000)).markCompleted(eq(response.sessionId()), any(RecognitionResult.class));
    }

    @Test
    void shouldUseFallbackAfterAllRetriesFail() {
        when(imageProcessor.process(any())).thenReturn(new byte[]{0});
        when(visionClient.analyze(any(byte[].class), anyString(), anyString()))
                .thenThrow(new RuntimeException("API error"));

        org.springframework.mock.web.MockMultipartFile image =
                new org.springframework.mock.web.MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{0});

        var response = orchestrator.submitAsync(image, "整张图", 1L);

        // Should complete with fallback result
        verify(taskManager, timeout(10000)).markCompleted(eq(response.sessionId()), any(RecognitionResult.class));
    }

    @Test
    void shouldHandleImageQualityException() {
        when(imageProcessor.process(any())).thenThrow(new ImageQualityException("blurry"));

        org.springframework.mock.web.MockMultipartFile image =
                new org.springframework.mock.web.MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{0});

        var response = orchestrator.submitAsync(image, "整张图", 1L);

        verify(taskManager, timeout(5000)).markFailed(eq(response.sessionId()), eq("请重新拍照：图片模糊"));
    }

    @Test
    void shouldDelegateGetStatus() {
        RecognitionTaskResult expected = new RecognitionTaskResult(
                "sess1", "PROCESSING", null, null, null, null
        );
        when(taskManager.getStatus("sess1")).thenReturn(expected);

        RecognitionTaskResult result = orchestrator.getStatus("sess1");

        assertThat(result).isEqualTo(expected);
    }
}
