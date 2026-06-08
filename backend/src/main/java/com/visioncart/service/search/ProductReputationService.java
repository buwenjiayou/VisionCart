package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.ReputationScore;
import com.visioncart.config.VisionCartProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Shop/seller reputation scoring service.
 * Product-level item ratings are display-only and do not contribute to shop trust sorting.
 */
@Service
public class ProductReputationService {

    private final VisionCartProperties.Search.Reputation config;

    public ProductReputationService() {
        this(new VisionCartProperties.Search.Reputation());
    }

    @Autowired
    public ProductReputationService(VisionCartProperties properties) {
        this(properties == null ? new VisionCartProperties.Search.Reputation()
                : properties.getSearch().getReputation());
    }

    ProductReputationService(VisionCartProperties.Search.Reputation config) {
        this.config = config == null ? new VisionCartProperties.Search.Reputation() : config;
    }

    public ReputationScore score(ProductCard product, List<ProductCard> pool) {
        return shopTrustScore(product, pool);
    }

    public ReputationScore score(ProductCard product) {
        return shopTrustScore(product, List.of());
    }

    public ReputationScore shopTrustScore(ProductCard product, List<ProductCard> pool) {
        if (product == null) {
            return ReputationScore.EMPTY;
        }
        TrustSignal signal = trustSignal(product);
        double simScore = clamp(product.similarity());
        double calibratedTrust = signal.calibratedTrust();
        double finalScore = calibratedTrust * clamp(config.getTrustWeight())
                + simScore * clamp(config.getRelevanceWeight());
        return new ReputationScore(finalScore, 0, signal.score(), signal.confidence(), signal.label());
    }

    public ReputationScore shopTrustScore(ProductCard product) {
        return shopTrustScore(product, List.of());
    }

    public boolean hasShopOrSellerTrust(ProductCard product) {
        return trustSignal(product).confidence() > 0;
    }

    public List<ProductCard> attachReputation(List<ProductCard> products) {
        return attachShopTrustReputation(products);
    }

