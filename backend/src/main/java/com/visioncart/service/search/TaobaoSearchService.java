package com.visioncart.service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.config.VisionCartProperties;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TaobaoSearchService implements PlatformSearchService {
    private static final Logger log = LoggerFactory.getLogger(TaobaoSearchService.class);

    private final VisionCartProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private volatile long apiClockOffsetMs = 0;

    public TaobaoSearchService(VisionCartProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    @Override
    public String platform() {
        return "淘宝";
    }

    @Override
    public List<ProductCard> search(Map<String, String> attributes, SearchFilter filter, int page, int pageSize) {
        VisionCartProperties.Taobao tb = properties.getTaobao();
        if (StringUtils.isAnyBlank(tb.getAppKey(), tb.getAppSecret())) {
            log.warn("Taobao search skipped: TAOBAO_APP_KEY or TAOBAO_APP_SECRET is not configured");
            return List.of();
        }

        try {
            Map<String, String> params = buildParams(attributes, filter, page, pageSize, tb);
            String body = executeSearch(tb, params);

            return mapResponse(body);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Invalid timestamp")) {
                syncClockOffset(tb.getApiUrl());
                try {
                    Map<String, String> retryParams = buildParams(attributes, filter, page, pageSize, tb);
                    return mapResponse(executeSearch(tb, retryParams));
                } catch (Exception retryError) {
                    log.warn("Taobao search retry failed: {}", retryError.toString());
                }
            }
            log.warn("Taobao search failed: {}", e.toString());
            return List.of();
        }
    }

    private Map<String, String> buildParams(Map<String, String> attributes,
                                            SearchFilter filter,
                                            int page,
                                            int pageSize,
                                            VisionCartProperties.Taobao tb) throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("method", "taobao.tbk.dg.material.optional.upgrade");
        params.put("app_key", tb.getAppKey());
        params.put("timestamp", taobaoTimestamp());
        params.put("v", "2.0");
        params.put("sign_method", "md5");
        params.put("format", "json");

        params.put("q", keyword(attributes, filter));
        params.put("page_no", String.valueOf(Math.max(1, page)));
        params.put("page_size", String.valueOf(Math.min(100, Math.max(10, pageSize))));
        params.put("platform", "2");
        if (StringUtils.isNotBlank(tb.getAdzoneId())) {
            params.put("adzone_id", tb.getAdzoneId());
        }

        if (filter.priceRange() != null) {
            if (filter.priceRange().min() != null) {
                params.put("start_price", String.valueOf(filter.priceRange().min().longValue()));
            }
            if (filter.priceRange().max() != null) {
                params.put("end_price", String.valueOf(filter.priceRange().max().longValue()));
            }
        }

        if (filter.sortBy() != null) {
            String sort = switch (filter.sortBy()) {
                case "price" -> "price_asc".equals(filter.sortOrder()) ? "price_asc" : "price_des";
                case "sales" -> "total_sales_des";
                case "rating" -> "tk_rate_des";
                default -> null;
            };
            if (sort != null) {
                params.put("sort", sort);
            }
        }

        params.put("sign", sign(params, tb.getAppSecret()));
        return params;
    }

    private String executeSearch(VisionCartProperties.Taobao tb, Map<String, String> params) {
        return restClient.post()
                .uri(tb.getApiUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(buildQueryString(params))
                .retrieve()
                .body(String.class);
    }

    private String keyword(Map<String, String> attributes, SearchFilter filter) {
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            return filter.keyword();
        }
        String brand = SearchTextUtils.useful(attributes.get("品牌"));
        String style = SearchTextUtils.useful(attributes.get("款式"));
        String category = SearchTextUtils.useful(attributes.get("类目"));
        if (StringUtils.isNotBlank(brand)) {
            return brand;
        }
        if (StringUtils.isNotBlank(style)) {
            return style;
        }
        if (StringUtils.isNotBlank(category)) {
            return category;
        }
        return "商品";
    }

    private String sign(Map<String, String> params, String secret) throws Exception {
        StringBuilder raw = new StringBuilder(secret);
        params.entrySet().stream()
                .filter(e -> !"sign".equals(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> raw.append(e.getKey()).append(e.getValue()));
        raw.append(secret);
        MessageDigest md = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(md.digest(raw.toString().getBytes(StandardCharsets.UTF_8))).toUpperCase();
    }

    private String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) sb.append("&");
            sb.append(k).append("=").append(java.net.URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private String taobaoTimestamp() {
        Instant now = Instant.now().plusMillis(apiClockOffsetMs);
        return LocalDateTime.ofInstant(now, ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void syncClockOffset(String apiUrl) {
        try {
            ResponseEntity<String> response = restClient.get()
                    .uri(apiUrl)
                    .retrieve()
                    .toEntity(String.class);
            long serverMs = response.getHeaders().getDate();
            if (serverMs > 0) {
                apiClockOffsetMs = serverMs - System.currentTimeMillis();
                log.info("Taobao API clock offset adjusted to {} ms", apiClockOffsetMs);
            }
        } catch (Exception ignored) {
            apiClockOffsetMs = Duration.ofHours(8).toMillis();
        }
    }

    private List<ProductCard> mapResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode errorResponse = root.path("error_response");
        if (!errorResponse.isMissingNode() && !errorResponse.isEmpty()) {
            throw new IllegalStateException(errorResponse.toString());
        }
        JsonNode results = root.path("tbk_dg_material_optional_response")
                .path("result_list")
                .path("map_data");
        if (!results.isArray() || results.isEmpty()) {
            results = root.path("tbk_dg_material_optional_upgrade_response")
                    .path("result_list")
                    .path("map_data");
        }

        if (!results.isArray() || results.isEmpty()) {
            return List.of();
        }

        List<ProductCard> products = new ArrayList<>();
        for (JsonNode item : results) {
            JsonNode basicInfo = item.path("item_basic_info");
            JsonNode priceInfo = item.path("price_promotion_info");
            String numIid = firstText(item, "num_iid", "item_id", "item_id_str", "itemId");
            String title = StringUtils.defaultIfBlank(
                    firstText(item, "title", "short_title", "item_description"),
                    firstText(basicInfo, "title", "short_title", "sub_title"));
            title = StringUtils.defaultIfBlank(title, "淘宝商品");
            String pictUrl = firstText(item, "pict_url", "white_image", "item_url", "small_images");
            if (StringUtils.isBlank(pictUrl)) {
                pictUrl = firstText(basicInfo, "pict_url", "white_image", "small_images");
            }
            BigDecimal zkPrice = firstPrice(
                    firstText(priceInfo, "final_promotion_price", "promotion_price", "final_price"),
                    firstText(item, "zk_final_price", "price", "final_price", "reserve_price"));
            BigDecimal origPrice = firstPrice(
                    firstText(item, "reserve_price", "orig_price", "zk_final_price", "price"),
                    firstText(priceInfo, "reserve_price", "origin_price", "final_promotion_price"));
            String shopName = StringUtils.defaultIfBlank(
                    firstText(item, "shop_title", "shop_name", "seller_nick"),
                    firstText(basicInfo, "shop_title", "shop_name", "seller_nick"));
            shopName = StringUtils.defaultIfBlank(shopName, "淘宝店铺");
            long sales = parseSales(firstLong(item, "volume", "sell_num", "total_sales", "sales"));
            if (sales == 0) {
                sales = parseSales(firstLong(basicInfo, "volume", "sell_num", "total_sales", "tk_total_sales", "annual_vol"));
            }
            boolean hasCoupon = item.path("coupon_amount").asLong(0) > 0
                    || priceInfo.path("final_promotion_path_list").path("final_promotion_path_map_data").isArray();
            String userType = firstLong(item, "user_type") == 1 || firstLong(basicInfo, "user_type") == 1 ? "天猫" : "淘宝";
            boolean selfOperated = "天猫".equals(userType);

            double rating = parseRating(
                    firstText(item, "shop_dsr", "item_score"),
                    firstText(basicInfo, "shop_dsr", "item_score"));

            List<String> tags = new ArrayList<>();
            tags.add(userType);
            if (item.path("free_shipment").asBoolean()) tags.add("包邮");
            if (hasCoupon) tags.add("有券");

            products.add(new ProductCard(
                    "tb_" + numIid,
                    title,
                    pictUrl,
                    zkPrice,
                    origPrice.compareTo(zkPrice) > 0 ? origPrice : null,
                    "淘宝",
                    selfOperated,
                    shopName,
                    rating,
                    sales,
                    0.85,
                    tags,
                    "https://item.taobao.com/item.htm?id=" + numIid
            ));
        }

        return products;
    }

    private String firstText(JsonNode item, String... names) {
        for (String name : names) {
            JsonNode value = item.path(name);
            if (value.isArray() && !value.isEmpty()) {
                value = value.get(0);
            }
            if (!value.isMissingNode() && !value.isNull() && StringUtils.isNotBlank(value.asText())) {
                return value.asText();
            }
        }
        return "";
    }

    private BigDecimal parsePrice(String value) {
        try {
            return new BigDecimal(value);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal firstPrice(String... values) {
        for (String value : values) {
            BigDecimal price = parsePrice(value);
            if (price.compareTo(BigDecimal.ZERO) > 0) {
                return price;
            }
        }
        return BigDecimal.ZERO;
    }

    private long firstLong(JsonNode item, String... names) {
        for (String name : names) {
            JsonNode value = item.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asLong(0);
            }
        }
        return 0;
    }

    private long parseSales(long volume) {
        if (volume >= 10000) return volume;
        // taobao sometimes returns values like "1.5万+" — but API returns long, so just use as-is
        return volume;
    }

    private double parseRating(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                try {
                    double rating = Double.parseDouble(value.trim());
                    // shop_dsr is typically 1.0-5.0 range, or may be in 0-100 percentage format
                    if (rating > 5.0 && rating <= 100.0) {
                        return rating / 20.0; // convert percentage to 5-star scale
                    }
                    if (rating >= 1.0 && rating <= 5.0) {
                        return Math.round(rating * 10.0) / 10.0;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0.0;
    }
}
