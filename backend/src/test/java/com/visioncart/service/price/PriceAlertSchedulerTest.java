package com.visioncart.service.price;

import com.visioncart.config.VisionCartProperties;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.PriceAlert;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PriceAlertSchedulerTest {

    private PriceAlertRepository alertRepository;
    private FavoriteProductRepository favoriteRepository;
    private PriceMonitorService priceMonitorService;
    private PriceAlertScheduler scheduler;

    @BeforeEach
    void setUp() {
        alertRepository = mock(PriceAlertRepository.class);
        favoriteRepository = mock(FavoriteProductRepository.class);
        priceMonitorService = mock(PriceMonitorService.class);
        VisionCartProperties properties = new VisionCartProperties();
        scheduler = new PriceAlertScheduler(alertRepository, favoriteRepository, priceMonitorService, properties);
    }

    @Test
    void shouldRefreshPricesForActiveAlerts() {
        PriceAlert alert = createAlert("tb_123", 1L);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(alert));

        FavoriteProduct favorite = createFavorite("tb_123", 1L);
        when(favoriteRepository.findByProductIdAndUserId("tb_123", 1L))
                .thenReturn(Optional.of(favorite));
        when(priceMonitorService.refreshPrice(eq(favorite), eq(1L)))
                .thenReturn(new BigDecimal("459"));

        scheduler.checkActiveAlerts();

        verify(priceMonitorService).refreshPrice(eq(favorite), eq(1L));
    }

    @Test
    void shouldSkipWhenNoActiveAlerts() {
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of());

        scheduler.checkActiveAlerts();

        verify(priceMonitorService, never()).refreshPrice(any(), any());
    }

    @Test
    void shouldSkipWhenFavoriteNotFound() {
        PriceAlert alert = createAlert("tb_123", 1L);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(alert));
        when(favoriteRepository.findByProductIdAndUserId("tb_123", 1L))
                .thenReturn(Optional.empty());

        scheduler.checkActiveAlerts();

        verify(priceMonitorService, never()).refreshPrice(any(), any());
    }

    @Test
    void shouldLimitProductsPerRun() {
        // Create 60 active alerts, should only process 50
        List<PriceAlert> alerts = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            alerts.add(createAlert("tb_" + i, 1L));
        }
        when(alertRepository.findAllByActiveTrue()).thenReturn(alerts);

        FavoriteProduct favorite = createFavorite("tb_0", 1L);
        when(favoriteRepository.findByProductIdAndUserId(anyString(), eq(1L)))
                .thenReturn(Optional.of(favorite));
        when(priceMonitorService.refreshPrice(any(), eq(1L)))
                .thenReturn(new BigDecimal("100"));

        scheduler.checkActiveAlerts();

        verify(priceMonitorService, times(50)).refreshPrice(any(), eq(1L));
    }

    @Test
    void shouldContinueOnIndividualFailure() {
        PriceAlert alert1 = createAlert("tb_1", 1L);
        PriceAlert alert2 = createAlert("tb_2", 1L);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(alert1, alert2));

        FavoriteProduct fav1 = createFavorite("tb_1", 1L);
        FavoriteProduct fav2 = createFavorite("tb_2", 1L);
        when(favoriteRepository.findByProductIdAndUserId("tb_1", 1L))
                .thenReturn(Optional.of(fav1));
        when(favoriteRepository.findByProductIdAndUserId("tb_2", 1L))
                .thenReturn(Optional.of(fav2));
        when(priceMonitorService.refreshPrice(eq(fav1), eq(1L)))
                .thenThrow(new RuntimeException("API error"));
        when(priceMonitorService.refreshPrice(eq(fav2), eq(1L)))
                .thenReturn(new BigDecimal("100"));

        scheduler.checkActiveAlerts();

        verify(priceMonitorService).refreshPrice(eq(fav2), eq(1L));
    }

    private PriceAlert createAlert(String productId, Long userId) {
        PriceAlert alert = new PriceAlert();
        alert.setProductId(productId);
        alert.setUserId(userId);
        alert.setPlatform("淘宝");
        alert.setTargetPrice(new BigDecimal("500"));
        alert.setActive(true);
        return alert;
    }

    private FavoriteProduct createFavorite(String productId, Long userId) {
        FavoriteProduct f = new FavoriteProduct();
        f.setProductId(productId);
        f.setUserId(userId);
        f.setPlatform("淘宝");
        f.setTitle("Test Product");
        f.setPrice(new BigDecimal("599"));
        return f;
    }
}
