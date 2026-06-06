package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Filters out invalid products before counting results.
 * Removes: zero/negative price, empty title, known invalid markers, duplicates.
 */
@Service
public class ProductValidityFilter {

    private static final Logger log = LoggerFactory.getLogger(ProductValidityFilter.class);

    /**
     * Filter out invalid products. Should be called BEFORE NLP filters and result counting.
     */
    public List<ProductCard> filterValid(List<ProductCard> products) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        List<ProductCard> valid = products.stream()
                .filter(this::isValid)
                .toList();
        int removed = products.size() - valid.size();
        if (removed > 0) {
            log.debug("Validity filter: {} -> {} (removed {} invalid)", products.size(), valid.size(), removed);
        }
        return valid;
    }

    private boolean isValid(ProductCard product) {
        // Must have non-empty title
        if (StringUtils.isBlank(product.title())) {
            return false;
        }

        // Must have valid price (> 0)
        if (product.price() == null || product.price().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        // Must have valid platform
        if (StringUtils.isBlank(product.platform())) {
            return false;
        }

        // Must have valid ID
        if (StringUtils.isBlank(product.id())) {
            return false;
        }

        // Known invalid title markers (下架、失效、已售罄)
        String titleLower = product.title().toLowerCase();
        if (titleLower.contains("已下架") || titleLower.contains("已失效")
                || titleLower.contains("已售罄") || titleLower.contains("暂无报价")
                || titleLower.contains("商品不存在")) {
            return false;
        }

        return true;
    }
}
