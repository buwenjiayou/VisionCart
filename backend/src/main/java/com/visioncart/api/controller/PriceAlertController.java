package com.visioncart.api.controller;

import com.visioncart.api.dto.*;
import com.visioncart.config.JwtAuthenticationFilter.AuthPrincipal;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.PriceAlert;
import com.visioncart.domain.PriceHistory;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceAlertRepository;
import com.visioncart.service.price.PriceMonitorService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PriceAlertController {
    private final PriceAlertRepository alertRepository;
    private final FavoriteProductRepository favoriteRepository;
    private final PriceMonitorService priceMonitorService;

    public PriceAlertController(PriceAlertRepository alertRepository,
                                FavoriteProductRepository favoriteRepository,
                                PriceMonitorService priceMonitorService) {
        this.alertRepository = alertRepository;
        this.favoriteRepository = favoriteRepository;
        this.priceMonitorService = priceMonitorService;
    }

    @PostMapping("/price-alerts")
    public ApiResponse<PriceAlertCard> createAlert(@Valid @RequestBody PriceAlertRequest request) {
        Long userId = getCurrentUserId();

        // Check if alert already exists for this product
        PriceAlert alert = alertRepository.findByUserIdAndProductIdAndActiveTrue(userId, request.productId())
                .orElseGet(PriceAlert::new);

        // Get favorite info for context
        FavoriteProduct favorite = favoriteRepository.findByProductIdAndUserId(request.productId(), userId)
                .orElse(null);

        alert.setUserId(userId);
        alert.setProductId(request.productId());
        alert.setPlatform(favorite != null ? favorite.getPlatform() : "未知");
        alert.setTargetPrice(request.targetPrice());
        alert.setCurrentPrice(favorite != null ? favorite.getPrice() : null);
        alert.setActive(true);
        alert.setTriggeredAt(null);
        alert.setNotifiedAt(null);

        PriceAlert saved = alertRepository.save(alert);
        return ApiResponse.ok(toCard(saved, favorite));
    }

    @GetMapping("/price-alerts")
    public ApiResponse<List<PriceAlertCard>> listAlerts() {
        Long userId = getCurrentUserId();
        List<PriceAlert> alerts = alertRepository.findAllByUserId(userId);
        List<PriceAlertCard> cards = alerts.stream().map(alert -> {
            FavoriteProduct favorite = favoriteRepository.findByProductIdAndUserId(alert.getProductId(), userId)
                    .orElse(null);
            return toCard(alert, favorite);
        }).toList();
        return ApiResponse.ok(cards);
    }

    @DeleteMapping("/price-alerts/{productId}")
    public ApiResponse<Void> deleteAlert(@PathVariable String productId) {
        Long userId = getCurrentUserId();
        alertRepository.deleteByUserIdAndProductId(userId, productId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/price-history/{productId}")
    public ApiResponse<PriceHistoryDto> priceHistory(@PathVariable String productId) {
        Long userId = getCurrentUserId();
        FavoriteProduct favorite = favoriteRepository.findByProductIdAndUserId(productId, userId).orElse(null);
        String platform = favorite != null ? favorite.getPlatform() : "未知";

        List<PriceHistory> history = priceMonitorService.getPriceHistory(productId, platform);
        BigDecimal lowest = priceMonitorService.getLowestPrice30d(productId, platform);

        List<PriceHistoryDto.Entry> entries = history.stream()
                .map(h -> new PriceHistoryDto.Entry(h.getPrice(), h.getRecordedAt()))
                .toList();

        return ApiResponse.ok(new PriceHistoryDto(productId, platform, lowest, entries));
    }

    private PriceAlertCard toCard(PriceAlert alert, FavoriteProduct favorite) {
        return new PriceAlertCard(
                alert.getProductId(),
                alert.getPlatform(),
                favorite != null ? favorite.getTitle() : null,
                favorite != null ? favorite.getImageUrl() : null,
                alert.getTargetPrice(),
                alert.getCurrentPrice(),
                favorite != null ? favorite.getPrice() : null,
                alert.isActive(),
                alert.getTriggeredAt(),
                alert.getCreatedAt()
        );
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.getUserId();
        }
        throw new SecurityException("未登录");
    }
}
