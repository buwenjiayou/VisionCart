package com.visioncart.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.CategoryDto;
import com.visioncart.api.dto.FavoriteCard;
import com.visioncart.api.dto.FavoriteRequest;
import com.visioncart.api.dto.HistoryItem;
import com.visioncart.api.dto.HistoryProduct;
import com.visioncart.api.dto.PagedResult;
import com.visioncart.config.SecurityUtils;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.domain.RecognitionHistoryProduct;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceAlertRepository;
import com.visioncart.repository.PriceHistoryRepository;
import com.visioncart.repository.RecognitionHistoryProductRepository;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.price.PriceMonitorService;
import com.visioncart.service.recognition.AsyncRecognitionTaskManager;
import com.visioncart.service.recognition.RecognitionImageStorage;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
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
    private final PriceAlertRepository priceAlertRepository;
    private final RecognitionHistoryProductRepository historyProductRepository;
    private final PriceMonitorService priceMonitorService;
    private final ExecutorService searchExecutor;
    private final ObjectMapper objectMapper;
    private final RecognitionImageStorage imageStorage;
    private final AsyncRecognitionTaskManager taskManager;

    public UserDataController(FavoriteProductRepository favoriteRepository,
                              RecognitionHistoryRepository historyRepository,
                              PriceHistoryRepository priceHistoryRepository,
                              PriceAlertRepository priceAlertRepository,
                              RecognitionHistoryProductRepository historyProductRepository,
                              PriceMonitorService priceMonitorService,
                              @Qualifier("searchExecutor") ExecutorService searchExecutor,
                              ObjectMapper objectMapper,
                              RecognitionImageStorage imageStorage,
                              AsyncRecognitionTaskManager taskManager) {
        this.favoriteRepository = favoriteRepository;
        this.historyRepository = historyRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.priceAlertRepository = priceAlertRepository;
        this.historyProductRepository = historyProductRepository;
        this.priceMonitorService = priceMonitorService;
        this.searchExecutor = searchExecutor;
        this.objectMapper = objectMapper;
        this.imageStorage = imageStorage;
        this.taskManager = taskManager;
    }

    @Transactional
    @PostMapping("/favorites")
    public ApiResponse<FavoriteCard> addFavorite(@Valid @RequestBody FavoriteRequest request) {
        Long userId = SecurityUtils.currentUserId();
        FavoriteProduct existingByProduct = favoriteRepository.findByProductIdAndUserId(request.productId(), userId)
                .orElse(null);
        if (existingByProduct != null && request.updatedAt() != null) {
            Instant clientTime = Instant.ofEpochMilli(request.updatedAt());
            if (existingByProduct.getUpdatedAt() != null && clientTime.isBefore(existingByProduct.getUpdatedAt())) {
                return ApiResponse.ok(toCard(existingByProduct));
            }
        }

        FavoriteProduct favorite = existingByProduct != null ? existingByProduct : new FavoriteProduct();
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

        // Batch-fetch lowest prices to avoid N+1 queries
        Instant since = Instant.now().minus(Duration.ofDays(90));
        List<String> productIds = items.stream().map(FavoriteProduct::getProductId).toList();
        Map<String, BigDecimal> lowestPrices = new java.util.HashMap<>();
        if (!productIds.isEmpty()) {
            try {
                List<Object[]> rows = priceHistoryRepository.findMinPricesByProductIds(productIds, since);
                for (Object[] row : rows) {
                    lowestPrices.put((String) row[0], (BigDecimal) row[1]);
                }
            } catch (Exception e) {
                log.warn("Batch price history lookup failed: {}", e.getMessage());
            }
        }

        List<FavoriteCard> cards = items.stream()
                .map(f -> toCardWithPriceInfo(f, lowestPrices.get(f.getProductId())))
                .toList();

        Instant staleThreshold = Instant.now().minus(Duration.ofMinutes(30));
        int refreshed = 0;
        int maxAsyncRefresh = 5;
        for (FavoriteProduct item : items) {
            if (refreshed >= maxAsyncRefresh) break;
            if (item.getUpdatedAt() != null && item.getUpdatedAt().isAfter(staleThreshold)) continue;
            refreshed++;
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
        // Clean up associated price alerts to prevent orphaned records
        priceAlertRepository.deleteByUserIdAndProductId(userId, productId);
        return ApiResponse.ok(null);
    }

    @Transactional
    @DeleteMapping("/history/{sessionId}")
    public ApiResponse<Void> deleteHistory(@PathVariable String sessionId) {
        Long userId = SecurityUtils.currentUserId();
        if (historyRepository.findBySessionIdAndUserId(sessionId, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recognition history not found");
        }
        historyRepository.deleteBySessionIdAndUserId(sessionId, userId);
        imageStorage.deleteHistoryImage(sessionId);
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

    @GetMapping("/history/{sessionId}/products")
    public ApiResponse<List<HistoryProduct>> historyProducts(@PathVariable String sessionId) {
        Long userId = SecurityUtils.currentUserId();
        boolean ownsCompletedHistory = historyRepository.findBySessionIdAndUserId(sessionId, userId).isPresent();
        boolean ownsActiveTask = !ownsCompletedHistory && taskManager.belongsToUser(sessionId, userId);
        if (!ownsCompletedHistory && !ownsActiveTask) {
            return ApiResponse.fail(403, "无权访问该识别任务");
        }
        List<HistoryProduct> products = historyProductRepository
                .findByHistoryIdOrderBySortNoAsc(sessionId, org.springframework.data.domain.PageRequest.of(0, 100))
                .stream()
                .map(this::toHistoryProduct)
                .toList();
        return ApiResponse.ok(products);
    }

    @GetMapping("/history/{sessionId}/image")
    public ResponseEntity<Resource> historyImage(@PathVariable String sessionId) {
        Long userId = SecurityUtils.currentUserId();
        boolean ownsCompletedHistory = historyRepository.findBySessionIdAndUserId(sessionId, userId).isPresent();
        boolean ownsActiveTask = !ownsCompletedHistory && taskManager.belongsToUser(sessionId, userId);
        if (!ownsCompletedHistory && !ownsActiveTask) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recognition history not found");
        }

        Resource resource = imageStorage.loadHistoryImage(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "recognition image not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(resource);
    }

    private FavoriteCard toCard(FavoriteProduct f) {
        Long updatedMillis = f.getUpdatedAt() != null ? f.getUpdatedAt().toEpochMilli() : null;
        return new FavoriteCard(f.getProductId(), f.getPlatform(), f.getTitle(),
                f.getImageUrl(), f.getPrice(), f.getDetailUrl(), updatedMillis);
    }

    private FavoriteCard toCardWithPriceInfo(FavoriteProduct f, BigDecimal lowestPrice) {
        Long updatedMillis = f.getUpdatedAt() != null ? f.getUpdatedAt().toEpochMilli() : null;
        BigDecimal currentPrice = f.getPrice();

        boolean isLowest = lowestPrice != null && currentPrice != null && currentPrice.compareTo(lowestPrice) <= 0;

        return new FavoriteCard(f.getProductId(), f.getPlatform(), f.getTitle(),
                f.getImageUrl(), f.getPrice(), f.getDetailUrl(), updatedMillis,
                currentPrice, null, isLowest);
    }

    private HistoryItem toHistoryItem(RecognitionHistory h) {
        CategoryDto category = parseJson(h.getCategoryJson(), CategoryDto.class);
        Map<String, AttributeValue> attributes = parseAttributes(h.getAttributesJson());
        List<String> keywords = h.getKeywords() != null ? List.of(h.getKeywords().split(",")) : List.of();
        // 从快照明细表加载商品列表
        List<HistoryProduct> products = historyProductRepository
                .findByHistoryIdOrderBySortNoAsc(h.getSessionId(), org.springframework.data.domain.PageRequest.of(0, 50))
                .stream()
                .map(this::toHistoryProduct)
                .toList();
        return new HistoryItem(h.getSessionId(), displayableHistoryImageUrl(h), category, attributes,
                keywords, h.getConfidence(), h.getCreatedAt(), products);
    }

    private HistoryProduct toHistoryProduct(RecognitionHistoryProduct p) {
        return new HistoryProduct(
                p.getProductId(), p.getSortNo(), p.getSimilarityScore(),
                p.getTitle(), p.getCoverImage(), p.getPrice(), p.getOriginalPrice(),
                p.getPlatform(), p.getBrand(), p.getRating(), p.getSales(), p.getDetailUrl(),
                p.getMainCategoryCode(), p.getProductRole()
        );
    }

    private String displayableHistoryImageUrl(RecognitionHistory h) {
        String imageUrl = h.getImageUrl();
        if (imageUrl == null || imageUrl.isBlank() || imageUrl.startsWith("upload://")) {
            return imageStorage.historyImageUrl(h.getSessionId());
        }
        return imageUrl;
    }

    private <T> T parseJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Map<String, AttributeValue> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, AttributeValue.class));
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
