package com.visioncart.service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.config.VisionCartProperties;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class EbaySearchService implements PlatformSearchService {
    private static final Logger log = LoggerFactory.getLogger(EbaySearchService.class);

    private final VisionCartProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    // Cache eBay app token (valid ~2 hours)
    private volatile String cachedToken;
    private volatile long tokenExpiresAt = 0;
    private final java.util.concurrent.locks.ReentrantLock tokenLock = new java.util.concurrent.locks.ReentrantLock();

    public EbaySearchService(VisionCartProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    @Override
    public String platform() {
        return "eBay";
    }

    @Override
    public boolean domesticOnly() {
        return false;
    }

    @Override
    public List<ProductCard> search(Map<String, String> attributes, SearchFilter filter, int page, int pageSize) {
        VisionCartProperties.Ebay ebay = properties.getEbay();
        if (StringUtils.isAnyBlank(ebay.getAppId(), ebay.getCertId())) {
            log.warn("eBay search skipped: EBAY_APP_ID or EBAY_CERT_ID is not configured");
            return List.of();
        }

        try {
            String token = getAppToken(ebay);
            if (token == null) {
                log.warn("eBay search skipped: token is empty");
                return List.of();
            }

            // Build search URL
            int fetchSize = SearchQueryBuilder.platformFetchSize(pageSize);
            StringBuilder url = new StringBuilder(ebay.getBrowseUrl());
            url.append("?q=").append(java.net.URLEncoder.encode(keyword(attributes, filter), StandardCharsets.UTF_8));
            url.append("&limit=").append(Math.min(100, fetchSize));
            url.append("&offset=").append((page - 1) * pageSize);

            // Price filter
            if (filter.priceRange() != null) {
                StringBuilder priceFilter = new StringBuilder("price:[");
                if (filter.priceRange().min() != null) {
                    priceFilter.append(filter.priceRange().min().longValue());
                } else {
                    priceFilter.append("0");
                }
                priceFilter.append("..");
                if (filter.priceRange().max() != null) {
                    priceFilter.append(filter.priceRange().max().longValue());
                } else {
                    priceFilter.append("100000");
                }
                priceFilter.append("]");
                url.append("&filter=").append(java.net.URLEncoder.encode(priceFilter.toString(), StandardCharsets.UTF_8));
            }

            // Sort
            if (filter.sortBy() != null) {
                String sort = switch (filter.sortBy()) {
                    case "price" -> "asc".equalsIgnoreCase(filter.sortOrder()) || "price_asc".equalsIgnoreCase(filter.sortOrder()) ? "price" : "-price";
                    default -> null;
                };
                if (sort != null) url.append("&sort=").append(sort);
            }

            // Condition: new items
            url.append("&filter=").append(java.net.URLEncoder.encode("conditions:{NEW}", StandardCharsets.UTF_8));

            String body = restClient.get()
                    .uri(url.toString())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("X-EBAY-C-MARKETPLACE-ID", ebay.getMarketplaceId())
                    .retrieve()
                    .body(String.class);

            return mapResponse(body);
        } catch (Exception e) {
            log.warn("eBay search failed: {}", e.toString());
            return List.of();
        }
    }

    private String getAppToken(VisionCartProperties.Ebay ebay) {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cachedToken;
        }

        tokenLock.lock();
        try {
            // Double-check after acquiring lock
            if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
                return cachedToken;
            }

            String credentials = Base64.getEncoder().encodeToString(
                    (ebay.getAppId() + ":" + ebay.getCertId()).getBytes(StandardCharsets.UTF_8));

            String body = restClient.post()
                    .uri(ebay.getTokenUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                    .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body("grant_type=client_credentials&scope=https://api.ebay.com/oauth/api_scope")
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(body);
            cachedToken = root.path("access_token").asText(null);
            if (StringUtils.isBlank(cachedToken)) {
                JsonNode errors = root.path("errors");
                throw new IllegalStateException(errors.isMissingNode() ? root.toString() : errors.toString());
            }
            int expiresIn = root.path("expires_in").asInt(7200);
            tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 300) * 1000L; // refresh 5 min early
            return cachedToken;
        } catch (Exception e) {
            log.warn("eBay token request failed: {}", e.toString());
            return null;
        } finally {
            tokenLock.unlock();
        }
    }

    private String keyword(Map<String, String> attributes, SearchFilter filter) {
        return SearchQueryBuilder.taobaoQueries(attributes, filter, "shopping item").stream()
                .findFirst()
                .orElse("shopping item");
    }

    private List<ProductCard> mapResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode items = root.path("itemSummaries");

        if (!items.isArray()) return List.of();

        List<ProductCard> products = new ArrayList<>();
        for (JsonNode item : items) {
            String itemId = item.path("itemId").asText("");
            String title = item.path("title").asText("eBay Item");
            String imageUrl = "";
            JsonNode imgNode = item.path("image");
            if (imgNode.isObject()) {
                imageUrl = imgNode.path("imageUrl").asText("");
            }
            if (imageUrl.isBlank()) {
                imageUrl = item.path("thumbnailUrl").asText("");
            }

            BigDecimal price = new BigDecimal(item.path("price").path("value").asText("0"));
            BigDecimal origPrice = null;
            JsonNode origPriceNode = item.path("originalPrice");
            if (origPriceNode.has("value")) {
                origPrice = new BigDecimal(origPriceNode.path("value").asText("0"));
            }

            String condition = item.path("condition").asText("");
            String shopName = item.path("seller").path("username").asText("eBay Seller");
            String brand = SearchTextUtils.inferBrand(title, shopName);
            double rating = 0.0;
            JsonNode sellerNode = item.path("seller");
            if (sellerNode.has("feedbackPercentage")) {
                rating = sellerNode.path("feedbackPercentage").asDouble(0) / 20.0;
            }
            long sales = item.path("itemCreationDate").isMissingNode() ? 0 : item.path("watchCount").asLong(0);
            String detailUrl = SearchTextUtils.normalizeUrl(item.path("itemWebUrl").asText(""));
            if (StringUtils.isBlank(detailUrl)) {
                detailUrl = "https://www.ebay.com/itm/" + itemId;
            }

            List<String> tags = new ArrayList<>();
            tags.add("eBay");
            if ("NEW".equals(condition)) tags.add("全新");

            products.add(new ProductCard(
                    "ebay_" + itemId,
                    title,
                    imageUrl,
                    price,
                    origPrice,
                    "eBay",
                    false,
                    shopName,
                    rating,
                    sales,
                    0.0,
                    tags,
                    detailUrl,
                    brand,
                    "none",
                    null
            ));
        }

        return products;
    }
}
