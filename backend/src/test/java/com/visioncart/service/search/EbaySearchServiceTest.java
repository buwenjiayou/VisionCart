package com.visioncart.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.config.VisionCartProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbaySearchServiceTest {

    @Test
    void buildSearchUrlUsesEnglishTranslatedQuery() {
        VisionCartProperties properties = new VisionCartProperties();
        EbayQueryTranslator translator = new EbayQueryTranslator(unavailableChatClient());
        EbaySearchService service = new EbaySearchService(
                properties, new ObjectMapper(), translator, RestClient.create());

        String url = service.buildSearchUrl(properties.getEbay(),
                translator.translateForEbay("透明外壳 充电宝"),
                emptyFilter(), 1, 20);

        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertThat(decoded).contains("q=transparent shell power bank");
        assertThat(decoded).doesNotContain("q=透明");
    }

    @Test
    void buildSearchUrlKeepsExistingPriceAndConditionFilters() {
        VisionCartProperties properties = new VisionCartProperties();
        EbayQueryTranslator translator = new EbayQueryTranslator(unavailableChatClient());
        EbaySearchService service = new EbaySearchService(
                properties, new ObjectMapper(), translator, RestClient.create());
        SearchFilter filter = new SearchFilter(
                new PriceRange(10.0, 50.0), List.of(), null,
                List.of(), List.of(), null, "price", "asc",
                null, Map.of(), List.of());

        String decoded = URLDecoder.decode(service.buildSearchUrl(
                properties.getEbay(), "mouse", filter, 2, 20), StandardCharsets.UTF_8);

        assertThat(decoded).contains("q=mouse");
        assertThat(decoded).contains("price:[10..50]");
        assertThat(decoded).contains("conditions:{NEW}");
        assertThat(decoded).contains("sort=price");
        assertThat(decoded).contains("offset=20");
    }

    private SearchFilter emptyFilter() {
        return new SearchFilter(
                new PriceRange(null, null), List.of(), null,
                List.of(), List.of(), null, null, "desc",
                null, Map.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatClient.Builder> unavailableChatClient() {
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
