package com.visioncart.service.filter;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.filter.capability.CapabilityRegistry;
import com.visioncart.service.filter.capability.CapabilityResult;
import com.visioncart.service.filter.capability.GenericKeywordCapabilityEvaluator;
import com.visioncart.service.search.ProductSortService;
import com.visioncart.service.search.SearchTextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Core execution engine for the semantic filter system.
 * Implements three-layer execution: Hard Filter → Safe Filter → Rerank.
 * Integrates with ZeroResultGuard for safe commit/rollback semantics.
 */
@Service
public class FilterExecutionService {

    private static final Logger log = LoggerFactory.getLogger(FilterExecutionService.class);

    private final CapabilityRegistry capabilityRegistry;
    private final ZeroResultGuard zeroResultGuard;
    private final GenericKeywordCapabilityEvaluator genericEvaluator;
    private final ProductSortService productSortService;

    public FilterExecutionService(CapabilityRegistry capabilityRegistry,
                                  ZeroResultGuard zeroResultGuard,
                                  GenericKeywordCapabilityEvaluator genericEvaluator,
                                  ProductSortService productSortService) {
        this.capabilityRegistry = capabilityRegistry;
        this.zeroResultGuard = zeroResultGuard;
        this.genericEvaluator = genericEvaluator;
        this.productSortService = productSortService;
    }

    /**
     * Execute a filter plan against a list of candidates.
     * Returns the execution result with products, warnings, and explanations.
     */
    public FilterExecutionResult execute(FilterPlan plan, List<ProductCard> candidates,
                                         List<ProductCard> previousProducts,
                                         SearchFilter baseFilter, String category) {
        if (plan.clauses().isEmpty()) {
            return new FilterExecutionResult(candidates, candidates.size(), true, List.of(), List.of(), false);
        }

        List<ProductCard> currentResults = new ArrayList<>(candidates);
        List<String> warnings = new ArrayList<>();
        List<String> explanations = new ArrayList<>();

        // Layer 1: Hard Filter (structured conditions)
        List<FilterClause> hardClauses = plan.clauses().stream()
                .filter(c -> c.applyMode() == FilterClause.ApplyMode.HARD_FILTER)
                .toList();
        if (!hardClauses.isEmpty()) {
            currentResults = applyHardFilter(currentResults, hardClauses, baseFilter);
            log.info("Hard filter: {} clauses, {} -> {} results",
                    hardClauses.size(), candidates.size(), currentResults.size());
        }

        // Layer 2: Safe Filter (capability conditions — trial first)
        List<FilterClause> safeClauses = plan.clauses().stream()
                .filter(c -> c.applyMode() == FilterClause.ApplyMode.SAFE_FILTER)
                .toList();
        if (!safeClauses.isEmpty()) {
            FilterTrialResult trialResult = applySafeFilter(currentResults, safeClauses, category);
            currentResults = trialResult.products();
            warnings.addAll(trialResult.warnings());
            explanations.addAll(trialResult.explanations());
            log.info("Safe filter: {} clauses, {} results after trial", safeClauses.size(), currentResults.size());
        }

        // Layer 3: Exclusion filter
        List<FilterClause> exclusionClauses = plan.clauses().stream()
                .filter(c -> c.applyMode() == FilterClause.ApplyMode.EXCLUSION)
                .toList();
        if (!exclusionClauses.isEmpty()) {
            int beforeSize = currentResults.size();
            currentResults = applyExclusionFilter(currentResults, exclusionClauses);
            log.info("Exclusion filter: {} clauses, {} -> {} results",
                    exclusionClauses.size(), beforeSize, currentResults.size());
        }

        // Layer 4: Rerank (preference conditions — never removes, only reorders)
        List<FilterClause> rerankClauses = plan.clauses().stream()
                .filter(c -> c.applyMode() == FilterClause.ApplyMode.RERANK)
                .toList();
        if (!rerankClauses.isEmpty()) {
            currentResults = applyRerank(currentResults, rerankClauses, category);
            log.info("Rerank: {} clauses, {} results", rerankClauses.size(), currentResults.size());
        }

        // Zero Result Guard
        ZeroResultGuard.GuardDecision guardDecision = zeroResultGuard.evaluate(
                currentResults, previousProducts, plan, candidates.size());

        List<ProductCard> finalProducts;
        boolean committed;
        String guardMessage = guardDecision.message();
        String guardHint = guardDecision.hint();

        if (guardDecision.shouldCommit()) {
            finalProducts = currentResults;
            committed = true;
            if (guardMessage != null) warnings.add(guardMessage);
            if (guardHint != null) warnings.add(guardHint);
        } else {
            // Rollback — keep previous results
            finalProducts = guardDecision.products();
            committed = false;
            if (guardMessage != null) warnings.add(guardMessage);
            if (guardHint != null) warnings.add(guardHint);
            log.info("ZeroResultGuard: rolled back, keeping {} previous results", finalProducts.size());
        }

        return new FilterExecutionResult(
                finalProducts,
                candidates.size(),
                committed,
                warnings,
                explanations,
                guardDecision.keptPrevious()
        );
    }

