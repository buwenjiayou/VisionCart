package com.visioncart.service.filter.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.service.ai.PromptLoader;
import com.visioncart.service.metrics.PerformanceMetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmProductJudgeTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final LlmProductJudge judge = new LlmProductJudge(
            provider,
            new ObjectMapper(),
            mock(PromptLoader.class),
            new VisionCartProperties(),
            aiExecutor,
            mock(PerformanceMetricsService.class));

    @Test
    void parseResultsIgnoresUnknownIdsAndClampsScores() throws Exception {
        var results = judge.parseResults("""
                {
                  "results": [
                    {
                      "product_id": "p1",
                      "score": 1.4,
                      "bucket": "GOOD_MATCH",
                      "reasons": ["礼盒装", "评分高", "外观精致", "多余理由"],
                      "risk_flags": []
                    },
                    {
                      "product_id": "unknown",
                      "score": 0.9,
                      "bucket": "GOOD_MATCH",
                      "reasons": ["不应进入结果"],
                      "risk_flags": []
                    },
                    {
                      "product_id": "p2",
                      "score": -0.2,
                      "bucket": "NOT_A_BUCKET",
                      "reasons": "信息不足",
                      "risk_flags": ["疑似配件"]
                    }
                  ]
                }
                """, Set.of("p1", "p2"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).productId()).isEqualTo("p1");
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(0).reasons()).hasSize(3);
        assertThat(results.get(1).productId()).isEqualTo("p2");
        assertThat(results.get(1).score()).isEqualTo(0.0);
        assertThat(results.get(1).bucket()).isEqualTo("REJECT");
        assertThat(results.get(1).riskFlags()).contains("疑似配件");
    }
}
