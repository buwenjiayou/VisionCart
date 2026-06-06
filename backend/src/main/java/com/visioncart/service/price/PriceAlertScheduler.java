package com.visioncart.service.price;

import com.visioncart.config.VisionCartProperties;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.PriceAlert;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "visioncart.price-monitor",
        name = "scheduled-enabled",
        havingValue = "true",
        matchIfMissing = true
)
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
        int checked = 0;
        int totalFetched = 0;
        int page = 0;

        while (checked < maxProductsPerRun) {
            int pageSize = Math.min(maxProductsPerRun - checked, 50);
            Page<PriceAlert> alertPage = alertRepository.findByActiveTrue(PageRequest.of(page, pageSize));
            List<PriceAlert> alerts = alertPage.getContent();
            if (alerts.isEmpty()) {
                break;
            }
            totalFetched += alerts.size();

            for (PriceAlert alert : alerts) {
                if (checked >= maxProductsPerRun) {
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

            if (!alertPage.hasNext()) {
                break;
            }
            page++;
        }

        log.info("Price alert check complete: {} products checked", checked);
    }
}
