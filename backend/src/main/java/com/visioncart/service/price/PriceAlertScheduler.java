package com.visioncart.service.price;

import com.visioncart.config.VisionCartProperties;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.PriceAlert;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PriceAlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(PriceAlertScheduler.class);

    private final PriceAlertRepository alertRepository;
    private final FavoriteProductRepository favoriteRepository;
    private final PriceMonitorService priceMonitorService;
    private final int maxProductsPerRun;

    public PriceAlertScheduler(PriceAlertRepository alertRepository,
                               FavoriteProductRepository favoriteRepository,
                               PriceMonitorService priceMonitorService,
                               VisionCartProperties properties) {
        this.alertRepository = alertRepository;
        this.favoriteRepository = favoriteRepository;
        this.priceMonitorService = priceMonitorService;
        this.maxProductsPerRun = properties.getPriceMonitor().getMaxProductsPerRun();
    }

    @Scheduled(cron = "0 0 8,14,20 * * *")
    public void checkActiveAlerts() {
        List<PriceAlert> activeAlerts = alertRepository.findAllByActiveTrue();
        if (activeAlerts.isEmpty()) {
            return;
        }

        log.info("Price alert check: {} active alerts", activeAlerts.size());

        int checked = 0;
        for (PriceAlert alert : activeAlerts) {
            if (checked >= maxProductsPerRun) {
                log.info("Reached per-run limit ({}), remaining alerts deferred", maxProductsPerRun);
                break;
            }

            try {
                FavoriteProduct favorite = favoriteRepository
                        .findByProductIdAndUserId(alert.getProductId(), alert.getUserId())
                        .orElse(null);
                if (favorite == null) {
                    log.debug("Favorite not found for alert product {}, skipping", alert.getProductId());
                    continue;
                }

                priceMonitorService.refreshPrice(favorite, alert.getUserId());
                checked++;
            } catch (Exception e) {
                log.warn("Price check failed for {}: {}", alert.getProductId(), e.getMessage());
            }
        }

        log.info("Price alert check complete: {}/{} products checked", checked, activeAlerts.size());
    }
}
