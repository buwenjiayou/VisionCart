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
import org.springframework.scheduling.annotation.Scheduled;
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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class TaobaoSearchService implements PlatformSearchService {
    private static final Logger log = LoggerFactory.getLogger(TaobaoSearchService.class);
    private static final int DETAIL_ENRICH_LIMIT = 20;

    private final VisionCartProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final java.util.concurrent.ExecutorService searchExecutor;
    private final java.util.concurrent.ExecutorService detailExecutor;
    private final Cache<String, CachedDetail> detailCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();
    private volatile long apiClockOffsetMs = 0;

    public TaobaoSearchService(VisionCartProperties properties, ObjectMapper objectMapper,
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
        return "淘宝";
    }

    /**
     * P0-3: 通过 itemId 精确查询单个淘宝商品（用于价格刷新）。
     * 优先从 productId 解析（格式: taobao_{itemId}），其次从 detailUrl 解析 id= 参数。
     */
    @Override
    public Optional<ProductCard> fetchByProductId(String productId, String detailUrl) {
        VisionCartProperties.Taobao tb = properties.getTaobao();
        if (StringUtils.isAnyBlank(tb.getAppKey(), tb.getAppSecret())) {
            return Optional.empty();
        }

        String itemId = parseItemIdFromProductId(productId);
        if (itemId.isBlank()) {
            itemId = parseItemIdFromUrl(detailUrl);
        }
        if (itemId.isBlank()) {
            return Optional.empty();
        }

        try {
            Map<String, TaobaoDetail> details = loadDetails(List.of(itemId), tb);
            TaobaoDetail detail = details.get(itemId);
            if (detail == null) {
                return Optional.empty();
            }
            ProductCard card = detailToProductCard(detail);
            log.info("fetchByProductId: Taobao itemId={} -> title='{}', price={}", itemId, card.title(), card.price());
            return Optional.of(card);
        } catch (Exception e) {
            log.warn("fetchByProductId failed for Taobao itemId={}: {}", itemId, e.getMessage());
            return Optional.empty();
        }
    }

    private String parseItemIdFromProductId(String productId) {
        if (StringUtils.isBlank(productId)) return "";
        // Format: "taobao_123456" or just "123456"
        if (productId.startsWith("taobao_")) {
            return productId.substring("taobao_".length());
        }
        // If it looks like a numeric ID
        if (productId.matches("\\d+")) {
            return productId;
        }
        return "";
    }

    private String parseItemIdFromUrl(String url) {
        if (StringUtils.isBlank(url)) return "";
        // Match id=123456 in URL
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(?:id|item_id|num_iid)=([A-Za-z0-9_-]+)").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    private ProductCard detailToProductCard(TaobaoDetail detail) {
        List<String> tags = new ArrayList<>(detail.tags());
        String salesSource = StringUtils.defaultIfBlank(detail.salesSource(), "monthly");
        return new ProductCard(
                "taobao_" + detail.itemId(),
                StringUtils.defaultIfBlank(detail.title(), "淘宝商品"),
                StringUtils.defaultIfBlank(detail.imageUrl(), ""),
                detail.price(),
                detail.originalPrice().compareTo(BigDecimal.ZERO) > 0 ? detail.originalPrice() : null,
                "淘宝",
                detail.selfOperated(),
                StringUtils.defaultIfBlank(detail.shopName(), ""),
                detail.rating(),
                detail.sales(),
                0.0,
                tags,
                StringUtils.defaultIfBlank(detail.detailUrl(), ""),
                detail.brand(),
                detail.rating() > 0 ? detail.ratingSource() : "none",
                SearchTextUtils.salesLabel(detail.sales(), salesSource)
        );
    }

    @Override
    public List<ProductCard> search(Map<String, String> attributes, SearchFilter filter, int page, int pageSize) {
        VisionCartProperties.Taobao tb = properties.getTaobao();
        if (StringUtils.isAnyBlank(tb.getAppKey(), tb.getAppSecret())) {
            log.warn("Taobao search skipped: TAOBAO_APP_KEY or TAOBAO_APP_SECRET is not configured");
            return List.of();
        }

        try {
            return doSearch(attributes, filter, page, pageSize, tb);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Invalid timestamp")) {
                syncClockOffset(tb.getApiUrl());
                try {
                    return doSearch(attributes, filter, page, pageSize, tb);
                } catch (Exception retryError) {
                    log.warn("Taobao search retry failed: {}", retryError.toString());
                    throw new IllegalStateException("Taobao search retry failed", retryError);
                }
            }
            log.warn("Taobao search failed: {}", e.toString());
            throw new IllegalStateException("Taobao search failed", e);
        }
    }

    private List<ProductCard> doSearch(Map<String, String> attributes, SearchFilter filter, int page, int pageSize,
                                       VisionCartProperties.Taobao tb) throws Exception {
        int fetchSize = SearchQueryBuilder.platformFetchSize(pageSize);
        List<String> queries = SearchQueryBuilder.taobaoQueries(attributes, filter, "商品");
        log.info("Taobao search: {} queries", queries.size());

        // Run all queries in parallel
        List<java.util.concurrent.CompletableFuture<QueryResult>> futures = queries.stream()
                .map(query -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        Map<String, String> params = buildParams(query, filter, page, fetchSize, tb);
                        List<ProductCard> results = mapResponse(executeSearch(tb, params));
                        log.info("Taobao query '{}' returned {} products", query, results.size());
                        return new QueryResult(query, results, true);
                    } catch (Exception e) {
                        log.warn("Taobao query '{}' failed: {}", query, e.toString());
                        return new QueryResult(query, List.of(), false);
                    }
                }, searchExecutor))
                .toList();

        // Collect results as they complete
        waitForQueries(futures, "Taobao");
        Map<String, ProductCard> byId = new LinkedHashMap<>();
        int completedQueries = 0;
        int successfulQueries = 0;
        for (java.util.concurrent.CompletableFuture<QueryResult> future : futures) {
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
            throw new IllegalStateException("Taobao query batch successful 0 of " + queries.size()
                    + " queries (completed=" + completedQueries + ")");
        }
        if (successfulQueries < queries.size()) {
            log.warn("Taobao query batch partial: successful {} of {} queries (completed={})",
                    successfulQueries, queries.size(), completedQueries);
        }

        return enrichWithDetails(new ArrayList<>(byId.values()).stream()
                .limit(fetchSize).toList(), tb);
    }

    private void waitForQueries(List<java.util.concurrent.CompletableFuture<QueryResult>> futures, String platform) {
        try {
            java.util.concurrent.CompletableFuture
                    .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(platformTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            long completed = futures.stream().filter(java.util.concurrent.CompletableFuture::isDone).count();
            log.warn("{} query batch timed out; using {} completed of {} queries", platform, completed, futures.size());
        }
    }

    private Duration platformTimeout() {
        long timeoutMs = properties.getPlatforms().getPlatform(platform()).getTimeoutMs();
        return Duration.ofMillis(Math.max(1_000, timeoutMs));
    }

    private Map<String, String> buildParams(String query,
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

        params.put("q", query);
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
                case "price" -> "asc".equalsIgnoreCase(filter.sortOrder()) || "price_asc".equalsIgnoreCase(filter.sortOrder())
                        ? "price_asc" : "price_des";
                case "sales" -> "total_sales_des";
                // 淘宝API无真正的评分排序，tk_rate_des是佣金比率排序（非客户评分）
                // 评分/口碑排序依赖后端本地 applySort 按 shop_dsr/seller 信号排序，不能下发销量排序。
                case "rating", "reviews", "review_quality", "rating_desc", "shop_trust", "seller_trust" -> null;
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
        String body = buildQueryString(params);
        log.info("Taobao search: method={}, q={}", params.get("method"), params.get("q"));
        String response = restClient.post()
                .uri(tb.getApiUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(String.class);
        log.info("Taobao response (first 500 chars): {}", response != null ? response.substring(0, Math.min(500, response.length())) : "null");
        return response;
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
            String brand = StringUtils.defaultIfBlank(
                    firstText(basicInfo, "brand_name", "brand"),
                    SearchTextUtils.inferBrand(title, shopName));
            JsonNode publishInfo = item.path("publish_info");
            SalesInfo salesInfo = salesInfo(item, basicInfo, publishInfo);
            boolean hasCoupon = item.path("coupon_amount").asLong(0) > 0
                    || priceInfo.path("final_promotion_path_list").path("final_promotion_path_map_data").isArray();
            String userType = firstLong(item, "user_type") == 1 || firstLong(basicInfo, "user_type") == 1 ? "天猫" : "淘宝";
            boolean selfOperated = "天猫".equals(userType);

            RatingInfo ratingInfo = ratingInfo(item, basicInfo);
            double rating = ratingInfo.rating();
            if (log.isDebugEnabled() && rating > 0) {
                log.debug("Taobao search item {} rating_source={} rating={}",
                        numIid, ratingInfo.source(), rating);
            }

            List<String> tags = new ArrayList<>();
            tags.add(userType);
            if (item.path("free_shipment").asBoolean()) tags.add("包邮");
            if (hasCoupon) tags.add("有券");
            addPromotionTags(tags, priceInfo);

            String detailUrl = SearchTextUtils.normalizeUrl(firstText(
                    publishInfo,
                    "coupon_share_url",
                    "click_url"));
            if (StringUtils.isBlank(detailUrl)) {
                detailUrl = SearchTextUtils.normalizeUrl(firstText(item, "item_url", "url"));
            }
            if (StringUtils.isBlank(detailUrl) && numIid.matches("\\d+")) {
                detailUrl = "https://item.taobao.com/item.htm?id=" + numIid;
            }
            if (StringUtils.isBlank(detailUrl)) {
                detailUrl = "https://s.taobao.com/search?q=" + java.net.URLEncoder.encode(title, StandardCharsets.UTF_8);
            }

            // Generate stable product ID: use numIid if available, otherwise hash title+image+shop
            String productId;
            if (StringUtils.isNotBlank(numIid)) {
                productId = "tb_" + numIid;
            } else {
                String seed = StringUtils.defaultString(title) + "|" + StringUtils.defaultString(pictUrl) + "|" + StringUtils.defaultString(shopName);
                productId = "tb_" + Integer.toHexString(seed.hashCode());
            }

            products.add(new ProductCard(
                    productId,
                    title,
                    pictUrl,
                    zkPrice,
                    origPrice.compareTo(zkPrice) > 0 ? origPrice : null,
                    "淘宝",
                    selfOperated,
                    shopName,
                    rating,
                    salesInfo.sales(),
                    0.0,
                    tags,
                    detailUrl,
                    brand,
                    ratingInfo.source(),
                    SearchTextUtils.salesLabel(salesInfo.sales(), salesInfo.source())
            ).withReputationSignals(ratingInfo.itemRating(), ratingInfo.shopReputationScore(),
                    null, null, ratingInfo.evidence()));
        }

        return products;
    }

    private List<ProductCard> enrichWithDetails(List<ProductCard> products, VisionCartProperties.Taobao tb) {
        if (products.isEmpty()) {
            return products;
        }
        try {
            Map<String, TaobaoDetail> details = loadDetails(
                    products.stream()
                            .map(product -> product.id().replaceFirst("^tb_", ""))
                            .filter(StringUtils::isNotBlank)
                            .limit(DETAIL_ENRICH_LIMIT)
                            .toList(),
                    tb);
            if (details.isEmpty()) {
                return products;
            }
            return products.stream()
                    .map(product -> applyDetail(product, details.get(product.id().replaceFirst("^tb_", ""))))
                    .toList();
        } catch (Exception e) {
            log.warn("Taobao detail enrichment skipped: {}", e.toString());
            return products;
        }
    }

    private Map<String, TaobaoDetail> loadDetails(List<String> itemIds, VisionCartProperties.Taobao tb) throws Exception {
        Map<String, TaobaoDetail> details = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String itemId : itemIds) {
            CachedDetail cached = detailCache.getIfPresent(itemId);
            if (cached != null) {
                details.put(itemId, cached.detail());
            } else {
                missing.add(itemId);
            }
        }
        if (!missing.isEmpty()) {
            Map<String, TaobaoDetail> loaded = fetchDetails(missing, tb);
            loaded.forEach((id, detail) -> detailCache.put(id, new CachedDetail(detail)));
            details.putAll(loaded);
        }
        return details;
    }

    private Map<String, TaobaoDetail> fetchDetails(List<String> itemIds, VisionCartProperties.Taobao tb) throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("method", "taobao.tbk.item.info.upgrade.get");
        params.put("app_key", tb.getAppKey());
        params.put("timestamp", taobaoTimestamp());
        params.put("v", "2.0");
        params.put("sign_method", "md5");
        params.put("format", "json");
        params.put("item_id", String.join(",", itemIds));
        params.put("platform", "2");
        params.put("sign", sign(params, tb.getAppSecret()));

        String body = restClient.post()
                .uri(tb.getApiUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(buildQueryString(params))
                .retrieve()
                .body(String.class);
        return mapDetailResponse(body);
    }

    private Map<String, TaobaoDetail> mapDetailResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode errorResponse = root.path("error_response");
        if (!errorResponse.isMissingNode() && !errorResponse.isEmpty()) {
            throw new IllegalStateException(errorResponse.toString());
        }
        JsonNode details = root.path("tbk_item_info_upgrade_get_response").path("results").path("tbk_item_detail");
        if (!details.isArray()) {
            details = root.findPath("tbk_item_detail");
        }
        Map<String, TaobaoDetail> mapped = new LinkedHashMap<>();
        if (!details.isArray()) {
            return mapped;
        }
        for (JsonNode detail : details) {
            TaobaoDetail item = toDetail(detail);
            if (StringUtils.isNotBlank(item.itemId())) {
                mapped.put(item.itemId(), item);
            }
            if (StringUtils.isNotBlank(item.inputItemId())) {
                mapped.put(item.inputItemId(), item);
            }
        }
        return mapped;
    }

    private TaobaoDetail toDetail(JsonNode detail) {
        JsonNode basicInfo = detail.path("item_basic_info");
        JsonNode priceInfo = detail.path("price_promotion_info");
        JsonNode publishInfo = detail.path("publish_info");
        String itemId = firstNonBlank(
                firstText(detail, "item_id"),
                firstText(basicInfo, "item_id", "num_iid"));
        String inputItemId = firstText(detail, "input_item_iid");
        String title = firstText(basicInfo, "title", "short_title", "sub_title");
        String imageUrl = firstText(basicInfo, "pict_url", "white_image", "small_images");
        BigDecimal price = firstPrice(
                firstText(priceInfo, "final_promotion_price", "promotion_price", "zk_final_price"),
                firstText(priceInfo, "zk_final_price"),
                firstText(basicInfo, "zk_final_price", "price"));
        BigDecimal originalPrice = firstPrice(
                firstText(priceInfo, "reserve_price", "origin_price"),
                firstText(basicInfo, "reserve_price", "price"));
        String shopName = firstText(basicInfo, "shop_title", "shop_name", "seller_nick");
        String brand = StringUtils.defaultIfBlank(firstText(basicInfo, "brand_name", "brand"),
                SearchTextUtils.inferBrand(title, shopName));
        RatingInfo ratingInfo = ratingInfo(detail, basicInfo);
        double rating = ratingInfo.rating();
        SalesInfo sales = salesInfo(detail, basicInfo, publishInfo);
        String detailUrl = SearchTextUtils.normalizeUrl(firstText(publishInfo, "coupon_share_url", "click_url"));
        if (StringUtils.isBlank(detailUrl)) {
            detailUrl = SearchTextUtils.normalizeUrl(firstText(basicInfo, "item_url", "tmall_desc_url", "taobao_desc_url"));
        }
        List<String> tags = new ArrayList<>();
        String userType = firstLong(basicInfo, "user_type") == 1 ? "天猫" : "淘宝";
        tags.add(userType);
        if (basicInfo.path("free_shipment").asBoolean(false)) tags.add("包邮");
        addPromotionTags(tags, priceInfo);
        return new TaobaoDetail(itemId, inputItemId, title, imageUrl, price, originalPrice, shopName, brand,
                rating, ratingInfo.source(), ratingInfo.itemRating(), ratingInfo.shopReputationScore(),
                ratingInfo.evidence(), sales.sales(), sales.source(), tags, detailUrl, "天猫".equals(userType));
    }

    private ProductCard applyDetail(ProductCard base, TaobaoDetail detail) {
        if (detail == null) {
            return base;
        }
        List<String> tags = new ArrayList<>(base.tags());
        detail.tags().forEach(tag -> {
            if (!tags.contains(tag)) {
                tags.add(tag);
            }
        });
        long sales = Math.max(base.sales(), detail.sales());
        String salesSource = detail.sales() >= base.sales() ? detail.salesSource() : salesSourceFromLabel(base.salesLabel());
        double rating = detail.rating() > 0 ? detail.rating() : base.rating();
        String ratingSource = detail.rating() > 0 ? detail.ratingSource() : base.ratingSource();
        Double itemRating = detail.itemRating() != null ? detail.itemRating() : base.itemRating();
        Double shopReputationScore = detail.shopReputationScore() != null
                ? detail.shopReputationScore() : base.shopReputationScore();
        String reputationEvidence = detail.reputationEvidence() != null
                && !"none".equals(detail.reputationEvidence()) ? detail.reputationEvidence() : base.reputationEvidence();
        return new ProductCard(
                base.id(),
                StringUtils.defaultIfBlank(detail.title(), base.title()),
                StringUtils.defaultIfBlank(detail.imageUrl(), base.imageUrl()),
                detail.price().compareTo(BigDecimal.ZERO) > 0 ? detail.price() : base.price(),
                detail.originalPrice().compareTo(BigDecimal.ZERO) > 0 ? detail.originalPrice() : base.originalPrice(),
                base.platform(),
                detail.selfOperated() || base.selfOperated(),
                StringUtils.defaultIfBlank(detail.shopName(), base.shopName()),
                rating,
                sales,
                base.similarity(),
                tags,
                StringUtils.defaultIfBlank(detail.detailUrl(), base.detailUrl()),
                StringUtils.defaultIfBlank(detail.brand(), base.brand()),
                rating > 0 ? ratingSource : "none",
                SearchTextUtils.salesLabel(sales, salesSource)
        ).withReputationSignals(itemRating, shopReputationScore, base.shopReputationLevel(),
                base.sellerReputationScore(), reputationEvidence);
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private SalesInfo salesInfo(JsonNode item, JsonNode basicInfo, JsonNode publishInfo) {
        long monthly = SearchTextUtils.maxHumanCount(
                firstText(item, "volume"),
                firstText(item, "sell_num"),
                firstText(item, "total_sales"),
                firstText(item, "sales"),
                firstText(item, "sales_tip"),
                firstText(basicInfo, "volume"),
                firstText(basicInfo, "sell_num"),
                firstText(basicInfo, "total_sales"),
                firstText(basicInfo, "monthly_sales"));
        long annual = SearchTextUtils.maxHumanCount(firstText(basicInfo, "annual_vol"));
        long promotion = SearchTextUtils.maxHumanCount(
                firstText(basicInfo, "tk_total_sales"),
                firstText(publishInfo, "daily_promotion_sales"),
                firstText(publishInfo, "two_hour_promotion_sales"));
        if (annual >= monthly && annual >= promotion) {
            return new SalesInfo(annual, "annual");
        }
        if (promotion >= monthly) {
            return new SalesInfo(promotion, "promotion");
        }
        return new SalesInfo(monthly, "monthly");
    }

    private void addPromotionTags(List<String> tags, JsonNode priceInfo) {
        JsonNode tagList = priceInfo.path("promotion_tag_list");
        if (tagList.isArray()) {
            tagList.forEach(tag -> {
                String name = firstText(tag, "tag_name", "name");
                if (StringUtils.isNotBlank(name) && !tags.contains(name)) {
                    tags.add(name);
                }
            });
        }
        JsonNode pathList = priceInfo.path("final_promotion_path_list").path("final_promotion_path_map_data");
        if (pathList.isArray()) {
            pathList.forEach(path -> {
                String title = firstText(path, "promotion_title", "promotion_desc");
                if (StringUtils.isNotBlank(title) && !tags.contains(title)) {
                    tags.add(title);
                }
            });
        }
    }

    private String salesSourceFromLabel(String label) {
        if (label != null && label.startsWith("年销")) {
            return "annual";
        }
        if (label != null && label.startsWith("推广")) {
            return "promotion";
        }
        return "monthly";
    }

    private RatingInfo ratingInfo(JsonNode item, JsonNode basicInfo) {
        // seller_info node may contain shop_dsr and other rating fields
        JsonNode sellerInfo = item.path("seller_info");

        String rawItemScore = firstText(basicInfo, "item_score");
        if (StringUtils.isBlank(rawItemScore)) {
            rawItemScore = firstText(item, "item_score");
        }
        if (StringUtils.isBlank(rawItemScore)) {
            rawItemScore = firstText(sellerInfo, "item_score");
        }
        double itemRating = parseRating(rawItemScore);

        // shop_dsr: check basicInfo → item root → seller_info
        String rawShopDsr = firstText(basicInfo, "shop_dsr");
        if (StringUtils.isBlank(rawShopDsr)) {
            rawShopDsr = firstText(item, "shop_dsr");
        }
        if (StringUtils.isBlank(rawShopDsr)) {
            rawShopDsr = firstText(sellerInfo, "shop_dsr");
        }
        double shopDsr = parseRating(rawShopDsr);

        // shop_score / seller_rate as final fallback
        if (shopDsr <= 0) {
            String rawShopScore = firstText(sellerInfo, "shop_score", "seller_rate", "score");
            if (StringUtils.isBlank(rawShopScore)) {
                rawShopScore = firstText(basicInfo, "shop_score");
            }
            shopDsr = parseRating(rawShopScore);
        }

        double legacyRating = itemRating > 0 ? itemRating : shopDsr;
        String legacySource = itemRating > 0 ? "item_rating" : (shopDsr > 0 ? "shop_dsr" : "none");
        String evidence = shopDsr > 0 ? "shop_dsr" : (itemRating > 0 ? "item_rating" : "none");
        Double itemSignal = itemRating > 0 ? itemRating : null;
        Double shopSignal = shopDsr > 0 ? Math.min(1.0, shopDsr / 5.0) : null;
        return new RatingInfo(legacyRating, legacySource, itemSignal, shopSignal, evidence);
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

    @Scheduled(fixedDelay = 300_000)
    void evictExpiredCaches() {
        detailCache.cleanUp();
    }

    private record SalesInfo(long sales, String source) {}

    private record QueryResult(String query, List<ProductCard> products, boolean success) {
        static QueryResult failed(String query) {
            return new QueryResult(query, List.of(), false);
        }
    }

    private record RatingInfo(
            double rating,
            String source,
            Double itemRating,
            Double shopReputationScore,
            String evidence
    ) {}

    private record CachedDetail(TaobaoDetail detail) {}

    private record TaobaoDetail(
            String itemId,
            String inputItemId,
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
            String reputationEvidence,
            long sales,
            String salesSource,
            List<String> tags,
            String detailUrl,
            boolean selfOperated
    ) {}
}
