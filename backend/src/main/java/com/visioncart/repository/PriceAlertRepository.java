package com.visioncart.repository;

import com.visioncart.domain.PriceAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {

    List<PriceAlert> findAllByActiveTrue();

    Page<PriceAlert> findByActiveTrue(Pageable pageable);

    List<PriceAlert> findAllByUserId(Long userId);

    Optional<PriceAlert> findByUserIdAndProductIdAndActiveTrue(Long userId, String productId);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO price_alert
                (product_id, platform, target_price, current_price, active, triggered_at, created_at, user_id, notified_at)
            VALUES
                (:productId, :platform, :targetPrice, :currentPrice, b'1', NULL, CURRENT_TIMESTAMP(6), :userId, NULL)
            ON DUPLICATE KEY UPDATE
                platform = VALUES(platform),
                target_price = VALUES(target_price),
                current_price = VALUES(current_price),
                active = b'1',
                triggered_at = NULL,
                notified_at = NULL
            """, nativeQuery = true)
    int upsertActiveAlert(@Param("userId") Long userId,
                          @Param("productId") String productId,
                          @Param("platform") String platform,
                          @Param("targetPrice") BigDecimal targetPrice,
                          @Param("currentPrice") BigDecimal currentPrice);

    @Modifying
    @Transactional
    void deleteByUserIdAndProductId(Long userId, String productId);
}
