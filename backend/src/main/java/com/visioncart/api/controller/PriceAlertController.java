package com.visioncart.api.controller;

import com.visioncart.api.dto.*;
import com.visioncart.config.SecurityUtils;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.PriceAlert;
import com.visioncart.domain.PriceHistory;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceAlertRepository;
import com.visioncart.service.price.PriceMonitorService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/v1")
public class PriceAlertController {
    private final PriceAlertRepository alertRepository;
    private final FavoriteProductRepository favoriteRepository;
    private final PriceMonitorService priceMonitorService;
    private final Object[] alertLocks = IntStream.range(0, 256).mapToObj(i -> new Object()).toArray();

    public PriceAlertController(PriceAlertRepository alertRepository,
                                FavoriteProductRepository favoriteRepository,
                                PriceMonitorService priceMonitorService) {
        this.alertRepository = alertRepository;
        this.favoriteRepository = favoriteRepository;
        this.priceMonitorService = priceMonitorService;
    }

    @Transactional
    @PostMapping("/price-alerts")
    public ApiResponse<PriceAlertCard> createAlert(@Valid @RequestBody PriceAlertRequest request) {
        Long userId = SecurityUtils.currentUserId();
        synchronized (lockFor(userId, request.productId())) {
            PriceAlert alert = alertRepository.findByUserIdAndProductIdAndActiveTrue(userId, request.productId())
                    .orElseGet(PriceAlert::new);

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
            if (alert.getCreatedAt() == null) {
                alert.setCreatedAt(java.time.Instant.now());
            }

            PriceAlert saved = alertRepository.save(alert);
            return ApiResponse.ok(toCard(saved, favorite));
        }
    }

    @GetMapping("/price-alerts")
    public ApiResponse<List<PriceAlertCard>> listAlerts() {
        Long userId = SecurityUtils.currentUserId();
        List<PriceAlert> alerts = alertRepository.findAllByUserId(userId);
        List<String> productIds = alerts.stream().map(PriceAlert::getProductId).distinct().toList();
        Map<String, FavoriteProduct> favorites = productIds.isEmpty()
                ? Map.of()
                : favoriteRepository.findByUserIdAndProductIdIn(userId, productIds).stream()
                        .collect(Collectors.toMap(FavoriteProduct::getProductId, Function.identity(), (left, right) -> left));
        List<PriceAlertCard> cards = alerts.stream()
                .map(alert -> toCard(alert, favorites.get(alert.getProductId())))
                .toList();
        return ApiResponse.ok(cards);
    }

    @DeleteMapping("/price-alerts/{productId}")
    public ApiResponse<Void> deleteAlert(@PathVariable String productId) {
        Long userId = SecurityUtils.currentUserId();
        alertRepository.deleteByUserIdAndProductId(userId, productId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/price-history/{productId}")
    public ApiResponse<PriceHistoryDto> priceHistory(@PathVariable String productId) {
        Long userId = SecurityUtils.currentUserId();
        FavoriteProduct favorite = favoriteRepository.findByProductIdAndUserId(productId, userId).orElse(null);
        String platform = favorite != null ? favorite.getPlatform() : "未知";

        List<PriceHistory> history = priceMonitorService.getPriceHistory(productId, platform);
        BigDecimal lowest = priceMonitorService.getLowestPrice30d(productId, platform);

        List<PriceHistoryDto.Entry> entries = history.stream()
                .map(h -> new PriceHistoryDto.Entry(h.getPrice(), h.getRecordedAt()))
                .toList();

        return ApiResponse.ok(new PriceHistoryDto(productId, platform, lowest, entries));
    }

    private Object lockFor(Long userId, String productId) {
        return alertLocks[Math.floorMod((userId + ":" + productId).hashCode(), alertLocks.length)];
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

}
