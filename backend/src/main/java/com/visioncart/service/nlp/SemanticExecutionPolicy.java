package com.visioncart.service.nlp;

import com.visioncart.api.dto.SemanticActionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Controls whether a SemanticActionPlan is allowed to invoke synchronous LLM Judge.
 *
 * <p>Design principle: LLM Planner UNDERSTANDS and PLANS;
 * synchronous LLM Judge (product-level scoring) is only allowed for small,
 * complex selection tasks — not for broad subjective preferences.
 */
@Component
public class SemanticExecutionPolicy {

    private static final Logger log = LoggerFactory.getLogger(SemanticExecutionPolicy.class);

    /** Max candidate pool size for synchronous LLM Judge */
    private static final int SYNC_JUDGE_MAX_POOL = 12;

    /**
     * Normalize a plan for synchronous execution.
     * Downgrades LLM_RERANK / COMBINED with judge to PREFERENCE_RERANK when the pool is large.
     */
    public SemanticActionPlan normalizeForSyncExecution(
            String userInput,
            SemanticActionPlan plan,
            int visibleProductCount) {
        if (plan == null) {
            return plan;
        }

        String mode = plan.executionMode();

        // 1. PREFERENCE_RERANK must never carry a judge — strip it
        if ("PREFERENCE_RERANK".equals(mode) && hasJudge(plan)) {
            log.info("ExecutionPolicy: stripping judge from PREFERENCE_RERANK");
            return stripJudge(plan);
        }

        // 2. LLM_RERANK only allowed for small pools
        if ("LLM_RERANK".equals(mode) && visibleProductCount > SYNC_JUDGE_MAX_POOL) {
            log.info("ExecutionPolicy: downgrading LLM_RERANK → PREFERENCE_RERANK (pool={})", visibleProductCount);
            return toPreferenceRerank(plan);
        }

        // 3. COMBINED with only preferences (no hard filters, no semantic evidence) → local rerank
        if ("COMBINED".equals(mode)
                && hasPreferences(plan)
                && !hasHardFilters(plan)
                && !hasSemanticFilters(plan)
                && !hasCriteria(plan)
                && visibleProductCount > SYNC_JUDGE_MAX_POOL) {
            log.info("ExecutionPolicy: downgrading COMBINED → PREFERENCE_RERANK (pool={}, preferences only)", visibleProductCount);
            return toPreferenceRerank(plan);
        }

        return plan;
    }

    /** Strip judge from a plan (keep everything else). */
    public SemanticActionPlan stripJudge(SemanticActionPlan plan) {
        return new SemanticActionPlan(
                plan.intent(),
                plan.executionMode(),
                plan.targetProduct(),
                plan.hardFilters(),
                plan.semanticFilters(),
                plan.preferences(),
                plan.exclusions(),
                plan.sort(),
                plan.zeroResultPolicy(),
                plan.criteria(),
                plan.negativeCriteria(),
                null,  // judge = null
                plan.rankingGoal(),
                plan.resultStrategy()
        );
    }

    /** Downgrade a plan to PREFERENCE_RERANK, preserving preferences and stripping judge. */
    private SemanticActionPlan toPreferenceRerank(SemanticActionPlan plan) {
        return new SemanticActionPlan(
                plan.intent(),
                "PREFERENCE_RERANK",
                plan.targetProduct(),
                plan.hardFilters(),
                plan.semanticFilters(),
                plan.preferences(),
                plan.exclusions(),
                plan.sort(),
                plan.zeroResultPolicy(),
                plan.criteria(),
                plan.negativeCriteria(),
                null,  // judge = null
                plan.rankingGoal(),
                plan.resultStrategy()
        );
    }

    private boolean hasJudge(SemanticActionPlan plan) {
        return plan.judge() != null && Boolean.TRUE.equals(plan.judge().required());
    }

    private boolean hasPreferences(SemanticActionPlan plan) {
        return plan.preferences() != null && !plan.preferences().isEmpty();
    }

    private boolean hasHardFilters(SemanticActionPlan plan) {
        return plan.hardFilters() != null && !plan.hardFilters().isEmpty();
    }

    private boolean hasSemanticFilters(SemanticActionPlan plan) {
        return plan.semanticFilters() != null && !plan.semanticFilters().isEmpty();
    }

    private boolean hasCriteria(SemanticActionPlan plan) {
        return plan.criteria() != null && !plan.criteria().isEmpty();
    }
}
