package com.visioncart.service.filter;

import com.visioncart.service.filter.capability.CapabilitySynonymRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Produces a FilterPlan from parsed FilterClauses.
 * Decides execution mode, thresholds, and zero-result policies based on clause types.
 */
@Component
public class FilterPlanner {

    private static final Logger log = LoggerFactory.getLogger(FilterPlanner.class);

    private static final int DEFAULT_MIN_RESULT_THRESHOLD = 3;

    private final CapabilitySynonymRegistry capabilityRegistry;

    public FilterPlanner() {
        this.capabilityRegistry = new CapabilitySynonymRegistry();
    }

    public FilterPlanner(CapabilitySynonymRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    /**
     * Generate an execution plan from the given clauses.
     */
    public FilterPlan plan(List<FilterClause> clauses, String explanation) {
        if (clauses == null || clauses.isEmpty()) {
            return FilterPlan.rerankOnly(generatePlanId(), List.of());
        }

        boolean hasStructured = clauses.stream().anyMatch(c -> c.type() == FilterClause.ClauseType.STRUCTURED);
        boolean hasCapability = clauses.stream().anyMatch(c -> c.type() == FilterClause.ClauseType.CAPABILITY);
        boolean hasPreference = clauses.stream().anyMatch(c -> c.type() == FilterClause.ClauseType.PREFERENCE);
        boolean hasExclusion = clauses.stream().anyMatch(c -> c.type() == FilterClause.ClauseType.EXCLUSION);
        boolean hasAttribute = clauses.stream().anyMatch(c -> c.type() == FilterClause.ClauseType.ATTRIBUTE);

        FilterPlan.ExecutionMode mode;
        FilterPlan.ZeroResultPolicy zeroPolicy;
        int minThreshold;

        if (hasCapability || hasExclusion) {
            // Capability or exclusion filters need trial execution
            mode = FilterPlan.ExecutionMode.TRIAL_FIRST;
            zeroPolicy = FilterPlan.ZeroResultPolicy.DO_NOT_COMMIT;
            minThreshold = DEFAULT_MIN_RESULT_THRESHOLD;
        } else if (hasStructured && !hasPreference) {
            // Pure structured filters can be applied directly
            mode = FilterPlan.ExecutionMode.DIRECT;
            zeroPolicy = FilterPlan.ZeroResultPolicy.COMMIT;
            minThreshold = 0;
        } else if (hasAttribute && !hasCapability) {
            // Attribute filters with trial
            mode = FilterPlan.ExecutionMode.TRIAL_FIRST;
            zeroPolicy = FilterPlan.ZeroResultPolicy.WARN_SPARSE;
            minThreshold = 2;
        } else if (hasPreference && !hasStructured && !hasAttribute && !hasCapability) {
            // Pure preference = rerank only
            mode = FilterPlan.ExecutionMode.RERANK_ONLY;
            zeroPolicy = FilterPlan.ZeroResultPolicy.COMMIT;
            minThreshold = 0;
        } else {
            // Mixed: trial first
            mode = FilterPlan.ExecutionMode.TRIAL_FIRST;
            zeroPolicy = FilterPlan.ZeroResultPolicy.DO_NOT_COMMIT;
            minThreshold = DEFAULT_MIN_RESULT_THRESHOLD;
        }

        FilterPlan plan = new FilterPlan(generatePlanId(), clauses, mode, minThreshold, zeroPolicy, explanation);
        log.info("FilterPlanner: planId={}, mode={}, clauses={}, zeroPolicy={}, minThreshold={}",
                plan.planId(), mode, clauses.size(), zeroPolicy, minThreshold);
        return plan;
    }

    /**
     * Classify raw NLP output clauses and assign proper apply modes.
     * This is the second pass after LLM produces initial clause definitions.
     */
    public List<FilterClause> classifyClauses(List<FilterClause> rawClauses) {
        List<FilterClause> classified = new ArrayList<>();
        for (FilterClause clause : rawClauses) {
            classified.add(classifySingle(clause));
        }
        return classified;
    }

    private FilterClause classifySingle(FilterClause clause) {
        // If already properly typed, keep it
        if (clause.applyMode() != null && clause.type() != null) {
            return clause;
        }

        String field = clause.field() != null ? clause.field().toLowerCase() : "";
        String raw = clause.rawText() != null ? clause.rawText().toLowerCase() : "";

        // Detect capability patterns
        if (isCapabilityField(field) || isCapabilityText(raw)) {
            return FilterClause.capability(
                    clause.id(), clause.rawText(), clause.field(), clause.confidence());
        }

        // Detect preference patterns
        if (isPreferenceText(raw)) {
            return FilterClause.preference(clause.id(), clause.rawText(), clause.field());
        }

        // Detect exclusion patterns
        if (raw.startsWith("不要") || raw.startsWith("排除") || raw.startsWith("不要") ||
                raw.contains("exclude") || raw.contains("不要")) {
            return FilterClause.exclusion(clause.id(), clause.rawText(), clause.field(), clause.value());
        }

        // Default to the original clause
        return clause;
    }

    private boolean isCapabilityField(String field) {
        // Check if field is a known capability code (not preference)
        return capabilityRegistry.isCapabilityCode(field);
    }

    private boolean isCapabilityText(String text) {
        // Use centralized registry to detect capability keywords
        String code = capabilityRegistry.detectCapability(text);
        return code != null && capabilityRegistry.isCapabilityCode(code);
    }

    private boolean isPreferenceText(String text) {
        // Use centralized registry to detect preference keywords
        String code = capabilityRegistry.detectCapability(text);
        return code != null && capabilityRegistry.isPreferenceCode(code);
    }

    private String generatePlanId() {
        return "plan-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