    /**
     * Apply hard (structured) filters — deterministic conditions.
     */
    private List<ProductCard> applyHardFilter(List<ProductCard> products, List<FilterClause> clauses,
                                              SearchFilter baseFilter) {
        return products.stream().filter(product -> {
            for (FilterClause clause : clauses) {
                if (!matchesStructuredClause(product, clause)) {
                    return false;
                }
            }
            return true;
        }).toList();
    }

    private boolean matchesStructuredClause(ProductCard product, FilterClause clause) {
        String field = clause.field() != null ? clause.field().toLowerCase() : "";
        Object value = clause.value();
        String text = productText(product);

        return switch (field) {
            case "price_max", "price_lte" -> {
                if (value instanceof Number n && product.price() != null)
                    yield product.price().doubleValue() <= n.doubleValue();
                yield true;
            }
            case "price_min", "price_gte" -> {
                if (value instanceof Number n && product.price() != null)
                    yield product.price().doubleValue() >= n.doubleValue();
                yield true;
            }
            case "rating_min" -> {
                if (value instanceof Number n)
                    yield product.rating() >= n.doubleValue();
                yield true;
            }
            case "platform" -> {
                if (value instanceof String s)
                    yield product.platform() != null && product.platform().equalsIgnoreCase(s);
                yield true;
            }
            case "self_operated" -> {
                if (value instanceof Boolean b)
                    yield product.selfOperated() == b;
                yield true;
            }
            case "brand" -> {
                if (value instanceof String s) {
                    String lower = s.toLowerCase();
                    yield (product.brand() != null && product.brand().toLowerCase().contains(lower))
                            || (product.title() != null && product.title().toLowerCase().contains(lower));
                }
                yield true;
            }
            case "color" -> {
                if (value instanceof String s) {
                    String lower = s.toLowerCase();
                    yield text.contains(lower)
                            || (product.tags() != null && product.tags().stream()
                                    .anyMatch(t -> t.toLowerCase().contains(lower)));
                }
                yield true;
            }
            case "keyword" -> {
                if (value instanceof String s) {
                    String lower = s.toLowerCase();
                    // Special meta-keywords are handled by SearchFilter, not here
                    if (lower.startsWith("[") && lower.endsWith("]")) yield true;
                    yield text.contains(lower);
                }
                yield true;
            }
            case "exclude_roles" -> {
                // Exclusion clauses use List<String> value — handled by applyExclusionFilter, not here
                yield true;
            }
            case "sort_by" -> {
                // Sort is a reranking operation, not a filter — always passes
                yield true;
            }
            default -> true;
        };
    }

