package com.visioncart.service.nlp;

import com.visioncart.api.dto.SemanticActionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticPlanConstraintProvenanceGuardTest {

    @Test
    void dropsAttributeTakenOnlyFromRecognitionContext() {
        SemanticActionPlan plan = planWithFilters(
                new SemanticActionPlan.HardFilter("price", "<=", 50),
                new SemanticActionPlan.HardFilter("attribute:\u7c7b\u578b", "equals", "\u6709\u7ebf"));

        SemanticActionPlan guarded = SemanticPlanConstraintProvenanceGuard.enforce(
                plan,
                "\u6211\u8981\u6bd4\u8f83\u9002\u5408\u5e74\u8f7b\u4eba\u7684\u6b3e\u5f0f",
                "\u6211\u8981\u4f4e\u4e8e50\u7684");

        assertThat(guarded.hardFilters()).extracting(SemanticActionPlan.HardFilter::field)
                .containsExactly("price");
    }

    @Test
    void keepsAttributeMentionedInCurrentInput() {
        SemanticActionPlan plan = planWithFilters(
                new SemanticActionPlan.HardFilter("price", "<=", 50),
                new SemanticActionPlan.HardFilter("attribute:\u7c7b\u578b", "equals", "\u6709\u7ebf"));

        SemanticActionPlan guarded = SemanticPlanConstraintProvenanceGuard.enforce(
                plan,
                "\u6211\u8981\u6709\u7ebf\u7684",
                "\u6211\u8981\u4f4e\u4e8e50\u7684");

        assertThat(guarded.hardFilters()).extracting(SemanticActionPlan.HardFilter::field)
                .containsExactly("price", "attribute:\u7c7b\u578b");
    }

    @Test
    void keepsAttributeMentionedInHistoryWhenCurrentAddsPreference() {
        SemanticActionPlan plan = planWithFilters(
                new SemanticActionPlan.HardFilter("price", "<=", 50),
                new SemanticActionPlan.HardFilter("attribute:\u7c7b\u578b", "equals", "\u6709\u7ebf"));

        SemanticActionPlan guarded = SemanticPlanConstraintProvenanceGuard.enforce(
                plan,
                "\u9002\u5408\u5e74\u8f7b\u4eba\u7684\u6b3e\u5f0f",
                "\u6211\u8981\u4f4e\u4e8e50\u7684\n\u6211\u8981\u6709\u7ebf\u7684");

        assertThat(guarded.hardFilters()).extracting(SemanticActionPlan.HardFilter::field)
                .containsExactly("price", "attribute:\u7c7b\u578b");
    }

    @Test
    void currentNegationDropsOldAttributeAndKeepsReplacement() {
        SemanticActionPlan plan = planWithFilters(
                new SemanticActionPlan.HardFilter("attribute:\u7c7b\u578b", "equals", "\u6709\u7ebf"),
                new SemanticActionPlan.HardFilter("attribute:\u7c7b\u578b", "equals", "\u65e0\u7ebf"));

        SemanticActionPlan guarded = SemanticPlanConstraintProvenanceGuard.enforce(
                plan,
                "\u4e0d\u8981\u6709\u7ebf\u4e86\uff0c\u6539\u6210\u65e0\u7ebf",
                "\u6211\u8981\u6709\u7ebf\u7684");

        assertThat(guarded.hardFilters()).hasSize(1);
        assertThat(guarded.hardFilters().get(0).value()).isEqualTo("\u65e0\u7ebf");
    }

    @Test
    void keepsDiverseExplicitAttributes() {
        SemanticActionPlan plan = planWithFilters(
                new SemanticActionPlan.HardFilter("attribute:\u63a5\u53e3", "equals", "Type-C"),
                new SemanticActionPlan.HardFilter("attribute:\u6750\u8d28", "equals", "\u7845\u80f6"),
                new SemanticActionPlan.HardFilter("attribute:\u6b3e\u5f0f", "equals", "\u900f\u660e"));

        SemanticActionPlan guarded = SemanticPlanConstraintProvenanceGuard.enforce(
                plan,
                "\u6211\u8981Type-C\u63a5\u53e3\u3001\u7845\u80f6\u6750\u8d28\u3001\u900f\u660e\u6b3e",
                "");

        assertThat(guarded.hardFilters()).hasSize(3);
    }

    private SemanticActionPlan planWithFilters(SemanticActionPlan.HardFilter... filters) {
        return new SemanticActionPlan(
                "filter_current_results",
                "COMBINED",
                "mouse",
                List.of(filters),
                null,
                List.of(new SemanticActionPlan.PreferenceRule(
                        "appearance", "\u9002\u5408\u5e74\u8f7b\u4eba", 0.8)),
                null,
                null,
                "KEEP_PREVIOUS_RESULTS");
    }
}
