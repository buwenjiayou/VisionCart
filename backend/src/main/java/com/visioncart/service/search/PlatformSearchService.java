package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PlatformSearchService {
    String platform();

    List<ProductCard> search(Map<String, String> attributes, SearchFilter filter, int page, int pageSize);

    /**
     * Fetch one product by product id/detail url for price refresh.
     * Platform implementations can override this when their API supports it.
     */
    default Optional<ProductCard> fetchByProductId(String productId, String detailUrl) {
        return Optional.empty();
    }
}