    /**
     * Apply safe (capability) filters with three-bucket approach:
     * - matched: all capabilities match with sufficient confidence
     * - uncertain: some capabilities are uncertain (not clearly matched, not clearly rejected)
     * - rejected: explicitly don't match
     *
     * If matched >= 3, only show matched.
     * If matched < 3, supplement with uncertain and warn user.
     */
    private FilterTrialResult applySafeFilter(List<ProductCard> products, List<FilterClause> clauses,
                                              String category) {
        List<ProductCard> matched = new ArrayList<>();
        List<ProductCard> uncertain = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> explanations = new ArrayList<>();

        for (ProductCard product : products) {
            boolean allMatched = true;
            boolean anyUncertain = false;

            for (FilterClause clause : clauses) {
                CapabilityResult capResult = capabilityRegistry.evaluate(
                        clause.field(), product, category);

                if (capResult.evidence() != null) {
                    explanations.addAll(capResult.evidence());
                }
                if (capResult.warning() != null) {
                    warnings.add(capResult.warning());
                }

                if (capResult.matched() && capResult.confidence() >= 0.5) {
                    // Confident match — good
                } else if (capResult.matched() && capResult.confidence() >= 0.2) {
                    // Low confidence match — uncertain
                    anyUncertain = true;
                } else if (!capResult.matched() && capResult.confidence() >= 0.2) {
                    // Not matched but not clearly rejected — uncertain
                    anyUncertain = true;
                } else {
                    // Clearly rejected
                    allMatched = false;
                    break;
                }
            }

            if (allMatched && !anyUncertain) {
                matched.add(product);
            } else if (allMatched) {
                uncertain.add(product);
            }
            // else: rejected, skip
        }

        // Three-bucket merge strategy
        List<ProductCard> results;
        if (matched.size() >= 3) {
            // Enough confident matches — only show matched
            results = matched;
        } else if (matched.size() + uncertain.size() > 0) {
            // Supplement with uncertain products
            results = new ArrayList<>(matched);
            results.addAll(uncertain);
            if (!uncertain.isEmpty()) {
                warnings.add(0, String.format("部分商品未标明具体参数，已作为可能符合项靠后展示（%d个明确匹配，%d个可能匹配）",
                        matched.size(), uncertain.size()));
            }
        } else {
            results = List.of();
        }

        return new FilterTrialResult(results, warnings, explanations);
    }

    /**
     * Apply exclusion filters.
     */
    private List<ProductCard> applyExclusionFilter(List<ProductCard> products, List<FilterClause> clauses) {
        return products.stream().filter(product -> {
            for (FilterClause clause : clauses) {
                if (matchesExclusion(product, clause)) {
                    return false; // excluded
                }
            }
            return true;
        }).toList();
    }

    /**
     * Check if a product matches an exclusion clause.
     *
     * For exclude_roles clauses:
     * 1. First check structured productRole field (most reliable)
     * 2. Then fall back to text matching with Chinese accessory keywords
     *
     * For other exclusion clauses:
     * - Match against product text
     */
    private boolean matchesExclusion(ProductCard product, FilterClause clause) {
        // For exclude_roles: check productRole first, then text
        if ("exclude_roles".equals(clause.field())) {
            return matchesExcludeRoles(product, clause);
        }

        // Generic text-based exclusion
        String text = productText(product);
        if (clause.value() instanceof String s) {
            return text.contains(s.toLowerCase());
        }
        if (clause.value() instanceof List<?> list) {
            for (Object item : list) {
                if (text.contains(item.toString().toLowerCase())) return true;
            }
        }
        return false;
    }

