package com.visioncart.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.*;
import com.visioncart.config.SecurityUtils;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.PriceHistory;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceHistoryRepository;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.price.PriceMonitorService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/api/v1")
public class UserDataController {
    private static final Logger log = LoggerFactory.getLogger(UserDataController.class);

    private final FavoriteProductRepository favoriteRepository;
    private final RecognitionHistoryRepository historyRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final PriceMonitorService priceMonitorService;
    private final ExecutorService searchExecutor;
    private final ObjectMapper objectMapper;

    public UserDataController(FavoriteProductRepository favoriteRepository,
                              RecognitionHistoryRepository historyRepository,
                              PriceHistoryRepository priceHistoryRepository,
                              PriceMonitorService priceMonitorService,
                              @Qualifier("searchExecutor") ExecutorService searchExecutor,
                              ObjectMapper objectMapper) {
        this.favoriteRepository = favoriteRepository;
        this.historyRepository = historyRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.priceMonitorService = priceMonitorService;
        this.searchExecutor = searchExecutor;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @PostMapping("/favorites")
    public ApiResponse<FavoriteCard> addFavorite(@Valid @RequestBody FavoriteRequest request) {
        Long userId = SecurityUtils.currentUserId();

        // 冲突解决：如果客户端时间比服务端旧，返回服务端最新数据
        FavoriteProduct existingByProduct = favoriteRepository.findByProductIdAndUserId(request.productId(), userId).orElse(null);
        if (existingByProduct != null && request.updatedAt() != null) {
            Instant clientTime = Instant.ofEpochMilli(request.updatedAt());
            if (existingByProduct.getUpdatedAt() != null && clientTime.isBefore(existingByProduct.getUpdatedAt())) {
                return ApiResponse.ok(toCard(existingByProduct));
            }
        }

        FavoriteProduct favorite = favoriteRepository.findByProductIdAndUserId(request.productId(), userId)
                .orElseGet(FavoriteProduct::new);
        favorite.setProductId(request.productId());
        favorite.setUserId(userId);
        favorite.setPlatform(request.platform());
        favorite.setTitle(request.title());
        favorite.setImageUrl(request.imageUrl());
        favorite.setPrice(request.price());
        favorite.setDetailUrl(request.detailUrl());
        Instant now = Instant.now();
        favorite.setUpdatedAt(now);
        if (favorite.getId() == null) {
            favorite.setCreatedAt(now);
        }
        FavoriteProduct saved = favoriteRepository.save(favorite);
        return ApiResponse.ok(toCard(saved));
    }

    @GetMapping("/favorites")
    public ApiResponse<List<FavoriteCard>> favorites() {
        Long userId = SecurityUtils.currentUserId();
        List<FavoriteProduct> items = favoriteRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);

        // Return current data immediately, trigger async price refresh in background
        List<FavoriteCard> cards = items.stream().map(this::toCardWithPriceInfo).toList();

        // Async refresh prices for each favorite
        for (FavoriteProduct item : items) {
            CompletableFuture.runAsync(() -> {
                try {
                    priceMonitorService.refreshPrice(item, userId);
                } catch (Exception e) {
                    log.warn("Async price refresh failed for {}: {}", item.getProductId(), e.getMessage());
                }
            }, searchExecutor);
        }

        return ApiResponse.ok(cards);
    }

    @Transactional
    @DeleteMapping("/favorites/{productId}")
    public ApiResponse<Void> removeFavorite(@PathVariable String productId) {
        Long userId = SecurityUtils.currentUserId();
        favoriteRepository.deleteByProductIdAndUserId(productId, userId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/history")
    public ApiResponse<PagedResult<HistoryItem>> history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.currentUserId();
        int clampedSize = Math.min(Math.max(size, 1), 50);
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), clampedSize);
        Page<RecognitionHistory> result = historyRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<HistoryItem> items = result.getContent().stream().map(this::toHistoryItem).toList();
        return ApiResponse.ok(new PagedResult<>(result.getTotalElements(), page, clampedSize, items));
    }

    private FavoriteCard toCard(FavoriteProduct f) {
        Long updatedMillis = f.getUpdatedAt() != null ? f.getUpdatedAt().toEpochMilli() : null;
        return new FavoriteCard(f.getProductId(), f.getPlatform(), f.getTitle(),
                f.getImageUrl(), f.getPrice(), f.getDetailUrl(), updatedMillis);
    }

    private FavoriteCard toCardWithPriceInfo(FavoriteProduct f) {
        Long updatedMillis = f.getUpdatedAt() != null ? f.getUpdatedAt().toEpochMilli() : null;
        BigDecimal currentPrice = f.getPrice(); // current stored price

        // Check if this is the 30-day lowest
        boolean isLowest = false;
        try {
            BigDecimal lowest = priceMonitorService.getLowestPrice30d(f.getProductId(), f.getPlatform());
            if (lowest != null && currentPrice != null && currentPrice.compareTo(lowest) <= 0) {
                isLowest = true;
            }
        } catch (Exception e) {
            // ignore — price history may not exist yet
        }

        // Price change from when it was first favorited (stored in price field) vs current
        // At this point price = current price (already updated or still original)
        // We don't have the "original favorite price" separately, so priceChange is null for now
        // It will be meaningful after first refresh when price changes
        return new FavoriteCard(f.getProductId(), f.getPlatform(), f.getTitle(),
                f.getImageUrl(), f.getPrice(), f.getDetailUrl(), updatedMillis,
                currentPrice, null, isLowest);
    }

    private HistoryItem toHistoryItem(RecognitionHistory h) {
        CategoryDto category = parseJson(h.getCategoryJson(), CategoryDto.class);
        Map<String, AttributeValue> attributes = parseAttributes(h.getAttributesJson());
        List<String> keywords = h.getKeywords() != null ? List.of(h.getKeywords().split(",")) : List.of();
        return new HistoryItem(h.getSessionId(), h.getImageUrl(), category, attributes,
                keywords, h.getConfidence(), h.getCreatedAt());
    }

    private <T> T parseJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Map<String, AttributeValue> parseAttributes(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, AttributeValue.class));
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

}
