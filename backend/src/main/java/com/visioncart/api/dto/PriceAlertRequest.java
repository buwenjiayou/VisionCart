package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PriceAlertRequest(
        @NotBlank @JsonProperty("product_id") String productId,
        @NotNull @JsonProperty("target_price") BigDecimal targetPrice
) {
}
