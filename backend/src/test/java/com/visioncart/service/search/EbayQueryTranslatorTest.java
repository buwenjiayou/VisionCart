package com.visioncart.service.search;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbayQueryTranslatorTest {

    private final EbayQueryTranslator translator = new EbayQueryTranslator(unavailableChatClient());

    @Test
    void localFallbackTranslatesCoreChineseProductTerms() {
        assertThat(translator.translateForEbay("鼠标")).isEqualTo("mouse");
    }

    @Test
    void localFallbackPreservesPowerBankProductAndAppearance() {
        assertThat(translator.translateForEbay("透明外壳 充电宝"))
                .contains("transparent shell")
                .contains("power bank")
                .doesNotContain("透明");
    }

    @Test
    void localFallbackKeepsSpecsForPowerBank() {
        assertThat(translator.translateForEbay("20Ah 充电宝"))
                .contains("20Ah")
                .contains("power bank");
    }

    @Test
    void sanitizerDropsExplanationsJsonAndLongText() {
        String cleaned = translator.sanitizeEnglishQuery("""
                {"query":"transparent power bank"}
                extra explanation that should be ignored
                """);

        assertThat(cleaned).doesNotContain("{", "}", "\n");
        assertThat(cleaned.length()).isLessThanOrEqualTo(80);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatClient.Builder> unavailableChatClient() {
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
