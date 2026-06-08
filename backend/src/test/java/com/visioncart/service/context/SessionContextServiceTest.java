package com.visioncart.service.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.search.CandidateSessionCache;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionContextServiceTest {

    private final RecognitionHistoryRepository historyRepository = mock(RecognitionHistoryRepository.class);
    private final CandidateSessionCache sessionCache = mock(CandidateSessionCache.class);
    private final SessionContextService service = new SessionContextService(
            historyRepository, sessionCache, new ObjectMapper());

    @Test
    void prefersHistoryOverCandidates() {
        RecognitionHistory history = new RecognitionHistory();
        history.setSessionId("s1");
        history.setUserId(7L);
        history.setCategoryJson("{\"level1\":\"electronics\",\"level2\":\"phone\",\"level3\":\"phone\"}");
        history.setKeywords("iPhone 15,phone");
        history.setAttributesJson("{\"brand\":{\"value\":\"Apple\",\"confidence\":0.9,\"verified\":true}}");

        when(historyRepository.findBySessionIdAndUserId("s1", 7L)).thenReturn(Optional.of(history));
        when(sessionCache.getBestCandidates("s1")).thenReturn(List.of(product("p1", "Xiaomi phone", "xiaomi")));

        SessionContextService.SessionContext context = service.resolve("s1", 7L, "client-category");

        assertThat(context.resolved()).isTrue();
        assertThat(context.normalizedCategory()).isEqualTo("phone");
        assertThat(context.mainProduct()).isEqualTo("iPhone 15");
        assertThat(context.attributes()).containsEntry("brand", "Apple");
    }

    @Test
    void fallsBackToCandidatesThenClientCategory() {
        when(historyRepository.findBySessionIdAndUserId("s2", 7L)).thenReturn(Optional.empty());
        when(sessionCache.getBestCandidates("s2")).thenReturn(List.of(product("p1", "Fast power bank", "Anker")));
        when(historyRepository.findBySessionIdAndUserId("s3", 7L)).thenReturn(Optional.empty());
        when(sessionCache.getBestCandidates("s3")).thenReturn(List.of());

        SessionContextService.SessionContext candidateContext = service.resolve("s2", 7L, null);
        SessionContextService.SessionContext clientContext = service.resolve("s3", 7L, "charger");

        assertThat(candidateContext.resolved()).isTrue();
        assertThat(candidateContext.attributes()).containsEntry("brand", "Anker");
        assertThat(clientContext.resolved()).isTrue();
        assertThat(clientContext.normalizedCategory()).isEqualTo("charger");
    }

    private ProductCard product(String id, String title, String brand) {
        return new ProductCard(id, title, "", BigDecimal.TEN, null, "taobao", false,
                "shop", 4.8, 100, 0.9, List.of(), "", brand,
                "none", null, "power_bank", "main");
    }
}
