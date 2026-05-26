package com.visioncart.repository;

import com.visioncart.domain.PriceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {

    List<PriceAlert> findAllByActiveTrue();

    List<PriceAlert> findAllByUserId(Long userId);

    Optional<PriceAlert> findByUserIdAndProductIdAndActiveTrue(Long userId, String productId);

    @Modifying
    @Transactional
    void deleteByUserIdAndProductId(Long userId, String productId);
}
