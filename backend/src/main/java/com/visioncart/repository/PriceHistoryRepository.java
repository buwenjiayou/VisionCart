package com.visioncart.repository;

import com.visioncart.domain.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    List<PriceHistory> findByProductIdAndPlatformAndRecordedAtAfterOrderByRecordedAtAsc(
            String productId, String platform, Instant since);

    @Query("SELECT MIN(p.price) FROM PriceHistory p WHERE p.productId = :productId AND p.platform = :platform AND p.recordedAt >= :since")
    BigDecimal findMinPriceSince(@Param("productId") String productId, @Param("platform") String platform, @Param("since") Instant since);

    @Query("SELECT p.productId as productId, MIN(p.price) as minPrice FROM PriceHistory p WHERE p.productId IN :ids AND p.recordedAt >= :since GROUP BY p.productId")
    List<Object[]> findMinPricesByProductIds(@Param("ids") List<String> ids, @Param("since") Instant since);

    List<PriceHistory> findTop30ByProductIdAndPlatformOrderByRecordedAtDesc(String productId, String platform);

    @Modifying
    @Transactional
    @Query("DELETE FROM PriceHistory p WHERE p.recordedAt < :before")
    void deleteByRecordedAtBefore(Instant before);
}
