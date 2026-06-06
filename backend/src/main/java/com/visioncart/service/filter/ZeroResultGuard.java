package com.visioncart.service.filter;

import com.visioncart.api.dto.ProductCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Safety guard that prevents NLP filters from producing empty results.
 * Implements the core principle: NEVER leave the user with an empty product list
 * due to a natural language filter.
 */
@Component
public class ZeroResultGuard {

    private static final Logger log = LoggerFactory.getLogger(ZeroResultGuard.class);

    /**
     * Evaluate the filter execution result and decide whether to commit.
     *
     * CRITICAL: Zero results ALWAYS roll back, regardless of threshold.
     * This prevents structured filters (price, platform, rating) from
     * clearing the product list when no candidates match.
     *
     * Also respects FilterPlan.zeroResultPolicy():
     * - DO_NOT_COMMIT: 0 results must roll back
     * - WARN_SPARSE: sparse results allowed with warning
     * - COMMIT: only for system-internal forced commits
     */
    public GuardDecision evaluate(List<ProductCard> trialResults, List<ProductCard> previousResults,
                                  FilterPlan plan, int totalInPool) {
        int resultCount = trialResults == null ? 0 : trialResults.size();
        int previousCount = previousResults == null ? 0 : previousResults.size();
        int threshold = plan.minResultThreshold();
        FilterPlan.ZeroResultPolicy zeroPolicy = plan.zeroResultPolicy();

        // Case 1: ZERO RESULTS — ALWAYS ROLLBACK
        // This is the core safety rule: never leave the user with an empty list
        if (resultCount == 0) {
            log.info("ZeroResultGuard: 0 results, policy={}, refusing to commit, keeping previous {} results",
                    zeroPolicy, previousCount);
            if (previousCount > 0) {
                return new GuardDecision(
                        GuardDecision.Action.ROLLBACK,
                        previousResults,
                        "没有找到明确符合的商品，已保留原结果",
                        "可尝试撤回、换个说法或放宽条件",
                        true
                );
            }
            return new GuardDecision(
                    GuardDecision.Action.ROLLBACK,
                    List.of(),
                    "没有找到符合的商品",
                    "请尝试其他筛选条件",
                    true
            );
        }

        // Case 2: DO_NOT_COMMIT — already handled by Case 1 (0 results rollback).
        // For non-zero results, proceed to normal commit/warning logic below.
        // The "do not commit" semantics only apply to zero results.

        // Case 3: Results are healthy — commit normally
        if (resultCount >= 10) {
            log.info("ZeroResultGuard: {} results >= 10, committing normally", resultCount);
            return new GuardDecision(
                    GuardDecision.Action.COMMIT,
                    trialResults,
                    null,
                    null,
                    false
            );
        }

        // Case 4: Results are sparse but acceptable
        int effectiveThreshold = Math.max(1, threshold);
        if (resultCount >= effectiveThreshold) {
            String message = String.format("只找到 %d 个可能符合的商品，结果较少", resultCount);
            log.info("ZeroResultGuard: {} results >= effectiveThreshold {}, committing with warning",
                    resultCount, effectiveThreshold);
            return new GuardDecision(
                    GuardDecision.Action.COMMIT_WITH_WARNING,
                    trialResults,
                    message,
                    "可尝试撤回或放宽条件",
                    false
            );
        }

        // Case 5: Very few results (1 to effectiveThreshold-1) — commit with warning
        String message = String.format("只找到 %d 个可能符合的商品", resultCount);
        log.info("ZeroResultGuard: {} results below threshold {}, partial commit", resultCount, effectiveThreshold);

        if (previousCount > 0) {
            return new GuardDecision(
                    GuardDecision.Action.COMMIT_WITH_WARNING,
                    trialResults,
                    message,
                    "结果较少，已展示部分匹配商品",
                    false
            );
        }

        return new GuardDecision(
                GuardDecision.Action.COMMIT_WITH_WARNING,
                trialResults,
                message,
                "可尝试撤回或换个说法",
                false
        );
    }

    /**
     * Decision from the zero-result guard.
     */
    public record GuardDecision(
            Action action,
            List<ProductCard> products,
            String message,
            String hint,
            boolean keptPrevious
    ) {
        public enum Action {
            COMMIT,
            COMMIT_WITH_WARNING,
            ROLLBACK
        }

        public boolean shouldCommit() {
            return action == Action.COMMIT || action == Action.COMMIT_WITH_WARNING;
        }
    }
}
