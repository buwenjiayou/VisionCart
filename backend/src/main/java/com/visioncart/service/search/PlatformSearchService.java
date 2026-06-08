package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PlatformSearchService {
    String platform();

    /**
     * true = 仅国内可见（淘宝、拼多多），false = 国外也可见（eBay）
     */
    default boolean domesticOnly() {
        return true;
    }

    List<ProductCard> search(Map<String, String> attributes, SearchFilter filter, int page, int pageSize);

    /**
     * P0-5: 通过 productId/detailUrl 精确查询单个商品（用于价格刷新）。
     * 默认返回空，各平台按需覆写。
     */
    default Optional<ProductCard> fetchByProductId(String productId, String detailUrl) {
        return Optional.empty();
    }
}
