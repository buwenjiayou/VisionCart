package com.visioncart.service.price;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.api.dto.SearchRequest;
import com.visioncart.api.dto.SearchResult;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.service.search.PlatformSearchService;
import com.visioncart.service.search.SearchOrchestrator;
import com.visioncart.service.search.SearchTextUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ExactProductRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ExactProductRefreshService.class);
    private static final double TITLE_ACCEPT_THRESHOLD = 0.92;
    private static final Pattern URL_ID = Pattern.compile("(?i)(?:id|item_id|goods_id|product_id)=([A-Za-z0-9_-]+)");

    private final SearchOrchestrator searchOrchestrator;
    private final List<PlatformSearchService> platformServices;

    public ExactProductRefreshService(SearchOrchestrator searchOrchestrator,
                                      List<PlatformSearchService> platformServices) {
        this.searchOrchestrator = searchOrchestrator;
        this.platformServices = platformServices;
    }

    /**
     * P0-5: 先用平台精确查询（productId/detailUrl），失败才 fallback 到 title search。
     */
    public Optional<ProductCard> refresh(FavoriteProduct favorite) {
        if (favorite == null || StringUtils.isBlank(favorite.getTitle())) {
            return Optional.empty();
        }

        // 第一步：尝试平台精确查询
        String platform = StringUtils.defaultString(favorite.getPlatform());
        if (!platform.isBlank()) {
            Optional<PlatformSearchService> platformService = platformServices.stream()
                    .filter(p -> p.platform().equalsIgnoreCase(platform))
                    .findFirst();
            if (platformService.isPresent()) {
                try {
                    Optional<ProductCard> exact = platformService.get()
                            .fetchByProductId(favorite.getProductId(), favorite.getDetailUrl());
                    if (exact.isPresent()) {
                        log.info("Exact refresh via platform API: {} -> {}", favorite.getProductId(), exact.get().title());
                        return exact;
                    }
                } catch (Exception e) {
                    log.warn("Platform exact fetch failed for {}: {}", platform, e.getMessage());
                }
            }
        }

        // 第二步：fallback 到 title search
        return fallbackTitleSearch(favorite);
    }

    private Optional<ProductCard> fallbackTitleSearch(FavoriteProduct favorite) {
        SearchFilter filter = new SearchFilter(
                null,
                StringUtils.isBlank(favorite.getPlatform()) ? List.of() : List.of(favorite.getPlatform()),
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null
        );
        SearchRequest request = new SearchRequest(
                null,
                Map.of(SearchTextUtils.ATTR_KEYWORD, favorite.getTitle()),
                filter,
                1,
                10,
                30,
                "price_refresh"
        );
        SearchResult result = searchOrchestrator.search(request);
        List<ProductCard> products = result.products() == null ? List.of() : result.products();

        Optional<ProductCard> byProductId = products.stream()
                .filter(product -> samePlatform(favorite, product))
                .filter(product -> sameProductId(favorite.getProductId(), product.id()))
                .findFirst();
        if (byProductId.isPresent()) {
            return byProductId;
        }

        String detailItemId = itemId(favorite.getDetailUrl());
        if (!detailItemId.isBlank()) {
            Optional<ProductCard> byDetailUrl = products.stream()
                    .filter(product -> samePlatform(favorite, product))
                    .filter(product -> sameProductId(detailItemId, product.id())
                            || sameProductId(detailItemId, itemId(product.detailUrl())))
                    .findFirst();
            if (byDetailUrl.isPresent()) {
                return byDetailUrl;
            }
        }

        return products.stream()
                .filter(product -> samePlatform(favorite, product))
                .filter(product -> titleSimilarity(favorite.getTitle(), product.title()) >= TITLE_ACCEPT_THRESHOLD)
                .findFirst();
    }

    private boolean samePlatform(FavoriteProduct favorite, ProductCard product) {
        String expected = StringUtils.defaultString(favorite.getPlatform());
        if (expected.isBlank()) {
            return true;
        }
        return expected.equalsIgnoreCase(StringUtils.defaultString(product.platform()));
    }

    private boolean sameProductId(String expected, String candidate) {
        String a = SearchTextUtils.useful(expected);
        String b = SearchTextUtils.useful(candidate);
        return !a.isBlank() && !b.isBlank() && a.equalsIgnoreCase(b);
    }

    private String itemId(String url) {
        Matcher matcher = URL_ID.matcher(StringUtils.defaultString(url));
        return matcher.find() ? matcher.group(1) : "";
    }

    private double titleSimilarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isBlank() || b.isBlank()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        int distance = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 0 : 1.0 - (distance / (double) max);
    }

    private String normalize(String value) {
        return StringUtils.defaultString(value)
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
