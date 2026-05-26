package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;

import java.util.List;
import java.util.Map;

public interface PlatformSearchService {
    String platform();

    /**
     * true = 仅国内可见（淘宝、拼多多），false = 国外也可见（eBay）
     */
    default boolean domesticOnly() {
        return true;
    }

    List<ProductCard> search(Map<String, String> attributes, SearchFilter filter, int page, int pageSize);
}