    /**
     * Check if a product should be excluded by role.
     * Priority: productRole (structured) > text keywords (fallback)
     */
    private boolean matchesExcludeRoles(ProductCard product, FilterClause clause) {
        // 1. Check structured productRole field
        String role = product.productRole();
        if (role != null && !role.isBlank()) {
            String normalized = role.toLowerCase().trim();
            if (clause.value() instanceof List<?> excludedRoles) {
                for (Object excluded : excludedRoles) {
                    if (normalized.contains(excluded.toString().toLowerCase())) {
                        return true;
                    }
                }
            } else if (clause.value() instanceof String s) {
                if (normalized.contains(s.toLowerCase())) {
                    return true;
                }
            }
        }

        // 2. Fallback: text-based matching with Chinese accessory keywords
        String text = productText(product);
        if (clause.value() instanceof List<?> excludedRoles) {
            for (Object excluded : excludedRoles) {
                String ex = excluded.toString().toLowerCase();
                if (text.contains(ex)) return true;
            }
        } else if (clause.value() instanceof String s) {
            if (text.contains(s.toLowerCase())) return true;
        }

        // 3. Additional Chinese accessory keywords
        return containsAccessoryKeyword(text);
    }

    /**
     * Check if product text contains common Chinese accessory indicators.
     */
    private boolean containsAccessoryKeyword(String text) {
        for (String keyword : ACCESSORY_KEYWORDS) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private static final List<String> ACCESSORY_KEYWORDS = List.of(
            "手机壳", "手机套", "保护套", "保护壳", "钢化膜", "屏幕膜", "贴膜",
            "杯套", "杯盖", "替换装", "替换芯", "配件", "收纳袋", "收纳盒",
            "防水袋", "防水壳", "防水套", "鞋垫", "鞋套", "鞋带",
            "护膝", "护腕", "护眼贴", "护眼罩", "耳机套", "耳机壳",
            "充电线", "数据线", "转接头", "适配器壳", "支架壳",
            "试用装", "小样", "赠品", "替换头", "滤芯", "滤网"
    );

    /**
     * Apply rerank (preference + sort) — never removes products, only reorders.
     */
    private List<ProductCard> applyRerank(List<ProductCard> products, List<FilterClause> clauses,
                                          String category) {
        // Extract sort_by clause (if any)
        FilterClause sortClause = clauses.stream()
                .filter(c -> "sort_by".equalsIgnoreCase(c.field()))
                .findFirst().orElse(null);

        if (sortClause != null && sortClause.value() instanceof String sortKey) {
            // Deterministic sort by field — delegate to centralized sort service
            return productSortService.sortByKey(new ArrayList<>(products), sortKey);
        }

        // Preference-based reranking (capability scoring)
        List<ProductCard> sorted = new ArrayList<>(products);
        sorted.sort((a, b) -> {
            double scoreA = calculatePreferenceScore(a, clauses, category);
            double scoreB = calculatePreferenceScore(b, clauses, category);
            return Double.compare(scoreB, scoreA); // higher is better
        });
        return sorted;
    }

    private double calculatePreferenceScore(ProductCard product, List<FilterClause> clauses, String category) {
        double score = 0;
        for (FilterClause clause : clauses) {
            CapabilityResult result = capabilityRegistry.evaluate(clause.field(), product, category);
            if (result.matched()) {
                score += result.confidence() * 10;
            }
        }
        // Also consider product rating and sales as tiebreakers
        score += product.rating() * 0.5;
        score += Math.log1p(product.sales()) * 0.1;
        return score;
    }

    private String productText(ProductCard product) {
        StringBuilder sb = new StringBuilder();
        if (product.title() != null) sb.append(product.title()).append(" ");
        if (product.brand() != null) sb.append(product.brand()).append(" ");
        if (product.shopName() != null) sb.append(product.shopName()).append(" ");
        if (product.tags() != null) sb.append(String.join(" ", product.tags()));
        return sb.toString().toLowerCase();
    }

    /**
     * Result of executing a filter plan.
     */
    public record FilterExecutionResult(
            List<ProductCard> products,
            int totalInPool,
            boolean committed,
            List<String> warnings,
            List<String> explanations,
            boolean keptPrevious
    ) {}

    private record FilterTrialResult(
            List<ProductCard> products,
            List<String> warnings,
            List<String> explanations
    ) {}
}
