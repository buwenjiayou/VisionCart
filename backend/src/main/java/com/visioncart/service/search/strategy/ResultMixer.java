package com.visioncart.service.search.strategy;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.service.search.IntentGate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ResultMixer {

    private ResultMixer() {
    }

    public static List<ProductCard> mix(List<VerticalSearchStrategy.ClassifiedProduct> classified,
                                        MixPolicy policy,
                                        int pageSize) {
        if (classified == null || classified.isEmpty() || pageSize <= 0) {
            return List.of();
        }
        MixPolicy p = policy == null ? MixPolicy.defaults() : policy;
        Map<IntentGate.IntentTier, List<ProductCard>> byTier = classified.stream()
                .filter(item -> item.tier() != IntentGate.IntentTier.REJECT)
                .collect(Collectors.groupingBy(
                        VerticalSearchStrategy.ClassifiedProduct::tier,
                        LinkedHashMap::new,
                        Collectors.mapping(VerticalSearchStrategy.ClassifiedProduct::product, Collectors.toList())));

        List<ProductCard> result = new ArrayList<>(pageSize);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        addTier(result, seen, byTier, IntentGate.IntentTier.EXACT_MAIN, pageSize);
        addTier(result, seen, byTier, IntentGate.IntentTier.COMPATIBLE_MAIN, pageSize);
        addTier(result, seen, byTier, IntentGate.IntentTier.SAME_FAMILY, pageSize);
        addRelated(result, seen, byTier.getOrDefault(IntentGate.IntentTier.RELATED_ACCESSORY, List.of()), p, pageSize);
        addTier(result, seen, byTier, IntentGate.IntentTier.SUBSTITUTE, pageSize);
        return result;
    }

    private static void addTier(List<ProductCard> result,
                                LinkedHashSet<String> seen,
                                Map<IntentGate.IntentTier, List<ProductCard>> byTier,
                                IntentGate.IntentTier tier,
                                int pageSize) {
        for (ProductCard product : platformProtected(byTier.getOrDefault(tier, List.of()), pageSize)) {
            if (result.size() >= pageSize) {
                return;
            }
            if (seen.add(key(product))) {
                result.add(product);
            }
        }
    }

    private static void addRelated(List<ProductCard> result,
                                   LinkedHashSet<String> seen,
                                   List<ProductCard> related,
                                   MixPolicy policy,
                                   int pageSize) {
        int added = 0;
        for (ProductCard product : related) {
            if (result.size() >= pageSize || added >= policy.maxRelatedTotal()) {
                return;
            }
            boolean wouldBeTopWindow = result.size() < policy.topWindowSize();
            if (wouldBeTopWindow && added >= policy.maxRelatedInTopWindow()) {
                continue;
            }
            if (seen.add(key(product))) {
                result.add(product);
                added++;
            }
        }
    }

    private static List<ProductCard> platformProtected(List<ProductCard> products, int windowSize) {
        if (products.size() <= 1 || windowSize <= 0) {
            return products;
        }
        long platformCount = products.stream()
                .map(ResultMixer::platformKey)
                .distinct()
                .count();
        if (platformCount <= 1) {
            return products;
        }
        int maxPerPlatform = Math.max(1, (int) Math.ceil((double) windowSize / platformCount));
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<ProductCard> protectedWindow = new ArrayList<>();
        List<ProductCard> deferred = new ArrayList<>();
        for (ProductCard product : products) {
            String platform = platformKey(product);
            if (protectedWindow.size() >= windowSize) {
                deferred.add(product);
                continue;
            }
            if (counts.getOrDefault(platform, 0) >= maxPerPlatform) {
                deferred.add(product);
                continue;
            }
            protectedWindow.add(product);
            counts.merge(platform, 1, Integer::sum);
        }
        protectedWindow.addAll(roundRobinByPlatform(deferred));
        return protectedWindow;
    }

    private static List<ProductCard> roundRobinByPlatform(List<ProductCard> products) {
        Map<String, List<ProductCard>> buckets = new LinkedHashMap<>();
        for (ProductCard product : products) {
            buckets.computeIfAbsent(platformKey(product), ignored -> new ArrayList<>()).add(product);
        }
        List<ProductCard> result = new ArrayList<>();
        boolean added;
        do {
            added = false;
            for (List<ProductCard> bucket : buckets.values()) {
                if (!bucket.isEmpty()) {
                    result.add(bucket.remove(0));
                    added = true;
                }
            }
        } while (added);
        return result;
    }

    private static String platformKey(ProductCard product) {
        return product == null || product.platform() == null ? "" : product.platform();
    }

    private static String key(ProductCard product) {
        if (product == null) {
            return "";
        }
        String id = product.id() == null ? "" : product.id();
        if (!id.isBlank()) {
            return id;
        }
        return (product.platform() == null ? "" : product.platform()) + "|"
                + (product.title() == null ? "" : product.title());
    }
}
