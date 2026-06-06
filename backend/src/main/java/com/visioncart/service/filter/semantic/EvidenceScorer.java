package com.visioncart.service.filter.semantic;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SemanticActionPlan;
import com.visioncart.service.filter.capability.ProductFeatureExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Scores products against semantic filters using multi-evidence evaluation.
 *
 * Core logic:
 * 1. For each product, check negative evidence first → reject if hit
 * 2. Check positive evidence rules (OR logic — any match counts)
 * 3. Take the highest-scoring matched rule as the product's score
 * 4. Classify into MatchBucket based on score
 */
@Component
public class EvidenceScorer {

    private static final Logger log = LoggerFactory.getLogger(EvidenceScorer.class);

    private final ProductFeatureExtractor featureExtractor;

    public EvidenceScorer(ProductFeatureExtractor featureExtractor) {
        this.featureExtractor = featureExtractor;
    }

    // ==================== Public API ====================

    public List<ScoredProduct> score(List<ProductCard> products, SemanticActionPlan.SemanticFilter filter) {
        List<ScoredProduct> scored = new ArrayList<>();
        for (ProductCard product : products) {
            scored.add(scoreSingle(product, filter));
        }
        scored.sort((a, b) -> {
            int bucketCmp = a.bucket().compareTo(b.bucket());
            return bucketCmp != 0 ? bucketCmp : Double.compare(b.score(), a.score());
        });
        return scored;
    }

    public ScoredProduct scoreSingle(ProductCard product, SemanticActionPlan.SemanticFilter filter) {
        ProductFeatureExtractor.ProductFeatures features = featureExtractor.extract(product);
        String allText = features.allText();
        List<String> evidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> matchedRuleIds = new ArrayList<>();

        // 1. Check negative evidence first → REJECT
        if (filter.negativeEvidence() != null) {
            for (SemanticActionPlan.EvidenceRule neg : filter.negativeEvidence()) {
                if (matchesNegativeEvidence(allText, features, neg)) {
                    evidence.add("排除: " + neg.description());
                    return ScoredProduct.rejected(product, evidence);
                }
            }
        }

        // 2. Check positive evidence rules (OR logic — best match wins)
        double bestScore = 0.0;
        String bestWarning = null;
        String bestBucket = "UNCERTAIN_RELATED";

        if (filter.positiveEvidence() != null) {
            for (SemanticActionPlan.EvidenceRule pos : filter.positiveEvidence()) {
                EvidenceMatch match = evaluateEvidenceRule(allText, features, pos);
                if (match.matched()) {
                    evidence.add(match.evidence());
                    matchedRuleIds.add(pos.id());
                    if (match.score() > bestScore) {
                        bestScore = match.score();
                        bestWarning = match.warning();
                        bestBucket = pos.bucket() != null ? pos.bucket() : "INFERRED_MATCH";
                    }
                }
            }
        }

        // 3. Classify into bucket
        MatchBucket bucket;
        if (bestScore >= 0.85) {
            bucket = MatchBucket.EXPLICIT_MATCH;
        } else if (bestScore >= 0.60) {
            bucket = MatchBucket.INFERRED_MATCH;
        } else if (bestScore >= 0.30 || !evidence.isEmpty()) {
            bucket = MatchBucket.UNCERTAIN_RELATED;
        } else {
            if (isRelevantCategory(allText, filter.code())) {
                bucket = MatchBucket.UNCERTAIN_RELATED;
                evidence.add("商品类别相关但缺少详细信息");
            } else {
                bucket = MatchBucket.REJECTED;
                evidence.add("未找到相关证据");
            }
        }

        if (bestWarning != null) {
            warnings.add(bestWarning);
        }

        return new ScoredProduct(product, bestScore, bucket, evidence, warnings, matchedRuleIds);
    }

    // ==================== Evidence Evaluation ====================

    private EvidenceMatch evaluateEvidenceRule(String allText,
                                               ProductFeatureExtractor.ProductFeatures features,
                                               SemanticActionPlan.EvidenceRule rule) {
        String op = rule.operator();

        // Formula-based evaluation (whitelist formulas)
        if (rule.formula() != null && !rule.formula().isEmpty()) {
            EvidenceMatch formulaMatch = evaluateFormula(features, rule);
            if (formulaMatch != null) return formulaMatch;
        }

        // Field-based evaluation
        if (rule.fields() != null && !rule.fields().isEmpty()) {
            for (String field : rule.fields()) {
                String fieldValue = getFieldValue(allText, features, field);
                if (fieldValue == null) continue;

                EvidenceMatch match = evaluateFieldMatch(fieldValue, op, rule);
                if (match != null) return match;
            }
        }

        return EvidenceMatch.noMatch();
    }

