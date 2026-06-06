package com.visioncart.service.filter;

import java.util.List;

/**
 * Execution plan produced by FilterPlanner.
 * Defines how filter clauses should be applied and what safety guards to use.
 */
public record FilterPlan(
    String planId,
    List<FilterClause> clauses,
    ExecutionMode executionMode,
    int minResultThreshold,
    ZeroResultPolicy zeroResultPolicy,
    String explanation
) {
    public enum ExecutionMode {
        /** Trial execution first — only commit if results are healthy. */
        TRIAL_FIRST,
        /** Direct execution — apply immediately (for structured filters). */
        DIRECT,
        /** Rerank only — no filtering, just reorder. */
        RERANK_ONLY
    }

    public enum ZeroResultPolicy {
        /** Do not commit filter state; keep previous results. */
        DO_NOT_COMMIT,
        /** Commit but warn user results are sparse. */
        WARN_SPARSE,
        /** Commit normally. */
        COMMIT
    }

    public static FilterPlan trialFirst(String planId, List<FilterClause> clauses, int minThreshold) {
        return new FilterPlan(planId, clauses, ExecutionMode.TRIAL_FIRST,
                minThreshold, ZeroResultPolicy.DO_NOT_COMMIT, null);
    }

    public static FilterPlan direct(String planId, List<FilterClause> clauses) {
        return new FilterPlan(planId, clauses, ExecutionMode.DIRECT,
                0, ZeroResultPolicy.COMMIT, null);
    }

    public static FilterPlan rerankOnly(String planId, List<FilterClause> clauses) {
        return new FilterPlan(planId, clauses, ExecutionMode.RERANK_ONLY,
                0, ZeroResultPolicy.COMMIT, null);
    }
}
