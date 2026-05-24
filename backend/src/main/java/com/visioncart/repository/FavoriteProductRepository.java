package com.visioncart.repository;

import com.visioncart.domain.FavoriteProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteProductRepository extends JpaRepository<FavoriteProduct, Long> {
    Optional<FavoriteProduct> findByProductId(String productId);

    List<FavoriteProduct> findTop50ByOrderByCreatedAtDesc();
}
