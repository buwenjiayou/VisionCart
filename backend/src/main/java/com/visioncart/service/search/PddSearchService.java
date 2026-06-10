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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class PddSearchService implements PlatformSearchService {
    private static final Logger log = LoggerFactory.getLogger(PddSearchService.class);

    private final VisionCartProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final java.util.concurrent.ExecutorService searchExecutor;
    private final java.util.concurrent.ExecutorService detailExecutor;
    private final Cache<String, CachedDetail> detailCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();
    private final Cache<String, CachedUrl> promotionUrlCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    public PddSearchService(VisionCartProperties properties, ObjectMapper objectMapper,
                            @org.springframework.beans.factory.annotation.Qualifier("searchExecutor") java.util.concurrent.ExecutorService searchExecutor,
                            @org.springframework.beans.factory.annotation.Qualifier("detailExecutor") java.util.concurrent.ExecutorService detailExecutor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.searchExecutor = searchExecutor;
        this.detailExecutor = detailExecutor;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        Duration platformTimeout = platformTimeout();
        factory.setConnectTimeout(platformTimeout);
        factory.setReadTimeout(platformTimeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String platform() {
        return "拼多多";
    }

    @Override
    public Optional<ProductCard> fetchByProductId(String productId, String detailUrl) {
        if (StringUtils.isAnyBlank(properties.getPdd().getClientId(), properties.getPdd().getClientSecret(), properties.getPdd().getPid())) {
            return Optional.empty();
        }

        String goodsSign = parseGoodsSign(productId, detailUrl);
        String goodsId = parseGoodsId(productId, detailUrl);
        if (StringUtils.isAllBlank(goodsSign, goodsId)) {
            return Optional.empty();
        }

        try {
            PddDetail detail = StringUtils.isNotBlank(goodsSign)
                    ? loadDetail(goodsSign, "")
                    : loadDetailByGoodsId(goodsId);
            if (detail == null) {
                return Optional.empty();
            }
            ProductCard card = detailToProductCard(detail, goodsSign, goodsId, detailUrl);
            log.info("fetchByProductId: PDD goodsId={} goodsSignHash={} -> title='{}', price={}",
                    StringUtils.defaultIfBlank(goodsId, detail.goodsId()), stableHash(goodsSign), card.title(), card.price());
            return Optional.of(card);
        } catch (Exception e) {
            log.warn("fetchByProductId failed for PDD productId={} detailUrl={}: {}", productId, detailUrl, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ProductCard> search(Map<String, String> attributes, SearchFilter filter, int page, int pageSize) {
        if (StringUtils.isAnyBlank(properties.getPdd().getClientId(), properties.getPdd().getClientSecret(), properties.getPdd().getPid())) {
            log.warn("PDD search skipped: PDD_CLIENT_ID, PDD_CLIENT_SECRET, or PDD_PID is not configured");
            return List.of();
        }

        try {
            int fetchSize = SearchQueryBuilder.platformFetchSize(pageSize);
            List<String> queries = SearchQueryBuilder.pddQueries(attributes, filter, "商品");
            log.info("PDD search: {} queries, attributes={}", queries.size(), attributes);

            // Run all queries in parallel
            List<CompletableFuture<QueryResult>> futures = queries.stream()
                    .map(query -> CompletableFuture.supplyAsync(() -> {
                        try {
                            List<ProductCard> results = mapResponse(executeSearch(query, page, fetchSize));
                            log.info("PDD query '{}' returned {} products", query, results.size());
                            return new QueryResult(query, results, true);
                        } catch (Exception e) {
                            log.warn("PDD query '{}' failed: {}", query, e.toString());
                            return new QueryResult(query, List.of(), false);
                        }
                    }, searchExecutor))
                    .toList();

            // Collect results as they complete, merge into deduplicated map
            waitForQueries(futures);
            Map<String, ProductCard> byId = new LinkedHashMap<>();
            int completedQueries = 0;
            int successfulQueries = 0;
            for (CompletableFuture<QueryResult> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                    continue;
                }
                completedQueries++;
                QueryResult queryResult = future.getNow(QueryResult.failed("unknown"));
                if (queryResult.success()) {
                    successfulQueries++;
                }
                queryResult.products().forEach(product -> byId.putIfAbsent(product.id(), product));
            }
            if (!queries.isEmpty() && successfulQueries == 0) {
                throw new IllegalStateException("PDD query batch successful 0 of " + queries.size()
                        + " queries (completed=" + completedQueries + ")");
            }
            if (successfulQueries < queries.size()) {
                log.warn("PDD query batch partial: successful {} of {} queries (completed={})",
                        successfulQueries, queries.size(), completedQueries);
            }

            List<ProductCard> relevant = relevantProducts(byId, fetchSize);
            log.info("PDD search: {} raw -> {} relevant products", byId.size(), relevant.size());
            return relevant;
        } catch (Exception error) {
            log.warn("PDD search failed: {}", error.toString());
            throw new IllegalStateException("PDD search failed", error);
        }
    }

    private void waitForQueries(List<CompletableFuture<QueryResult>> futures) {
        try {
            CompletableFuture
                    .allOf(futures.toArray(new CompletableFuture[0]))
                    .get(platformTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            long completed = futures.stream().filter(CompletableFuture::isDone).count();
            log.warn("PDD query batch timed out; using {} completed of {} queries", completed, futures.size());
        }
    }

    private Duration platformTimeout() {
        long timeoutMs = properties.getPlatforms().getPlatform(platform()).getTimeoutMs();
        return Duration.ofMillis(Math.max(1_000, timeoutMs));
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

    private List<ProductCard> relevantProducts(Map<String, ProductCard> byId, int pageSize) {
        return byId.values().stream()
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

        // Parallel detail enrichment for the first N products
        int detailLimit = 20;
        List<CompletableFuture<ProductCard>> futures = new ArrayList<>();
        for (JsonNode item : list) {
            String goodsSign = firstText(item, "goods_sign");
            ProductCard base = toProduct(item, goodsSign);
            if (futures.size() < detailLimit && StringUtils.isNotBlank(goodsSign)) {
                futures.add(CompletableFuture.supplyAsync(() -> enrichDetail(base, goodsSign, searchId), detailExecutor));
            } else {
                futures.add(CompletableFuture.completedFuture(base));
            }
        }
        return futures.stream()
                .map(f -> { try { return f.get(8, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { return f.getNow(null); } })
                .filter(java.util.Objects::nonNull)
                .toList();
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
            Double itemRating = detail.itemRating() != null ? detail.itemRating() : base.itemRating();
            Double shopReputationScore = detail.shopReputationScore() != null
                    ? detail.shopReputationScore() : base.shopReputationScore();
            String shopReputationLevel = StringUtils.defaultIfBlank(detail.shopReputationLevel(), base.shopReputationLevel());
            String reputationEvidence = detail.reputationEvidence() != null
                    && !"none".equals(detail.reputationEvidence()) ? detail.reputationEvidence() : base.reputationEvidence();
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
            ).withReputationSignals(itemRating, shopReputationScore, shopReputationLevel,
                    base.sellerReputationScore(), reputationEvidence);
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
        ).withReputationSignals(base.itemRating(), base.shopReputationScore(), base.shopReputationLevel(),
                base.sellerReputationScore(), base.reputationEvidence());
    }

    private String parseGoodsSign(String productId, String detailUrl) {
        String fromProductId = extractQueryParam(productId, "goods_sign");
        if (StringUtils.isNotBlank(fromProductId)) {
            return fromProductId;
        }
        return extractQueryParam(detailUrl, "goods_sign");
    }

    private String parseGoodsId(String productId, String detailUrl) {
        String fromProductId = extractQueryParam(productId, "goods_id");
        if (StringUtils.isNotBlank(fromProductId)) {
            return fromProductId;
        }
        String normalized = StringUtils.defaultString(productId).trim();
        if (normalized.startsWith("pdd_")) {
            normalized = normalized.substring("pdd_".length());
        }
        if (normalized.matches("\\d+")) {
            return normalized;
        }
        return extractQueryParam(detailUrl, "goods_id");
    }

    private String extractQueryParam(String value, String name) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:^|[?&])" + java.util.regex.Pattern.quote(name) + "=([^&#]+)")
                .matcher(value);
        if (!matcher.find()) {
            return "";
        }
        return java.net.URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private PddDetail loadDetail(String goodsSign, String searchId) throws Exception {
        CachedDetail cached = detailCache.getIfPresent(goodsSign);
        if (cached != null) {
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
            detailCache.put(goodsSign, new CachedDetail(detail));
        }
        return detail;
    }

    private PddDetail loadDetailByGoodsId(String goodsId) throws Exception {
        String cacheKey = "goods_id:" + goodsId;
        CachedDetail cached = detailCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.detail();
        }
        Map<String, Object> params = baseParams("pdd.ddk.goods.detail");
        params.put("goods_id_list", objectMapper.writeValueAsString(List.of(goodsId)));
        params.put("pid", properties.getPdd().getPid());
        params.put("goods_img_type", 1);
        params.put("need_sku_info", false);
        params.put("sign", sign(params));
        PddDetail detail = mapDetailResponse(call(params));
        if (detail != null) {
            detailCache.put(cacheKey, new CachedDetail(detail));
        }
        return detail;
    }

    private ProductCard detailToProductCard(PddDetail detail, String goodsSign, String goodsId, String detailUrl) {
        String resolvedGoodsId = StringUtils.defaultIfBlank(detail.goodsId(), goodsId);
        String resolvedGoodsSign = StringUtils.defaultIfBlank(detail.goodsSign(), goodsSign);
        String title = StringUtils.defaultIfBlank(detail.title(), "PDD Item");
        String url = SearchTextUtils.normalizeUrl(detailUrl);
        if (StringUtils.isBlank(url)) {
            url = fallbackDetailUrl(resolvedGoodsId, title);
        }
        return new ProductCard(
                stableProductId(resolvedGoodsSign, resolvedGoodsId),
                title,
                StringUtils.defaultIfBlank(detail.imageUrl(), ""),
                detail.price(),
                detail.originalPrice().compareTo(BigDecimal.ZERO) > 0 ? detail.originalPrice() : null,
                "PDD",
                false,
                StringUtils.defaultIfBlank(detail.shopName(), "PDD Shop"),
                detail.rating(),
                detail.sales(),
                0.0,
                detail.tags(),
                url,
                detail.brand(),
                detail.rating() > 0 ? detail.ratingSource() : "none",
                detail.salesLabel()
        ).withReputationSignals(detail.itemRating(), detail.shopReputationScore(), detail.shopReputationLevel(),
                null, detail.reputationEvidence());
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
        CachedUrl cached = promotionUrlCache.getIfPresent(cacheKey);
        if (cached != null) {
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
            promotionUrlCache.put(cacheKey, new CachedUrl(url));
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
                0.0,
                tags(item),
                fallbackDetailUrl(goodsId, title),
                brand,
                rating.source(),
                pddSalesLabel(firstText(item, "sales_tip"), sales)
        ).withReputationSignals(rating.itemRating(), rating.shopReputationScore(),
                rating.shopReputationLevel(), null, rating.evidence());
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
                firstText(item, "goods_id"),
                firstText(item, "goods_sign"),
                title,
                SearchTextUtils.normalizeUrl(firstText(item, "goods_image_url", "goods_thumbnail_url")),
                price,
                originalPrice,
                shopName,
                brand,
                rating.value(),
                rating.source(),
                rating.itemRating(),
                rating.shopReputationScore(),
                rating.shopReputationLevel(),
                rating.evidence(),
                sales,
                pddSalesLabel(firstText(item, "sales_tip"), sales),
                tags(item)
        );
    }

    private RatingInfo ratingInfo(JsonNode item) {
        double itemRating = parseRating(item.path("goods_eval_score"), item.path("goods_rate"));
        String shopLevel = parseDsrLevel(
                item.path("desc_txt"),
                item.path("serv_txt"),
                item.path("lgst_txt"));
        double textDsr = pddLevelRating(shopLevel);
        double shopDsr = parseRating(
                item.path("avg_desc"),
                item.path("avg_serv"),
                item.path("avg_lgst"));
        if (textDsr > 0) {
            shopDsr = textDsr;
        }
        if (shopLevel == null && shopDsr > 0) {
            shopLevel = inferPddLevel(shopDsr);
        }
        double legacyRating = itemRating > 0 ? itemRating : shopDsr;
        String legacySource = itemRating > 0 ? "item_rating" : (shopDsr > 0 ? "shop_dsr" : "none");
        String evidence = shopDsr > 0
                ? (textDsr > 0 ? "pdd_shop_level" : "shop_dsr")
                : (itemRating > 0 ? "item_rating" : "none");
        Double itemSignal = itemRating > 0 ? itemRating : null;
        Double shopSignal = shopDsr > 0 ? pddLevelScore(shopLevel, shopDsr) : null;
        return new RatingInfo(legacyRating, legacySource, itemSignal, shopSignal, shopLevel, evidence);
    }

    /**
     * 拼多多 DSR 文本转兼容评分：高→4.6, 中→4.0, 低→3.2
     * 注意：这是店铺口碑维度的映射，不是商品评分。
     * 排序时 ProductReputationService 会根据 ratingSource="shop_dsr" 使用低权重。
     * 取多个维度的平均值
     */
    private String parseDsrLevel(JsonNode... nodes) {
        int high = 0;
        int mid = 0;
        int low = 0;
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && node.isTextual()) {
                String text = node.asText("").trim();
                if ("\u9ad8".equals(text)) {
                    high++;
                } else if ("\u4e2d".equals(text)) {
                    mid++;
                } else if ("\u4f4e".equals(text)) {
                    low++;
                }
            }
        }
        if (high == 0 && mid == 0 && low == 0) {
            return null;
        }
        if (high >= mid && high >= low) {
            return "high";
        }
        if (mid >= low) {
            return "mid";
        }
        return "low";
    }

    private double pddLevelRating(String level) {
        return switch (StringUtils.defaultString(level)) {
            case "high" -> 4.6;
            case "mid" -> 4.0;
            case "low" -> 3.2;
            default -> 0.0;
        };
    }

    private String inferPddLevel(double rating) {
        if (rating >= 4.4) {
            return "high";
        }
        if (rating >= 3.8) {
            return "mid";
        }
        return "low";
    }

    private Double pddLevelScore(String level, double rating) {
        return switch (StringUtils.defaultString(level)) {
            case "high" -> 0.85;
            case "mid" -> 0.60;
            case "low" -> 0.30;
            default -> {
                if (rating >= 4.4) yield 0.85;
                if (rating >= 3.8) yield 0.60;
                yield rating > 0 ? 0.30 : null;
            }
        };
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
        detailCache.cleanUp();
        promotionUrlCache.cleanUp();
    }

    private record CachedDetail(PddDetail detail) {}

    private record CachedUrl(String url) {}

    private record QueryResult(String query, List<ProductCard> products, boolean success) {
        static QueryResult failed(String query) {
            return new QueryResult(query, List.of(), false);
        }
    }

    private record RatingInfo(
            double value,
            String source,
            Double itemRating,
            Double shopReputationScore,
            String shopReputationLevel,
            String evidence
    ) {}

    private record PddDetail(
            String goodsId,
            String goodsSign,
            String title,
            String imageUrl,
            BigDecimal price,
            BigDecimal originalPrice,
            String shopName,
            String brand,
            double rating,
            String ratingSource,
            Double itemRating,
            Double shopReputationScore,
            String shopReputationLevel,
            String reputationEvidence,
            long sales,
            String salesLabel,
            List<String> tags
    ) {}
}
