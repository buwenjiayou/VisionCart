package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PriceAlertRequest(
        @NotBlank @JsonProperty("product_id") String productId,
        @NotNull @DecimalMin(value = "0.01", message = "目标价格必须大于0") @JsonProperty("target_price") BigDecimal targetPrice
) {
}
