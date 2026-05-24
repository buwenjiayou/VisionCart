package com.visioncart.service.nlp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.SearchFilter;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;

@Component
public class CacheKeyGenerator {
    private final ObjectMapper objectMapper;

    public CacheKeyGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String key(SearchFilter filter) {
        StringJoiner joiner = new StringJoiner("|");
        if (filter.priceRange() != null) {
            joiner.add("价格:" + value(filter.priceRange().min()) + "-" + value(filter.priceRange().max()));
        }
        joiner.add("品牌:" + list(filter.brands()));
        joiner.add("平台:" + list(filter.platforms()));
        joiner.add("颜色:" + list(filter.colors()));
        joiner.add("自营:" + value(filter.selfOperated()));
        joiner.add("评分:" + value(filter.ratingMin()));
        joiner.add("排序:" + value(filter.sortBy()) + ":" + value(filter.sortOrder()));
        joiner.add("关键词:" + value(filter.keyword()));
        return md5(joiner.toString());
    }

    public String textKey(String text) {
        return md5(text.trim().toLowerCase());
    }

    public String toJson(SearchFilter filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    public SearchFilter fromJson(String json) {
        try {
            return objectMapper.readValue(json, SearchFilter.class);
        } catch (Exception error) {
            return SearchFilter.empty();
        }
    }

    private String list(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream().sorted().reduce((left, right) -> left + "," + right).orElse("");
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String md5(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