    public List<ProductCard> attachShopTrustReputation(List<ProductCard> products) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        return products.stream()
                .map(p -> {
                    ReputationScore s = shopTrustScore(p, products);
                    int index = (int) Math.round(s.shopOrSellerScore() * s.confidence() * 100);
                    String label = s.confidence() > 0 ? s.displayLabel() : null;
                    return p.withReputation(index, s.score(), s.confidence(), label);
                })
                .toList();
    }

    private TrustSignal trustSignal(ProductCard product) {
        String evidence = normalizeEvidence(product.reputationEvidence(), product.ratingSource());

        if ("seller".equals(evidence)) {
            double score = sellerScore(product);
            if (score > 0) {
                return new TrustSignal(score, sellerEvidenceConfidence(product), sellerLabel(score));
            }
        }

        if ("pdd_shop_level".equals(evidence)) {
            double score = product.shopReputationScore() != null
                    ? clamp(product.shopReputationScore())
                    : pddLevelScore(product.shopReputationLevel());
            if (score <= 0) {
                score = inferPddLevelScore(product.rating());
            }
            if (score > 0) {
                String level = normalizePddLevel(product.shopReputationLevel(), product.rating(), score);
                return new TrustSignal(score, shopEvidenceConfidence(product), pddLevelLabel(level));
            }
        }

        if ("shop_dsr".equals(evidence)) {
            double score = shopScore(product);
            if (score > 0) {
                double confidence = shopEvidenceConfidence(product);
                String label = isPdd(product.platform())
                        ? pddLevelLabel(normalizePddLevel(product.shopReputationLevel(), product.rating(), score))
                        : shopScoreLabel(score);
                return new TrustSignal(score, confidence, label);
            }
        }

        return new TrustSignal(0, 0, null);
    }

    private String normalizeEvidence(String evidence, String ratingSource) {
        String value = evidence;
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value)) {
            value = ratingSource;
        }
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private double shopScore(ProductCard product) {
        if (product.shopReputationScore() != null && product.shopReputationScore() > 0) {
            return clamp(product.shopReputationScore());
        }
        if (isPdd(product.platform())) {
            double levelScore = pddLevelScore(product.shopReputationLevel());
            if (levelScore > 0) {
                return levelScore;
            }
            return inferPddLevelScore(product.rating());
        }
        return product.rating() > 0 ? clamp(product.rating() / 5.0) : 0;
    }

    private double sellerScore(ProductCard product) {
        if (product.sellerReputationScore() != null && product.sellerReputationScore() > 0) {
            return clamp(product.sellerReputationScore());
        }
        return product.rating() > 0 ? clamp(product.rating() / 5.0) : 0;
    }

    private double shopEvidenceConfidence(ProductCard product) {
        if (isPdd(product.platform())) {
            return clamp(config.getPddShopLevelConfidence());
        }
        if (isTmall(product)) {
            return clamp(config.getTmallShopDsrConfidence());
        }
        if (isTaobao(product.platform())) {
            return clamp(config.getTaobaoShopDsrConfidence());
        }
        return clamp(config.getUnknownShopDsrConfidence());
    }

    private double sellerEvidenceConfidence(ProductCard product) {
        if (isEbay(product.platform())) {
            return clamp(config.getEbaySellerConfidence());
        }
        return clamp(config.getUnknownSellerConfidence());
    }

    private double pddLevelScore(String level) {
        String normalized = normalizeLevelText(level);
        return switch (normalized) {
            case "high" -> 0.85;
            case "mid" -> 0.60;
            case "low" -> 0.30;
            default -> 0;
        };
    }

    private double inferPddLevelScore(double rating) {
        if (rating >= 4.4) {
            return 0.85;
        }
        if (rating >= 3.8) {
            return 0.60;
        }
        if (rating > 0) {
            return 0.30;
        }
        return 0;
    }

    private String normalizePddLevel(String level, double rating, double score) {
        String normalized = normalizeLevelText(level);
        if (!normalized.isBlank()) {
            return normalized;
        }
        if (rating >= 4.4 || score >= 0.80) {
            return "high";
        }
        if (rating >= 3.8 || score >= 0.50) {
            return "mid";
        }
        return "low";
    }

    private String normalizeLevelText(String level) {
        if (level == null || level.isBlank()) {
            return "";
        }
        String normalized = level.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("high") || normalized.equals("\u9ad8")) {
            return "high";
        }
        if (normalized.equals("mid") || normalized.equals("medium") || normalized.equals("\u4e2d")) {
            return "mid";
        }
        if (normalized.equals("low") || normalized.equals("\u4f4e")) {
            return "low";
        }
        return "";
    }

    private String pddLevelLabel(String level) {
        String displayLevel = switch (level) {
            case "high" -> "\u9ad8";
            case "mid" -> "\u4e2d";
            case "low" -> "\u4f4e";
            default -> "";
        };
        return "\u5e97\u94fa\u53e3\u7891 " + displayLevel;
    }

    private String shopScoreLabel(double score) {
        return String.format(Locale.US, "\u5e97\u94fa\u8bc4\u5206 %.1f", score * 5.0);
    }

    private String sellerLabel(double score) {
        return String.format(Locale.US, "\u5356\u5bb6\u4fe1\u8a89 %.0f%%", score * 100.0);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1.0, value));
    }

    private boolean isPdd(String platform) {
        if (platform == null) {
            return false;
        }
        String normalized = platform.toLowerCase(Locale.ROOT);
        return normalized.contains("pdd") || platform.contains("\u62fc\u591a\u591a");
    }

    private boolean isTaobao(String platform) {
        if (platform == null) {
            return false;
        }
        String normalized = platform.toLowerCase(Locale.ROOT);
        return normalized.contains("taobao") || platform.contains("\u6dd8\u5b9d");
    }

    private boolean isTmall(ProductCard product) {
        if (product == null) {
            return false;
        }
        String platform = product.platform();
        if (platform != null) {
            String normalized = platform.toLowerCase(Locale.ROOT);
            if (normalized.contains("tmall") || platform.contains("\u5929\u732b")) {
                return true;
            }
        }
        return product.tags() != null && product.tags().stream()
                .anyMatch(tag -> tag != null && (tag.toLowerCase(Locale.ROOT).contains("tmall")
                        || tag.contains("\u5929\u732b")));
    }

    private boolean isEbay(String platform) {
        return platform != null && platform.toLowerCase(Locale.ROOT).contains("ebay");
    }

    private record TrustSignal(double score, double confidence, String label) {
        double calibratedTrust() {
            return score * confidence;
        }
    }
}
