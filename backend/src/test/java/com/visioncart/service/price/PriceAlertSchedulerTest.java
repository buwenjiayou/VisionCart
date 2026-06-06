package com.visioncart.service.price;

import com.visioncart.config.VisionCartProperties;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.PriceAlert;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.ArrayList;
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
        Page<PriceAlert> page = new PageImpl<>(List.of(alert));
        when(alertRepository.findByActiveTrue(any(Pageable.class))).thenReturn(page);

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
        Page<PriceAlert> emptyPage = new PageImpl<>(List.of());
        when(alertRepository.findByActiveTrue(any(Pageable.class))).thenReturn(emptyPage);

        scheduler.checkActiveAlerts();

        verify(priceMonitorService, never()).refreshPrice(any(), any());
    }

    @Test
    void shouldSkipWhenFavoriteNotFound() {
        PriceAlert alert = createAlert("tb_123", 1L);
        Page<PriceAlert> page = new PageImpl<>(List.of(alert));
        when(alertRepository.findByActiveTrue(any(Pageable.class))).thenReturn(page);
        when(favoriteRepository.findByProductIdAndUserId("tb_123", 1L))
                .thenReturn(Optional.empty());

        scheduler.checkActiveAlerts();

        verify(priceMonitorService, never()).refreshPrice(any(), any());
    }

    @Test
    void shouldLimitProductsPerRun() {
        // Create 60 active alerts, should only process 50
        List<PriceAlert> alerts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            alerts.add(createAlert("tb_" + i, 1L));
        }
        // First page returns 50, second page returns remaining 10 but scheduler stops at limit
        Page<PriceAlert> page1 = new PageImpl<>(alerts.subList(0, 50), PageRequest.of(0, 50), 60);
        Page<PriceAlert> page2 = new PageImpl<>(alerts.subList(50, 60), PageRequest.of(1, 50), 60);
        when(alertRepository.findByActiveTrue(eq(PageRequest.of(0, 50)))).thenReturn(page1);
        when(alertRepository.findByActiveTrue(eq(PageRequest.of(1, 10)))).thenReturn(page2);

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
        Page<PriceAlert> page = new PageImpl<>(List.of(alert1, alert2));
        when(alertRepository.findByActiveTrue(any(Pageable.class))).thenReturn(page);

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