    /**
     * Evaluate a field value against an operator and rule.
     */
    private EvidenceMatch evaluateFieldMatch(String fieldValue, String op, SemanticActionPlan.EvidenceRule rule) {
        String lowerField = fieldValue.toLowerCase();

        return switch (op) {
            case "contains_any" -> {
                List<String> keywords = rule.values();
                if (keywords == null || keywords.isEmpty()) yield EvidenceMatch.noMatch();
                for (String kw : keywords) {
                    if (lowerField.contains(kw.toLowerCase())) {
                        yield new EvidenceMatch(true, "包含「" + kw + "」", rule.score(), rule.warning());
                    }
                }
                yield EvidenceMatch.noMatch();
            }
            case "contains_all" -> {
                List<String> keywords = rule.values();
                if (keywords == null || keywords.isEmpty()) yield EvidenceMatch.noMatch();
                List<String> matched = new ArrayList<>();
                for (String kw : keywords) {
                    if (lowerField.contains(kw.toLowerCase())) {
                        matched.add(kw);
                    }
                }
                if (matched.size() == keywords.size()) {
                    yield new EvidenceMatch(true, "包含全部关键词", rule.score(), rule.warning());
                }
                yield EvidenceMatch.noMatch();
            }
            case "contains" -> {
                String keyword = rule.value() instanceof String s ? s : null;
                if (keyword == null) yield EvidenceMatch.noMatch();
                if (lowerField.contains(keyword.toLowerCase())) {
                    yield new EvidenceMatch(true, "包含「" + keyword + "」", rule.score(), rule.warning());
                }
                yield EvidenceMatch.noMatch();
            }
            case "regex" -> {
                String pattern = rule.value() instanceof String s ? s : null;
                if (pattern == null) yield EvidenceMatch.noMatch();
                try {
                    if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(fieldValue).find()) {
                        yield new EvidenceMatch(true, "匹配模式「" + pattern + "」", rule.score(), rule.warning());
                    }
                } catch (Exception e) {
                    log.debug("Invalid regex pattern: {}", pattern);
                }
                yield EvidenceMatch.noMatch();
            }
            case "equals" -> {
                if (fieldValue.equalsIgnoreCase(String.valueOf(rule.value()))) {
                    yield new EvidenceMatch(true, "等于「" + rule.value() + "」", rule.score(), rule.warning());
                }
                yield EvidenceMatch.noMatch();
            }
            case "<=", "le" -> {
                double fv = parseDouble(fieldValue);
                double tv = toDouble(rule.value());
                if (!Double.isNaN(fv) && !Double.isNaN(tv) && fv <= tv) {
                    yield new EvidenceMatch(true, String.format("%.0f ≤ %.0f", fv, tv), rule.score(), rule.warning());
                }
                yield EvidenceMatch.noMatch();
            }
            case ">=", "ge" -> {
                double fv = parseDouble(fieldValue);
                double tv = toDouble(rule.value());
                if (!Double.isNaN(fv) && !Double.isNaN(tv) && fv >= tv) {
                    yield new EvidenceMatch(true, String.format("%.0f ≥ %.0f", fv, tv), rule.score(), rule.warning());
                }
                yield EvidenceMatch.noMatch();
            }
            case "<", "lt" -> {
                double fv = parseDouble(fieldValue);
                double tv = toDouble(rule.value());
                if (!Double.isNaN(fv) && !Double.isNaN(tv) && fv < tv) {
                    yield new EvidenceMatch(true, String.format("%.0f < %.0f", fv, tv), rule.score(), rule.warning());
                }
                yield EvidenceMatch.noMatch();
            }
            case ">", "gt" -> {
                double fv = parseDouble(fieldValue);
                double tv = toDouble(rule.value());
                if (!Double.isNaN(fv) && !Double.isNaN(tv) && fv > tv) {
                    yield new EvidenceMatch(true, String.format("%.0f > %.0f", fv, tv), rule.score(), rule.warning());
                }
                yield EvidenceMatch.noMatch();
            }
            default -> EvidenceMatch.noMatch();
        };
    }

    // ==================== Formula Evaluation (Whitelist) ====================

    /**
     * Whitelist formula evaluation — no string expression parsing.
     */
    private EvidenceMatch evaluateFormula(ProductFeatureExtractor.ProductFeatures features,
                                          SemanticActionPlan.EvidenceRule rule) {
        String formulaType = rule.formula();
        double threshold = toDouble(rule.value());
        String op = rule.operator();
        if (Double.isNaN(threshold) || op == null) return null;

        OptionalDouble computed = computeFormula(features, formulaType);
        if (computed.isEmpty()) return null;

        double val = computed.getAsDouble();
        boolean matches = switch (op) {
            case "<=", "le", "formula_lte" -> val <= threshold;
            case ">=", "ge", "formula_gte" -> val >= threshold;
            case "<", "lt" -> val < threshold;
            case ">", "gt" -> val > threshold;
            default -> false;
        };

        if (matches) {
            String desc = String.format("%s: %.1f %s %.0f", rule.description(), val, op, threshold);
            return new EvidenceMatch(true, desc, rule.score(), rule.warning());
        }
        return EvidenceMatch.noMatch();
    }

    /**
     * Whitelist formula computation — safe, no string expression parsing.
     */
    private OptionalDouble computeFormula(ProductFeatureExtractor.ProductFeatures features, String formulaType) {
        if (formulaType == null) return OptionalDouble.empty();

        return switch (formulaType) {
            case "ENERGY_WH_FROM_MAH_VOLTAGE" -> {
                if (features.mAh().isPresent() && features.volt().isPresent()) {
                    yield OptionalDouble.of(features.mAh().getAsInt() * features.volt().getAsDouble() / 1000.0);
                }
                yield OptionalDouble.empty();
            }
            case "ENERGY_WH_FROM_MAH_DEFAULT" -> {
                if (features.mAh().isPresent()) {
                    yield OptionalDouble.of(features.mAh().getAsInt() * 3.7 / 1000.0);
                }
                yield OptionalDouble.empty();
            }
            default -> OptionalDouble.empty();
        };
    }

    // ==================== Negative Evidence ====================

    private boolean matchesNegativeEvidence(String allText,
                                            ProductFeatureExtractor.ProductFeatures features,
                                            SemanticActionPlan.EvidenceRule neg) {
        String op = neg.operator();
        String lowerText = allText.toLowerCase();

        // Check by values list (contains_any)
        if ("contains_any".equals(op) && neg.values() != null) {
            for (String kw : neg.values()) {
                if (lowerText.contains(kw.toLowerCase())) return true;
            }
        }

        // Check by single value (contains)
        if ("contains".equals(op) && neg.value() instanceof String keyword) {
            if (lowerText.contains(keyword.toLowerCase())) return true;
        }

        // Check by regex
        if ("regex".equals(op) && neg.value() instanceof String pattern) {
            try {
                if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(allText).find()) return true;
            } catch (Exception ignored) {}
        }

        // Check by fields
        if (neg.fields() != null) {
            for (String field : neg.fields()) {
                String fieldValue = getFieldValue(allText, features, field);
                if (fieldValue == null) continue;
                EvidenceMatch match = evaluateFieldMatch(fieldValue, op, neg);
                if (match != null && match.matched()) return true;
            }
        }

        // Check by structured features (productRole, isAccessory, riskWords)
        if ("exclude_accessory".equals(neg.id()) || "exclude_by_role".equals(neg.id())) {
            if (features.isAccessory()) return true;
            String role = features.role();
            if (role != null && ("accessory".equals(role) || "case".equals(role) || "part".equals(role)
                    || "consumable".equals(role) || "storage".equals(role))) {
                return true;
            }
            if (features.hasRiskWords()) return true;
        }

        return false;
    }

    // ==================== Field Access ====================

    private String getFieldValue(String allText, ProductFeatureExtractor.ProductFeatures features, String field) {
        return switch (field) {
            case "title" -> features.title();
            case "tags" -> String.join(" ", features.tags());
            case "all_text", "allText" -> allText;
            case "energy_wh" -> features.wh().isPresent() ? String.valueOf(features.wh().getAsDouble()) : null;
            case "capacity_mah" -> features.mAh().isPresent() ? String.valueOf(features.mAh().getAsInt()) : null;
            case "voltage" -> features.volt().isPresent() ? String.valueOf(features.volt().getAsDouble()) : null;
            case "wattage" -> features.wattage().isPresent() ? String.valueOf(features.wattage().getAsInt()) : null;
            case "ip_rating" -> features.ipRating().orElse(null);
            case "product_role" -> features.role();
            case "certifications" -> features.certifications().isEmpty() ? null : String.join(" ", features.certifications());
            default -> null;
        };
    }

    // ==================== Helpers ====================

    private double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replaceAll("[,，]", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) return parseDouble(s);
        return Double.NaN;
    }

    private boolean isRelevantCategory(String text, String capabilityCode) {
        return switch (capabilityCode) {
            case "airplane_allowed" -> text.contains("充电宝") || text.contains("移动电源")
                    || text.contains("power bank") || text.contains("电源");
            case "waterproof" -> true;
            case "fast_charging" -> text.contains("充电") || text.contains("电池") || text.contains("电源");
            case "eye_protection" -> text.contains("灯") || text.contains("台灯") || text.contains("屏幕");
            case "noise_cancelling" -> text.contains("耳机") || text.contains("耳麦");
            case "running_suitable" -> text.contains("鞋") || text.contains("跑鞋");
            default -> true;
        };
    }

    /**
     * Result of evaluating a single evidence rule.
     */
    record EvidenceMatch(boolean matched, String evidence, double score, String warning) {
        static EvidenceMatch noMatch() {
            return new EvidenceMatch(false, null, 0.0, null);
        }
    }
}
