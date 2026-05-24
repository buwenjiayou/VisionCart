package com.visioncart.service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.config.VisionCartProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PddSearchService implements PlatformSearchService {
    private static final Logger log = LoggerFactory.getLogger(PddSearchService.class);

    private final VisionCartProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public PddSearchService(VisionCartProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    @Override
    public String platform() {
        return "拼多多";
    }

    @Override
    public List<ProductCard> search(Map<String, String> attributes, SearchFilter filter, int page, int pageSize) {
        if (StringUtils.isAnyBlank(properties.getPdd().getClientId(), properties.getPdd().getClientSecret(), properties.getPdd().getPid())) {
            log.warn("PDD search skipped: PDD_CLIENT_ID, PDD_CLIENT_SECRET, or PDD_PID is not configured");
            return List.of();
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "pdd.ddk.goods.search");
            params.put("client_id", properties.getPdd().getClientId());
            params.put("timestamp", Instant.now().getEpochSecond());
            params.put("data_type", "JSON");
            params.put("keyword", keyword(attributes, filter));
            params.put("page", Math.max(1, page));
            params.put("page_size", Math.max(10, Math.min(100, pageSize)));
            params.put("pid", properties.getPdd().getPid());
            params.put("sign", sign(params));

            String body = restClient.post()
                    .uri(properties.getPdd().getApiUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(params)
                    .retrieve()
                    .body(String.class);
            return mapResponse(body);
        } catch (Exception error) {
            log.warn("PDD search failed: {}", error.toString());
            return List.of();
        }
    }

    private String keyword(Map<String, String> attributes, SearchFilter filter) {
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            return filter.keyword();
        }
        return String.join(" ",
                useful(attributes.get("品牌")),
                useful(attributes.get("颜色")),
                useful(attributes.get("款式")),
                StringUtils.defaultIfBlank(useful(attributes.get("类目")), "运动鞋")
        ).trim();
    }

    private String useful(String value) {
        String trimmed = StringUtils.defaultString(value).trim();
        return trimmed.isBlank()
                || "未知".equals(trimmed)
                || "未识别".equals(trimmed)
                || "unknown".equalsIgnoreCase(trimmed)
                ? ""
                : trimmed;
    }

    private String sign(Map<String, Object> params) throws Exception {
        StringBuilder raw = new StringBuilder(properties.getPdd().getClientSecret());
        params.keySet().stream()
                .filter(key -> !"sign".equals(key))
                .sorted()
                .forEach(key -> raw.append(key).append(params.get(key)));
        raw.append(properties.getPdd().getClientSecret());
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8))).toUpperCase();
    }

    private List<ProductCard> mapResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode list = root.path("goods_search_response").path("goods_list");
        if (!list.isArray()) {
            list = root.path("goods_list");
        }
        JsonNode errorResponse = root.path("error_response");
        if (!errorResponse.isMissingNode() && !errorResponse.isEmpty()) {
            throw new IllegalStateException(errorResponse.toString());
        }
        List<ProductCard> products = new ArrayList<>();
        for (JsonNode item : list) {
            long goodsId = item.path("goods_id").asLong();
            BigDecimal price = BigDecimal.valueOf(item.path("min_group_price").asLong(0)).movePointLeft(2);
            products.add(new ProductCard(
                    "pdd_" + goodsId,
                    item.path("goods_name").asText("拼多多商品"),
                    item.path("goods_thumbnail_url").asText(""),
                    price,
                    null,
                    "拼多多",
                    item.path("mall_coupon_discount_pct").asInt(0) > 0,
                    item.path("mall_name").asText("拼多多店铺"),
                    4.7,
                    item.path("sales_tip").asText("0").replaceAll("\\D", "").isBlank()
                            ? 0
                            : Long.parseLong(item.path("sales_tip").asText("0").replaceAll("\\D", "")),
                    0.82,
                    List.of("多多进宝"),
                    "https://mobile.yangkeduo.com/goods.html?goods_id=" + goodsId
            ));
        }
        return products;
    }
}
