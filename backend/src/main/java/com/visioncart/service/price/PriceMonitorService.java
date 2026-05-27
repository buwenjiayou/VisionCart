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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class PriceMonitorService {
    private static final Logger log = LoggerFactory.getLogger(PriceMonitorService.class);
    private static final BigDecimal LARGE_DROP_THRESHOLD = new BigDecimal("0.15");

    private final SearchOrchestrator searchOrchestrator;
    private final PriceHistoryRepository historyRepository;
    private final PriceAlertRepository alertRepository;
    private final FavoriteProductRepository favoriteRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final int historyDays;
    private final int dedupDays;

    @Autowired
    public PriceMonitorService(SearchOrchestrator searchOrchestrator,
                               PriceHistoryRepository historyRepository,
                               PriceAlertRepository alertRepository,
                               FavoriteProductRepository favoriteRepository,
                               SimpMessagingTemplate messagingTemplate,
                               StringRedisTemplate redisTemplate,
                               VisionCartProperties properties) {
        this.searchOrchestrator = searchOrchestrator;
        this.historyRepository = historyRepository;
        this.alertRepository = alertRepository;
        this.favoriteRepository = favoriteRepository;
        this.messagingTemplate = messagingTemplate;
        this.redisTemplate = redisTemplate;
        this.historyDays = properties.getPriceMonitor().getHistoryRetentionDays();
        this.dedupDays = properties.getPriceMonitor().getNotificationDedupDays();
    }

    PriceMonitorService(SearchOrchestrator searchOrchestrator,
                        PriceHistoryRepository historyRepository,
                        PriceAlertRepository alertRepository,
                        FavoriteProductRepository favoriteRepository,
                        SimpMessagingTemplate messagingTemplate,
                        VisionCartProperties properties) {
        this(searchOrchestrator, historyRepository, alertRepository, favoriteRepository, messagingTemplate, null, properties);
    }

    /**
     * Refresh price for a single favorite product. Searches by title keywords on the product's platform.
     * If price changed: updates favorite, records history, checks alerts.
     */
    public BigDecimal refreshPrice(FavoriteProduct favorite, Long userId) {
        String productId = favorite.getProductId();
        String platform = favorite.getPlatform();
        String title = favorite.getTitle();

        if (title == null || title.isBlank()) {
            return favorite.getPrice();
        }

        try {
            SearchFilter filter = new SearchFilter(
                    null, List.of(platform), null, List.of(), List.of(),
                    null, null, null, null
            );
            SearchRequest request = new SearchRequest(
                    "price-monitor", Map.of(), filter, 1, 10, "server"
            );

            SearchResult result = searchOrchestrator.search(request);
            ProductCard matched = result.products().stream()
                    .filter(p -> p.id().equals(productId))
                    .findFirst()
                    .orElse(null);

            if (matched == null) {
                log.debug("Product {} not found in search results for platform {}", productId, platform);
                return favorite.getPrice();
            }

            BigDecimal currentPrice = matched.price();
            BigDecimal oldPrice = favorite.getPrice();

            // Record price history
            recordHistory(productId, platform, currentPrice);

            // Update favorite price if changed
            if (oldPrice == null || currentPrice.compareTo(oldPrice) != 0) {
                favorite.setPrice(currentPrice);
                favoriteRepository.save(favorite);
            }

            // Check alerts
            checkAlerts(userId, favorite, currentPrice);

            return currentPrice;
        } catch (Exception e) {
            log.warn("Price refresh failed for {}: {}", productId, e.getMessage());
            return favorite.getPrice();
        }
    }

    public void recordHistory(String productId, String platform, BigDecimal price) {
        PriceHistory history = new PriceHistory();
        history.setProductId(productId);
        history.setPlatform(platform);
        history.setPrice(price);
        history.setRecordedAt(Instant.now());
        historyRepository.save(history);
    }

    public List<PriceHistory> getPriceHistory(String productId, String platform) {
        Instant since = Instant.now().minus(historyDays, ChronoUnit.DAYS);
        return historyRepository.findByProductIdAndPlatformAndRecordedAtAfterOrderByRecordedAtAsc(
                productId, platform, since);
    }

    public BigDecimal getLowestPrice30d(String productId, String platform) {
        Instant since = Instant.now().minus(historyDays, ChronoUnit.DAYS);
        return historyRepository.findMinPriceSince(productId, platform, since);
    }

    private void checkAlerts(Long userId, FavoriteProduct favorite, BigDecimal currentPrice) {
        String productId = favorite.getProductId();
        String platform = favorite.getPlatform();
        BigDecimal favoritePrice = favorite.getPrice();

        // Check user-set target alerts
        alertRepository.findByUserIdAndProductIdAndActiveTrue(userId, productId)
                .ifPresent(alert -> {
                    if (shouldNotify(alert) && currentPrice.compareTo(alert.getTargetPrice()) <= 0) {
                        alert.setCurrentPrice(currentPrice);
                        alert.setTriggeredAt(Instant.now());
                        alert.setNotifiedAt(Instant.now());
                        alert.setActive(false);
                        alertRepository.save(alert);
                        sendNotification(userId, "target_reached", favorite, currentPrice, alert.getTargetPrice());
                    }
                });

        // Check history-low (only if we have enough data)
        BigDecimal lowest30d = getLowestPrice30d(productId, platform);
        if (lowest30d != null && currentPrice.compareTo(lowest30d) <= 0 && favoritePrice != null) {
            // This is a history low — check if user has any alert for dedup
            PriceAlert dedupAlert = alertRepository.findByUserIdAndProductIdAndActiveTrue(userId, productId).orElse(null);
            if ((dedupAlert == null || shouldNotify(dedupAlert)) && markNotificationDedup(userId, productId, "history_low")) {
                if (dedupAlert != null) {
                    dedupAlert.setCurrentPrice(currentPrice);
                    dedupAlert.setNotifiedAt(Instant.now());
                    alertRepository.save(dedupAlert);
                }
                sendNotification(userId, "history_low", favorite, currentPrice, null);
            }
        }

        // Check large drop (15%+)
        if (favoritePrice != null && favoritePrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal drop = favoritePrice.subtract(currentPrice)
                    .divide(favoritePrice, 4, RoundingMode.HALF_UP);
            if (drop.compareTo(LARGE_DROP_THRESHOLD) >= 0) {
                PriceAlert dedupAlert = alertRepository.findByUserIdAndProductIdAndActiveTrue(userId, productId).orElse(null);
                if ((dedupAlert == null || shouldNotify(dedupAlert)) && markNotificationDedup(userId, productId, "large_drop")) {
                    if (dedupAlert != null) {
                        dedupAlert.setCurrentPrice(currentPrice);
                        dedupAlert.setNotifiedAt(Instant.now());
                        alertRepository.save(dedupAlert);
                    }
                    sendNotification(userId, "large_drop", favorite, currentPrice, null);
                }
            }
        }
    }

    private boolean markNotificationDedup(Long userId, String productId, String alertType) {
        if (redisTemplate == null) {
            return true;
        }
        String key = "visioncart:price-alert:dedup:" + userId + ":" + productId + ":" + alertType;
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", Math.max(1, dedupDays), TimeUnit.DAYS);
            return Boolean.TRUE.equals(first);
        } catch (Exception error) {
            log.warn("Price alert dedup unavailable, sending notification without Redis dedup: {}", error.getMessage());
            return true;
        }
    }

    private boolean shouldNotify(PriceAlert alert) {
        if (alert.getNotifiedAt() == null) return true;
        Instant cutoff = Instant.now().minus(dedupDays, ChronoUnit.DAYS);
        return alert.getNotifiedAt().isBefore(cutoff);
    }

    private void sendNotification(Long userId, String alertType, FavoriteProduct favorite,
                                  BigDecimal currentPrice, BigDecimal targetPrice) {
        String message = buildMessage(alertType, favorite.getTitle(), currentPrice, targetPrice, favorite.getPrice());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "price_alert");
        payload.put("alertType", alertType);
        payload.put("productId", favorite.getProductId());
        payload.put("platform", favorite.getPlatform());
        payload.put("title", favorite.getTitle());
        payload.put("currentPrice", currentPrice);
        payload.put("targetPrice", targetPrice);
        payload.put("favoritePrice", favorite.getPrice());
        payload.put("message", message);

        messagingTemplate.convertAndSend("/topic/price-alert/" + userId, payload);
        log.info("Price alert sent to user {}: {} - {}", userId, alertType, favorite.getTitle());
    }

    private String buildMessage(String alertType, String title, BigDecimal currentPrice,
                                BigDecimal targetPrice, BigDecimal favoritePrice) {
        String shortTitle = title != null && title.length() > 30 ? title.substring(0, 30) + "..." : title;
        return switch (alertType) {
            case "target_reached" -> String.format("%s 已降至 ¥%s，低于你设定的 ¥%s！",
                    shortTitle, currentPrice.toPlainString(), targetPrice.toPlainString());
            case "history_low" -> String.format("📉 历史低价！%s 当前 ¥%s（近30天最低）",
                    shortTitle, currentPrice.toPlainString());
            case "large_drop" -> {
                BigDecimal drop = favoritePrice != null && favoritePrice.compareTo(BigDecimal.ZERO) > 0
                        ? favoritePrice.subtract(currentPrice).multiply(new BigDecimal("100"))
                                .divide(favoritePrice, 0, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                yield String.format("🔥 大幅降价！%s 降价 %s%%，¥%s→¥%s",
                        shortTitle, drop.toPlainString(), favoritePrice.toPlainString(), currentPrice.toPlainString());
            }
            default -> String.format("%s 价格变动: ¥%s", shortTitle, currentPrice.toPlainString());
        };
    }
}
