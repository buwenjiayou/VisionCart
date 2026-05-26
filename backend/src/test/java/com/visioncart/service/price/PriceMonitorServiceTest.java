package com.visioncart.service.price;

import com.visioncart.api.dto.*;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.PriceAlert;
import com.visioncart.domain.PriceHistory;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceAlertRepository;
import com.visioncart.repository.PriceHistoryRepository;
import com.visioncart.service.search.SearchOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PriceMonitorServiceTest {

    private SearchOrchestrator searchOrchestrator;
    private PriceHistoryRepository historyRepository;
    private PriceAlertRepository alertRepository;
    private FavoriteProductRepository favoriteRepository;
    private SimpMessagingTemplate messagingTemplate;
    private VisionCartProperties properties;
    private PriceMonitorService service;

    @BeforeEach
    void setUp() {
        searchOrchestrator = mock(SearchOrchestrator.class);
        historyRepository = mock(PriceHistoryRepository.class);
        alertRepository = mock(PriceAlertRepository.class);
        favoriteRepository = mock(FavoriteProductRepository.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        properties = new VisionCartProperties();

        service = new PriceMonitorService(searchOrchestrator, historyRepository, alertRepository,
                favoriteRepository, messagingTemplate, properties);
    }

    @Test
    void shouldUpdatePriceWhenProductFound() {
        FavoriteProduct favorite = createFavorite("tb_123", "淘宝", "Nike Air Max", new BigDecimal("599"));

        ProductCard card = new ProductCard("tb_123", "Nike Air Max", "img.jpg",
                new BigDecimal("459"), null, "淘宝", false, "shop", 4.5, 100,
                0.9, List.of(), "https://item.taobao.com/item.htm?id=123");
        SearchResult searchResult = new SearchResult(1, List.of(card), List.of(), List.of());
        when(searchOrchestrator.search(any(SearchRequest.class))).thenReturn(searchResult);
        when(historyRepository.save(any(PriceHistory.class))).thenReturn(null);
        when(favoriteRepository.save(any(FavoriteProduct.class))).thenReturn(favorite);
        when(alertRepository.findByUserIdAndProductIdAndActiveTrue(eq(1L), eq("tb_123")))
                .thenReturn(Optional.empty());
        when(historyRepository.findMinPriceSince(anyString(), anyString(), any(Instant.class)))
                .thenReturn(new BigDecimal("459"));

        BigDecimal result = service.refreshPrice(favorite, 1L);

        assertThat(result).isEqualByComparingTo(new BigDecimal("459"));
        verify(favoriteRepository).save(any(FavoriteProduct.class));
        verify(historyRepository).save(any(PriceHistory.class));
    }

    @Test
    void shouldReturnOriginalPriceWhenProductNotFound() {
        FavoriteProduct favorite = createFavorite("tb_123", "淘宝", "Nike Air Max", new BigDecimal("599"));

        SearchResult searchResult = new SearchResult(0, List.of(), List.of(), List.of());
        when(searchOrchestrator.search(any(SearchRequest.class))).thenReturn(searchResult);

        BigDecimal result = service.refreshPrice(favorite, 1L);

        assertThat(result).isEqualByComparingTo(new BigDecimal("599"));
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void shouldReturnOriginalPriceOnSearchFailure() {
        FavoriteProduct favorite = createFavorite("tb_123", "淘宝", "Nike Air Max", new BigDecimal("599"));
        when(searchOrchestrator.search(any(SearchRequest.class))).thenThrow(new RuntimeException("API error"));

        BigDecimal result = service.refreshPrice(favorite, 1L);

        assertThat(result).isEqualByComparingTo(new BigDecimal("599"));
    }

    @Test
    void shouldSendTargetReachedNotification() {
        FavoriteProduct favorite = createFavorite("tb_123", "淘宝", "Nike Air Max", new BigDecimal("599"));

        PriceAlert alert = new PriceAlert();
        alert.setTargetPrice(new BigDecimal("500"));
        alert.setNotifiedAt(null);
        alert.setProductId("tb_123");
        alert.setPlatform("淘宝");

        ProductCard card = new ProductCard("tb_123", "Nike Air Max", "img.jpg",
                new BigDecimal("459"), null, "淘宝", false, "shop", 4.5, 100,
                0.9, List.of(), "https://item.taobao.com/item.htm?id=123");
        SearchResult searchResult = new SearchResult(1, List.of(card), List.of(), List.of());
        when(searchOrchestrator.search(any(SearchRequest.class))).thenReturn(searchResult);
        when(historyRepository.save(any(PriceHistory.class))).thenReturn(null);
        when(favoriteRepository.save(any(FavoriteProduct.class))).thenReturn(favorite);
        when(alertRepository.findByUserIdAndProductIdAndActiveTrue(eq(1L), eq("tb_123")))
                .thenReturn(Optional.of(alert));
        when(alertRepository.save(any(PriceAlert.class))).thenReturn(alert);
        when(historyRepository.findMinPriceSince(anyString(), anyString(), any(Instant.class)))
                .thenReturn(new BigDecimal("459"));

        service.refreshPrice(favorite, 1L);

        verify(messagingTemplate).convertAndSend(eq("/topic/price-alert/1"), any(Map.class));
    }

    @Test
    void shouldSkipNotificationWhenRecentlyNotified() {
        FavoriteProduct favorite = createFavorite("tb_123", "淘宝", "Nike Air Max", new BigDecimal("599"));

        PriceAlert alert = new PriceAlert();
        alert.setTargetPrice(new BigDecimal("500"));
        alert.setNotifiedAt(Instant.now().minus(1, ChronoUnit.DAYS)); // notified 1 day ago
        alert.setProductId("tb_123");
        alert.setPlatform("淘宝");

        ProductCard card = new ProductCard("tb_123", "Nike Air Max", "img.jpg",
                new BigDecimal("459"), null, "淘宝", false, "shop", 4.5, 100,
                0.9, List.of(), "https://item.taobao.com/item.htm?id=123");
        SearchResult searchResult = new SearchResult(1, List.of(card), List.of(), List.of());
        when(searchOrchestrator.search(any(SearchRequest.class))).thenReturn(searchResult);
        when(historyRepository.save(any(PriceHistory.class))).thenReturn(null);
        when(favoriteRepository.save(any(FavoriteProduct.class))).thenReturn(favorite);
        when(alertRepository.findByUserIdAndProductIdAndActiveTrue(eq(1L), eq("tb_123")))
                .thenReturn(Optional.of(alert));
        when(historyRepository.findMinPriceSince(anyString(), anyString(), any(Instant.class)))
                .thenReturn(new BigDecimal("459"));

        service.refreshPrice(favorite, 1L);

        verify(messagingTemplate, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    void shouldRecordPriceHistory() {
        FavoriteProduct favorite = createFavorite("tb_123", "淘宝", "Nike Air Max", new BigDecimal("599"));

        ProductCard card = new ProductCard("tb_123", "Nike Air Max", "img.jpg",
                new BigDecimal("459"), null, "淘宝", false, "shop", 4.5, 100,
                0.9, List.of(), "https://item.taobao.com/item.htm?id=123");
        SearchResult searchResult = new SearchResult(1, List.of(card), List.of(), List.of());
        when(searchOrchestrator.search(any(SearchRequest.class))).thenReturn(searchResult);
        when(historyRepository.save(any(PriceHistory.class))).thenReturn(null);
        when(favoriteRepository.save(any(FavoriteProduct.class))).thenReturn(favorite);
        when(alertRepository.findByUserIdAndProductIdAndActiveTrue(eq(1L), eq("tb_123")))
                .thenReturn(Optional.empty());
        when(historyRepository.findMinPriceSince(anyString(), anyString(), any(Instant.class)))
                .thenReturn(new BigDecimal("459"));

        service.refreshPrice(favorite, 1L);

        verify(historyRepository).save(argThat(h ->
                h.getProductId().equals("tb_123") &&
                h.getPlatform().equals("淘宝") &&
                h.getPrice().compareTo(new BigDecimal("459")) == 0
        ));
    }

    @Test
    void shouldDelegateGetPriceHistory() {
        PriceHistory h = new PriceHistory();
        h.setProductId("tb_123");
        h.setPlatform("淘宝");
        h.setPrice(new BigDecimal("459"));
        h.setRecordedAt(Instant.now());
        when(historyRepository.findByProductIdAndPlatformAndRecordedAtAfterOrderByRecordedAtAsc(
                eq("tb_123"), eq("淘宝"), any(Instant.class)))
                .thenReturn(List.of(h));

        List<PriceHistory> result = service.getPriceHistory("tb_123", "淘宝");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("459"));
    }

    private FavoriteProduct createFavorite(String productId, String platform, String title, BigDecimal price) {
        FavoriteProduct f = new FavoriteProduct();
        f.setProductId(productId);
        f.setPlatform(platform);
        f.setTitle(title);
        f.setPrice(price);
        f.setUserId(1L);
        return f;
    }
}
