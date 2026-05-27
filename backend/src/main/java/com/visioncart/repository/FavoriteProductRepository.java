package com.visioncart.repository;

import com.visioncart.domain.FavoriteProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface FavoriteProductRepository extends JpaRepository<FavoriteProduct, Long> {
    Optional<FavoriteProduct> findByProductIdAndUserId(String productId, Long userId);

    List<FavoriteProduct> findByProductId(String productId);

    List<FavoriteProduct> findByUserIdAndProductIdIn(Long userId, List<String> productIds);

    List<FavoriteProduct> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Transactional
    void deleteByProductIdAndUserId(String productId, Long userId);

    List<FavoriteProduct> findAllByUserId(Long userId);
}
