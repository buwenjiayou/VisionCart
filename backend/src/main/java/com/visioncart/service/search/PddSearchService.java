package com.visioncart.service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.config.VisionCartProperties;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PddSearchService implements PlatformSearchService {
    private static final Logger log = LoggerFactory.getLogger(PddSearchService.class);
    private static final long DETAIL_CACHE_TTL_MS = Duration.ofMinutes(30).toMillis();

    private final VisionCartProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Map<String, CachedDetail> detailCache = new ConcurrentHashMap<>();
    private final Map<String, CachedUrl> promotionUrlCache = new ConcurrentHashMap<>();

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
            Map<String, ProductCard> byId = new LinkedHashMap<>();
            for (String query : SearchQueryBuilder.pddQueries(attributes, filter, "商品")) {
                mapResponse(executeSearch(query, page, pageSize)).forEach(product -> byId.putIfAbsent(product.id(), product));
                List<ProductCard> relevant = relevantProducts(byId, attributes, pageSize);
                if (relevant.size() >= Math.min(3, Math.max(1, pageSize))) {
                    return relevant;
                }
            }
            List<ProductCard> relevant = relevantProducts(byId, attributes, pageSize);
            if (!relevant.isEmpty()) {
                return relevant;
            }
            return byId.values().stream().limit(pageSize).toList();
        } catch (Exception error) {
            log.warn("PDD search failed: {}", error.toString());
            return List.of();
        }
    }

    private String executeSearch(String keyword, int page, int pageSize) throws Exception {
        Map<String, Object> params = baseParams("pdd.ddk.goods.search");
        params.put("keyword", keyword);
        params.put("page", Math.max(1, page));
        params.put("page_size", Math.max(10, Math.min(100, pageSize)));
        params.put("pid", properties.getPdd().getPid());
        params.put("sign", sign(params));
        return call(params);
    }

    private List<ProductCard> relevantProducts(Map<String, ProductCard> byId, Map<String, String> attributes, int pageSize) {
        return byId.values().stream()
                .filter(product -> SearchTextUtils.relevantToCoreProduct(product.title(), attributes))
                .limit(pageSize)
                .toList();
    }

    private Map<String, Object> baseParams(String type) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", type);
        params.put("client_id", properties.getPdd().getClientId());
        params.put("timestamp", Instant.now().getEpochSecond());
        params.put("data_type", "JSON");
        return params;
    }

    private String call(Map<String, Object> params) {
        return restClient.post()
                .uri(properties.getPdd().getApiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(params)
                .retrieve()
                .body(String.class);
    }

    private String sign(Map<String, Object> params) throws Exception {
        StringBuilder raw = new StringBuilder(properties.getPdd().getClientSecret());
        params.keySet().stream()
                .filter(key -> !"sign".equals(key))
                .sorted()
                .forEach(key -> raw.append(key).append(signValue(params.get(key))));
        raw.append(properties.getPdd().getClientSecret());
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8))).toUpperCase();
    }

    private String signValue(Object value) {
        if (value instanceof Iterable<?> || value instanceof Map<?, ?>) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception ignored) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private List<ProductCard> mapResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode errorResponse = root.path("error_response");
        if (!errorResponse.isMissingNode() && !errorResponse.isEmpty()) {
            throw new IllegalStateException(errorResponse.toString());
        }
        JsonNode response = root.path("goods_search_response");
        String searchId = firstText(response, "search_id");
        JsonNode list = response.path("goods_list");
        if (!list.isArray()) {
            list = root.path("goods_list");
        }

        List<ProductCard> products = new ArrayList<>();
        for (JsonNode item : list) {
            String goodsSign = firstText(item, "goods_sign");
            ProductCard base = toProduct(item, goodsSign);
            products.add(enrichDetail(base, goodsSign, searchId));
        }
        return products;
    }

    private ProductCard enrichDetail(ProductCard base, String goodsSign, String searchId) {
        if (StringUtils.isBlank(goodsSign)) {
            return base;
        }
        String detailUrl = promotedDetailUrl(goodsSign, searchId, base.detailUrl());
        try {
            PddDetail detail = loadDetail(goodsSign, searchId);
            if (detail == null) {
                return withDetailUrl(base, detailUrl);
            }
            List<String> tags = new ArrayList<>(base.tags());
            detail.tags().forEach(tag -> {
                if (!tags.contains(tag)) {
                    tags.add(tag);
                }
            });
            long sales = Math.max(base.sales(), detail.sales());
            double rating = detail.rating() > 0 ? detail.rating() : base.rating();
            return new ProductCard(
                    base.id(),
                    StringUtils.defaultIfBlank(detail.title(), base.title()),
                    StringUtils.defaultIfBlank(detail.imageUrl(), base.imageUrl()),
                    detail.price().compareTo(BigDecimal.ZERO) > 0 ? detail.price() : base.price(),
                    detail.originalPrice().compareTo(BigDecimal.ZERO) > 0 ? detail.originalPrice() : base.originalPrice(),
                    base.platform(),
                    base.selfOperated(),
                    StringUtils.defaultIfBlank(detail.shopName(), base.shopName()),
                    rating,
                    sales,
                    base.similarity(),
                    tags,
                    detailUrl,
                    StringUtils.defaultIfBlank(detail.brand(), base.brand()),
                    detail.rating() > 0 ? detail.ratingSource() : base.ratingSource(),
                    StringUtils.defaultIfBlank(detail.salesLabel(), base.salesLabel())
            );
        } catch (Exception e) {
            log.debug("PDD detail enrichment skipped for {}: {}", base.id(), e.toString());
            return withDetailUrl(base, detailUrl);
        }
    }

    private ProductCard withDetailUrl(ProductCard base, String detailUrl) {
        if (StringUtils.equals(detailUrl, base.detailUrl())) {
            return base;
        }
        return new ProductCard(
                base.id(),
                base.title(),
                base.imageUrl(),
                base.price(),
                base.originalPrice(),
                base.platform(),
                base.selfOperated(),
                base.shopName(),
                base.rating(),
                base.sales(),
                base.similarity(),
                base.tags(),
                detailUrl,
                base.brand(),
                base.ratingSource(),
                base.salesLabel()
        );
    }

    private PddDetail loadDetail(String goodsSign, String searchId) throws Exception {
        long now = System.currentTimeMillis();
        CachedDetail cached = detailCache.get(goodsSign);
        if (cached != null && cached.expiresAt() > now) {
            return cached.detail();
        }
        Map<String, Object> params = baseParams("pdd.ddk.goods.detail");
        params.put("goods_sign", goodsSign);
        params.put("pid", properties.getPdd().getPid());
        params.put("goods_img_type", 1);
        params.put("need_sku_info", false);
        if (StringUtils.isNotBlank(searchId)) {
            params.put("search_id", searchId);
        }
        params.put("sign", sign(params));
        PddDetail detail = mapDetailResponse(call(params));
        if (detail != null) {
            detailCache.put(goodsSign, new CachedDetail(detail, now + DETAIL_CACHE_TTL_MS));
        }
        return detail;
    }

    private String promotedDetailUrl(String goodsSign, String searchId, String fallback) {
        try {
            String url = loadPromotionUrl(goodsSign, searchId);
            return StringUtils.defaultIfBlank(url, fallback);
        } catch (Exception e) {
            log.debug("PDD promotion url skipped for goods sign hash {}: {}", stableHash(goodsSign), e.toString());
            return fallback;
        }
    }

    private String loadPromotionUrl(String goodsSign, String searchId) throws Exception {
        String cacheKey = goodsSign + ":" + StringUtils.defaultString(searchId);
        long now = System.currentTimeMillis();
        CachedUrl cached = promotionUrlCache.get(cacheKey);
        if (cached != null && cached.expiresAt() > now) {
            return cached.url();
        }

        Map<String, Object> params = baseParams("pdd.ddk.goods.promotion.url.generate");
        params.put("p_id", properties.getPdd().getPid());
        // PDD documents this as String[], but the gateway signs and accepts it as a JSON string.
        params.put("goods_sign_list", objectMapper.writeValueAsString(List.of(goodsSign)));
        params.put("generate_short_url", true);
        params.put("generate_schema_url", false);
        params.put("generate_we_app", false);
        params.put("generate_qq_app", false);
        params.put("multi_group", false);
        if (StringUtils.isNotBlank(searchId)) {
            params.put("search_id", searchId);
        }
        params.put("sign", sign(params));

        String url = mapPromotionUrl(call(params));
        if (StringUtils.isNotBlank(url)) {
            promotionUrlCache.put(cacheKey, new CachedUrl(url, now + DETAIL_CACHE_TTL_MS));
        }
        return url;
    }

    private String mapPromotionUrl(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode errorResponse = root.path("error_response");
        if (!errorResponse.isMissingNode() && !errorResponse.isEmpty()) {
            throw new IllegalStateException(errorResponse.toString());
        }
        JsonNode urls = root.path("goods_promotion_url_generate_response").path("goods_promotion_url_list");
        if (!urls.isArray()) {
            urls = root.findPath("goods_promotion_url_list");
        }
        if (urls.isArray() && !urls.isEmpty()) {
            return SearchTextUtils.normalizeUrl(firstText(urls.get(0),
                    "mobile_url",
                    "mobile_short_url",
                    "url",
                    "short_url",
                    "schema_url"));
        }
        return "";
    }

    private PddDetail mapDetailResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode errorResponse = root.path("error_response");
        if (!errorResponse.isMissingNode() && !errorResponse.isEmpty()) {
            throw new IllegalStateException(errorResponse.toString());
        }
        JsonNode details = root.path("goods_detail_response").path("goods_details");
        if (!details.isArray()) {
            details = root.findPath("goods_details");
        }
        if (details.isArray() && !details.isEmpty()) {
            return toDetail(details.get(0));
        }
        return null;
    }

    private ProductCard toProduct(JsonNode item, String goodsSign) {
        String goodsId = firstText(item, "goods_id");
        String title = firstText(item, "goods_name", "goods_desc");
        if (StringUtils.isBlank(title)) {
            title = "拼多多商品";
        }
        BigDecimal price = BigDecimal.valueOf(item.path("min_group_price").asLong(0)).movePointLeft(2);
        BigDecimal originalPrice = BigDecimal.valueOf(item.path("min_normal_price").asLong(0)).movePointLeft(2);
        RatingInfo rating = ratingInfo(item);
        long sales = SearchTextUtils.maxHumanCount(
                firstText(item, "sales_tip"),
                firstText(item, "sales"),
                firstText(item, "goods_sale_num"),
                firstText(item, "sold_quantity"));
        String shopName = StringUtils.defaultIfBlank(firstText(item, "mall_name"), "拼多多店铺");
        String brand = StringUtils.defaultIfBlank(firstText(item, "brand_name"), SearchTextUtils.inferBrand(title, shopName));
        return new ProductCard(
                stableProductId(goodsSign, goodsId),
                title,
                SearchTextUtils.normalizeUrl(firstText(item, "goods_image_url", "goods_thumbnail_url")),
                price,
                originalPrice.compareTo(BigDecimal.ZERO) > 0 ? originalPrice : null,
                "拼多多",
                item.path("merchant_type").asInt(0) == 3,
                shopName,
                rating.value(),
                sales,
                0.82,
                tags(item),
                fallbackDetailUrl(goodsId, title),
                brand,
                rating.source(),
                pddSalesLabel(firstText(item, "sales_tip"), sales)
        );
    }

    private PddDetail toDetail(JsonNode item) {
        String title = firstText(item, "goods_name", "goods_desc");
        BigDecimal price = BigDecimal.valueOf(item.path("min_group_price").asLong(0)).movePointLeft(2);
        BigDecimal originalPrice = BigDecimal.valueOf(item.path("min_normal_price").asLong(0)).movePointLeft(2);
        String shopName = firstText(item, "mall_name");
        RatingInfo rating = ratingInfo(item);
        long sales = SearchTextUtils.maxHumanCount(
                firstText(item, "sales_tip"),
                firstText(item, "sales"),
                firstText(item, "goods_sale_num"),
                firstText(item, "sold_quantity"));
        String brand = StringUtils.defaultIfBlank(firstText(item, "brand_name"), SearchTextUtils.inferBrand(title, shopName));
        return new PddDetail(
                title,
                SearchTextUtils.normalizeUrl(firstText(item, "goods_image_url", "goods_thumbnail_url")),
                price,
                originalPrice,
                shopName,
                brand,
                rating.value(),
                rating.source(),
                sales,
                pddSalesLabel(firstText(item, "sales_tip"), sales),
                tags(item)
        );
    }

    private RatingInfo ratingInfo(JsonNode item) {
        double itemRating = parseRating(item.path("goods_eval_score"), item.path("goods_rate"));
        if (itemRating > 0) {
            return new RatingInfo(itemRating, "item_rating");
        }
        double shopDsr = parseRating(
                item.path("desc_txt"),
                item.path("serv_txt"),
                item.path("lgst_txt"),
                item.path("avg_desc"),
                item.path("avg_serv"),
                item.path("avg_lgst"));
        if (shopDsr > 0) {
            return new RatingInfo(shopDsr, "shop_dsr");
        }
        return new RatingInfo(0.0, "none");
    }

    private List<String> tags(JsonNode item) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("多多进宝");
        addStringArray(tags, item.path("unified_tags"));
        if (item.path("coupon_discount").asLong(0) > 0 || item.path("mall_coupon_discount_pct").asInt(0) > 0) {
            boolean hasCouponTag = tags.stream().anyMatch(tag -> tag.contains("券"));
            if (!hasCouponTag) {
                tags.add("有券");
            }
        }
        addServiceTags(tags, item.path("service_tags"));
        return tags.stream().limit(6).toList();
    }

    private void addStringArray(Set<String> tags, JsonNode values) {
        if (!values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            String tag = value.asText("");
            if (StringUtils.isNotBlank(tag)) {
                tags.add(tag);
            }
        }
    }

    private void addServiceTags(Set<String> tags, JsonNode values) {
        if (!values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            switch (value.asInt()) {
                case 1 -> tags.add("包邮");
                case 2 -> tags.add("七天退换");
                case 3 -> tags.add("退货包运费");
                case 12 -> tags.add("24小时发货");
                case 13 -> tags.add("48小时发货");
                case 24 -> tags.add("极速退款");
                case 25 -> tags.add("品质保障");
                default -> { }
            }
        }
    }

    private String pddSalesLabel(String salesTip, long sales) {
        String useful = SearchTextUtils.useful(salesTip);
        if (StringUtils.isNotBlank(useful)) {
            if (useful.contains("已") || useful.contains("销量") || useful.contains("售")) {
                return useful;
            }
            return "已售 " + useful;
        }
        return SearchTextUtils.salesLabel(sales, "monthly");
    }

    private String fallbackDetailUrl(String goodsId, String title) {
        if (StringUtils.isNotBlank(goodsId)) {
            return "https://mobile.yangkeduo.com/goods.html?goods_id=" + goodsId;
        }
        String encodedTitle = java.net.URLEncoder.encode(StringUtils.defaultString(title), StandardCharsets.UTF_8);
        return "https://mobile.yangkeduo.com/search_result.html?search_key=" + encodedTitle;
    }

    private String stableProductId(String goodsSign, String goodsId) {
        if (StringUtils.isNotBlank(goodsId)) {
            return "pdd_" + goodsId;
        }
        return "pdd_" + stableHash(goodsSign);
    }

    private String stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return Integer.toUnsignedString(StringUtils.defaultString(value).hashCode());
        }
    }

    private double parseRating(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                double value = node.asDouble(0);
                if (value > 5.0 && value <= 100.0) {
                    return Math.round(value / 20.0 * 10.0) / 10.0;
                }
                if (value >= 1.0 && value <= 5.0) {
                    return Math.round(value * 10.0) / 10.0;
                }
            }
        }
        return 0.0;
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

    @Scheduled(fixedDelay = 300_000)
    void evictExpiredCaches() {
        long now = System.currentTimeMillis();
        detailCache.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        promotionUrlCache.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
    }

    private record CachedDetail(PddDetail detail, long expiresAt) {}

    private record CachedUrl(String url, long expiresAt) {}

    private record RatingInfo(double value, String source) {}

    private record PddDetail(
            String title,
            String imageUrl,
            BigDecimal price,
            BigDecimal originalPrice,
            String shopName,
            String brand,
            double rating,
            String ratingSource,
            long sales,
            String salesLabel,
            List<String> tags
    ) {}
}
