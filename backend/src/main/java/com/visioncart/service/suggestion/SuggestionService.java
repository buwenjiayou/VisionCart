package com.visioncart.service.suggestion;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SuggestionCard;
import com.visioncart.api.dto.SuggestionExecuteResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@Service
public class SuggestionService {

    public List<SuggestionCard> cards(String sessionId, String clientType, List<ProductCard> products) {
        int limit = "overlay".equalsIgnoreCase(clientType) ? 3 : 5;
        boolean hasOfficial = products.stream().anyMatch(ProductCard::selfOperated);
        boolean ratingSpread = products.stream().mapToDouble(ProductCard::rating).max().orElse(0)
                - products.stream().mapToDouble(ProductCard::rating).min().orElse(0) >= 0.3;

        List<SuggestionCard> cards = new java.util.ArrayList<>();
        cards.add(new SuggestionCard("price_compare", "同款低价", "跨平台比价", "money", "sort_by_price_asc", 10));
        if (hasOfficial) {
            cards.add(new SuggestionCard("official_only", "官方旗舰店", "正品保障", "shield", "filter_self_operated", 9));
        }
        cards.add(new SuggestionCard("price_history", "历史价格", "价格趋势与购买建议", "chart", "show_price_history", 8));
        cards.add(new SuggestionCard("similar_style", "相似风格", "放宽品牌找高性价比", "palette", "search_similar_style", 7));
        if (ratingSpread) {
            cards.add(new SuggestionCard("high_rating", "高分好评", "筛出 4.8 分以上商品", "star", "filter_rating_4_8", 7));
        }
        return cards.stream()
                .sorted(Comparator.comparingInt(SuggestionCard::priority).reversed())
                .limit(limit)
                .toList();
    }

    public SuggestionExecuteResult execute(String action, List<ProductCard> currentProducts) {
        List<ProductCard> products = currentProducts == null ? List.of() : currentProducts;
        String toast;
        List<ProductCard> updated = switch (action) {
            case "sort_by_price_asc" -> {
                List<ProductCard> sorted = products.stream().sorted(Comparator.comparing(ProductCard::price)).toList();
                BigDecimal min = sorted.stream().map(ProductCard::price).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                toast = "已按价格排序，最低 ¥" + min;
                yield sorted;
            }
            case "filter_self_operated" -> {
                List<ProductCard> filtered = products.stream().filter(ProductCard::selfOperated).toList();
                toast = filtered.isEmpty() ? "当前没有官方/自营结果，已保留全部商品" : "已筛选官方/自营商品";
                yield filtered.isEmpty() ? products : filtered;
            }
            case "filter_rating_4_8" -> {
                List<ProductCard> filtered = products.stream().filter(product -> product.rating() >= 4.8).toList();
                toast = "已筛选 4.8 分以上商品";
                yield filtered;
            }
            case "show_price_history" -> {
                toast = "近 30 天价格低点在 ¥189-¥219，当前适合比价后入手";
                yield products;
            }
            case "search_similar_style" -> {
                toast = "已放宽品牌限制，优先保留相似风格商品";
                yield products.stream().sorted(Comparator.comparing(ProductCard::similarity).reversed()).toList();
            }
            default -> {
                toast = "已应用智能建议";
                yield products;
            }
        };
        return new SuggestionExecuteResult(updated, cards(null, "app", updated), toast);
    }
}
