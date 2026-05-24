package com.visioncart.api.dto;

import java.math.BigDecimal;

public record PlatformPriceStat(String platform, BigDecimal minPrice, BigDecimal avgPrice, long count) {
}
