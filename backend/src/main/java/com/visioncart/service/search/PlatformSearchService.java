package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;

import java.util.List;
import java.util.Map;

public interface PlatformSearchService {
    String platform();

    List<ProductCard> search(Map<String, String> attributes, SearchFilter filter, int page, int pageSize);
}
