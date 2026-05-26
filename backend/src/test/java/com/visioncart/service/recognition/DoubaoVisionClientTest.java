package com.visioncart.service.recognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.service.ai.AiTraceService;
import com.visioncart.service.ai.PromptLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DoubaoVisionClientTest {

    private VisionCartProperties properties;
    private AiTraceService traceService;
    private ObjectMapper objectMapper;
    private PromptLoader promptLoader;
    private RestClient restClient;
    private DoubaoVisionClient client;

    @BeforeEach
    void setUp() {
        properties = new VisionCartProperties();
        properties.getAi().setVisionApiKey("test-api-key");
        properties.getAi().setVisionModel("doubao-vision");
        properties.getRecognition().setTimeoutMs(30000L);

        traceService = mock(AiTraceService.class);
        when(traceService.start(anyString(), any())).thenReturn("test-trace-id");

        objectMapper = new ObjectMapper();
        promptLoader = mock(PromptLoader.class);
        when(promptLoader.getPrompt("vision-recognition")).thenReturn("Analyze this image: %s");

        restClient = mock(RestClient.class);

        client = new DoubaoVisionClient(properties, traceService, objectMapper, promptLoader,
                "https://ark.cn-beijing.volces.com/api/v3", restClient);
    }

    @Test
    void shouldRejectPlaceholderApiKey() {
        properties.getAi().setVisionApiKey("replace-with-your-key");

        assertThatThrownBy(() -> client.analyze(new byte[]{0}, "image/jpeg", "整张图"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void shouldRejectBlankApiKey() {
        properties.getAi().setVisionApiKey("");

        assertThatThrownBy(() -> client.analyze(new byte[]{0}, "image/jpeg", "整张图"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void shouldRejectBlankModel() {
        properties.getAi().setVisionModel("");

        assertThatThrownBy(() -> client.analyze(new byte[]{0}, "image/jpeg", "整张图"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置");
    }

    @Test
    void shouldParseValidApiResponse() throws Exception {
        String apiResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"category\\": {\\"level1\\": \\"服装\\", \\"level2\\": \\"外套\\", \\"level3\\": \\"夹克\\", \\"confidence\\": 0.9}, \\"attributes\\": {\\"品牌\\": {\\"value\\": \\"Nike\\", \\"confidence\\": 0.95, \\"verified\\": true}, \\"颜色\\": {\\"value\\": \\"黑色\\", \\"confidence\\": 0.9, \\"verified\\": true}, \\"款式\\": {\\"value\\": \\"运动\\", \\"confidence\\": 0.85, \\"verified\\": false}}, \\"keywords\\": [\\"Nike\\", \\"夹克\\", \\"运动\\"], \\"overall_confidence\\": 0.88}"
                    }
                  }]
                }
                """;

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
        doReturn(bodySpec).when(bodySpec).contentType(any());
        doReturn(bodySpec).when(bodySpec).body(any(Map.class));
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(apiResponse);

        RecognitionResult result = client.analyze(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}, "image/png", "整张图");

        assertThat(result).isNotNull();
        assertThat(result.category().level1()).isEqualTo("服装");
        assertThat(result.category().level2()).isEqualTo("外套");
        assertThat(result.category().level3()).isEqualTo("夹克");
        assertThat(result.attributes()).containsKey("品牌");
        assertThat(result.attributes().get("品牌").value()).isEqualTo("Nike");
        assertThat(result.keywords()).contains("Nike", "夹克");
        assertThat(result.overallConfidence()).isEqualTo(0.88);
    }

    @Test
    void shouldFillDefaultAttributesWhenMissing() throws Exception {
        String apiResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"category\\": {\\"level1\\": \\"未知\\", \\"level2\\": \\"未知\\", \\"level3\\": \\"未知\\", \\"confidence\\": 0.3}, \\"attributes\\": {}, \\"keywords\\": [], \\"overall_confidence\\": 0.3}"
                    }
                  }]
                }
                """;

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
        doReturn(bodySpec).when(bodySpec).contentType(any());
        doReturn(bodySpec).when(bodySpec).body(any(Map.class));
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(apiResponse);

        RecognitionResult result = client.analyze(new byte[]{0}, "image/jpeg", "整张图");

        assertThat(result.attributes()).containsKey("品牌");
        assertThat(result.attributes()).containsKey("颜色");
        assertThat(result.attributes()).containsKey("款式");
        assertThat(result.attributes().get("品牌").value()).isEqualTo("未知");
    }

    @Test
    void shouldThrowOnEmptyApiResponse() {
        String apiResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": ""
                    }
                  }]
                }
                """;

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
        doReturn(bodySpec).when(bodySpec).contentType(any());
        doReturn(bodySpec).when(bodySpec).body(any(Map.class));
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(apiResponse);

        assertThatThrownBy(() -> client.analyze(new byte[]{0}, "image/jpeg", "整张图"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("失败");
    }

    @Test
    void shouldRecordTraceOnSuccess() throws Exception {
        String apiResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"category\\": {\\"level1\\": \\"测试\\", \\"level2\\": \\"测试\\", \\"level3\\": \\"测试\\", \\"confidence\\": 0.5}, \\"attributes\\": {}, \\"keywords\\": [\\"test\\"], \\"overall_confidence\\": 0.5}"
                    }
                  }]
                }
                """;

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
        doReturn(bodySpec).when(bodySpec).contentType(any());
        doReturn(bodySpec).when(bodySpec).body(any(Map.class));
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(apiResponse);

        client.analyze(new byte[]{0}, "image/jpeg", "整张图");

        verify(traceService).start(eq("vision.recognition"), any());
        verify(traceService).finish(eq("vision.recognition"), any(String.class), eq("doubao_vision"));
    }

    @Test
    void shouldRecordTraceOnFailure() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
        doReturn(bodySpec).when(bodySpec).contentType(any());
        doReturn(bodySpec).when(bodySpec).body(any(Map.class));
        when(bodySpec.retrieve()).thenThrow(new RuntimeException("Connection timeout"));

        assertThatThrownBy(() -> client.analyze(new byte[]{0}, "image/jpeg", "整张图"))
                .isInstanceOf(IllegalStateException.class);

        verify(traceService).fail(eq("vision.recognition"), any(String.class), any(Exception.class), eq("none"));
    }
}
