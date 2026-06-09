package com.visioncart.api.controller;

import com.visioncart.api.dto.PriceAlertRequest;
import com.visioncart.config.JwtAuthenticationFilter;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.PriceAlert;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceAlertRepository;
import com.visioncart.service.price.PriceMonitorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceAlertControllerTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updatesExistingActiveAlert() {
        PriceAlertRepository alertRepository = mock(PriceAlertRepository.class);
        FavoriteProductRepository favoriteRepository = mock(FavoriteProductRepository.class);
        PriceAlertController controller = new PriceAlertController(
                alertRepository, favoriteRepository, mock(PriceMonitorService.class));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtAuthenticationFilter.AuthPrincipal(7L, "u@example.com"), null));

        FavoriteProduct favorite = favorite();
        PriceAlert existing = new PriceAlert();
        existing.setUserId(7L);
        existing.setProductId("tb_1");
        existing.setPlatform("淘宝");
        existing.setTargetPrice(new BigDecimal("300"));
        existing.setCurrentPrice(new BigDecimal("399"));
        existing.setActive(true);
        when(favoriteRepository.findByProductIdAndUserId("tb_1", 7L)).thenReturn(Optional.of(favorite));
        when(alertRepository.findByUserIdAndProductIdAndActiveTrue(7L, "tb_1")).thenReturn(Optional.of(existing));
        when(alertRepository.saveAndFlush(existing)).thenReturn(existing);

        var response = controller.createAlert(new PriceAlertRequest("tb_1", new BigDecimal("280")));

        assertThat(response.code()).isEqualTo(200);
        assertThat(existing.getTargetPrice()).isEqualByComparingTo("280");
        assertThat(existing.getTriggeredAt()).isNull();
        assertThat(existing.getNotifiedAt()).isNull();
        verify(alertRepository, never()).upsertActiveAlert(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void marksExistingAlertTriggeredWhenCurrentPriceAlreadyReachedTarget() {
        PriceAlertRepository alertRepository = mock(PriceAlertRepository.class);
        FavoriteProductRepository favoriteRepository = mock(FavoriteProductRepository.class);
        PriceAlertController controller = new PriceAlertController(
                alertRepository, favoriteRepository, mock(PriceMonitorService.class));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtAuthenticationFilter.AuthPrincipal(7L, "u@example.com"), null));

        FavoriteProduct favorite = favorite();
        favorite.setPrice(new BigDecimal("199"));
        PriceAlert existing = new PriceAlert();
        existing.setUserId(7L);
        existing.setProductId("tb_1");
        existing.setPlatform("淘宝");
        existing.setTargetPrice(new BigDecimal("300"));
        existing.setCurrentPrice(new BigDecimal("399"));
        existing.setActive(true);
        when(favoriteRepository.findByProductIdAndUserId("tb_1", 7L)).thenReturn(Optional.of(favorite));
        when(alertRepository.findByUserIdAndProductIdAndActiveTrue(7L, "tb_1")).thenReturn(Optional.of(existing));
        when(alertRepository.saveAndFlush(existing)).thenReturn(existing);

        var response = controller.createAlert(new PriceAlertRequest("tb_1", new BigDecimal("200")));

        assertThat(response.code()).isEqualTo(200);
        assertThat(existing.isActive()).isFalse();
        assertThat(existing.getCurrentPrice()).isEqualByComparingTo("199");
        assertThat(existing.getTriggeredAt()).isNotNull();
        assertThat(response.data().active()).isFalse();
        assertThat(response.data().triggeredAt()).isNotNull();
    }

    @Test
    void insertsThroughAtomicUpsertWhenNoActiveAlertExists() {
        PriceAlertRepository alertRepository = mock(PriceAlertRepository.class);
        FavoriteProductRepository favoriteRepository = mock(FavoriteProductRepository.class);
        PriceAlertController controller = new PriceAlertController(
                alertRepository, favoriteRepository, mock(PriceMonitorService.class));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtAuthenticationFilter.AuthPrincipal(7L, "u@example.com"), null));

        FavoriteProduct favorite = favorite();
        PriceAlert saved = new PriceAlert();
        saved.setUserId(7L);
        saved.setProductId("tb_1");
        saved.setPlatform("淘宝");
        saved.setTargetPrice(new BigDecimal("280"));
        saved.setCurrentPrice(new BigDecimal("399"));
        saved.setActive(true);
        when(favoriteRepository.findByProductIdAndUserId("tb_1", 7L)).thenReturn(Optional.of(favorite));
        when(alertRepository.findByUserIdAndProductIdAndActiveTrue(7L, "tb_1"))
                .thenReturn(Optional.empty(), Optional.of(saved));

        var response = controller.createAlert(new PriceAlertRequest("tb_1", new BigDecimal("280")));

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.data().targetPrice()).isEqualByComparingTo("280");
        verify(alertRepository).upsertActiveAlert(7L, "tb_1", "淘宝",
                new BigDecimal("280"), new BigDecimal("399"));
    }

    @Test
    void insertsTriggeredAlertWhenCurrentPriceAlreadyReachedTarget() {
        PriceAlertRepository alertRepository = mock(PriceAlertRepository.class);
        FavoriteProductRepository favoriteRepository = mock(FavoriteProductRepository.class);
        PriceAlertController controller = new PriceAlertController(
                alertRepository, favoriteRepository, mock(PriceMonitorService.class));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtAuthenticationFilter.AuthPrincipal(7L, "u@example.com"), null));

        FavoriteProduct favorite = favorite();
        favorite.setPrice(new BigDecimal("199"));
        PriceAlert saved = new PriceAlert();
        when(favoriteRepository.findByProductIdAndUserId("tb_1", 7L)).thenReturn(Optional.of(favorite));
        when(alertRepository.findByUserIdAndProductIdAndActiveTrue(7L, "tb_1")).thenReturn(Optional.empty());
        when(alertRepository.saveAndFlush(any(PriceAlert.class))).thenAnswer(invocation -> {
            PriceAlert alert = invocation.getArgument(0);
            saved.setUserId(alert.getUserId());
            saved.setProductId(alert.getProductId());
            saved.setPlatform(alert.getPlatform());
            saved.setTargetPrice(alert.getTargetPrice());
            saved.setCurrentPrice(alert.getCurrentPrice());
            saved.setActive(alert.isActive());
            saved.setTriggeredAt(alert.getTriggeredAt());
            saved.setCreatedAt(alert.getCreatedAt());
            return saved;
        });

        var response = controller.createAlert(new PriceAlertRequest("tb_1", new BigDecimal("200")));

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.data().active()).isFalse();
        assertThat(response.data().currentPrice()).isEqualByComparingTo("199");
        assertThat(response.data().triggeredAt()).isNotNull();
        verify(alertRepository, never()).upsertActiveAlert(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private FavoriteProduct favorite() {
        FavoriteProduct favorite = new FavoriteProduct();
        favorite.setUserId(7L);
        favorite.setProductId("tb_1");
        favorite.setPlatform("淘宝");
        favorite.setTitle("商品");
        favorite.setImageUrl("https://img.example/tb_1.jpg");
        favorite.setPrice(new BigDecimal("399"));
        return favorite;
    }
}
