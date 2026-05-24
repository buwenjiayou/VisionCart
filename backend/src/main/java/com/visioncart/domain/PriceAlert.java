package com.visioncart.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "price_alert")
public class PriceAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String productId;
    private String platform;
    private BigDecimal targetPrice;
    private BigDecimal currentPrice;
    private boolean active = true;
    private Instant triggeredAt;
    private Instant createdAt = Instant.now();
}
