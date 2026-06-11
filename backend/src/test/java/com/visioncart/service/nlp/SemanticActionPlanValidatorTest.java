package com.visioncart.service.nlp;

import com.visioncart.api.dto.SemanticActionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticActionPlanValidatorTest {

    @Test
    void validPlanPassesThrough() {
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results",
                "COMBINED",
                "power bank",
                List.of(new SemanticActionPlan.HardFilter("price", "<=", 100)),
                List.of(new SemanticActionPlan.SemanticFilter(
                        "airplane_allowed",
                        "airplane safe",
                        "EVIDENCE_OR_SCORE",
                        null,
                        null,
                        "KEEP_AS_SECONDARY")),
                List.of(new SemanticActionPlan.PreferenceRule("lightweight", "portable", 0.8)),
                List.of(new SemanticActionPlan.ExclusionRule("accessory", "not accessory",
                        List.of("case"), List.of("accessory"))),
                new SemanticActionPlan.SortRule("price", "asc"),
                "KEEP_PREVIOUS_RESULTS",
                List.of(new SemanticActionPlan.DynamicCriterion(
                        "portable",
                        "evidence_or_score",
                        false,
                        0.6,
                        List.of(new SemanticActionPlan.CriterionSignal(
                                "keyword", "all_text", "contains_any", null,
                                List.of("mini"), null, 0.9, "mini keyword", "EXPLICIT_MATCH")),
                        "KEEP_AS_SECONDARY",
                        "portable")),
                null,
                new SemanticActionPlan.JudgePlan(true, "best gift",
                        List.of("nice package"), List.of("accessory"), 8, 5, 0.35),
                "rank by gift fit",
                new SemanticActionPlan.ResultStrategy("KEEP_AS_SECONDARY", "KEEP_PREVIOUS_RESULTS", true)
        );

        SemanticActionPlan result = SemanticActionPlanValidator.validate(plan);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("filter_current_results");
        assertThat(result.executionMode()).isEqualTo("COMBINED");
        assertThat(result.hardFilters()).hasSize(1);
        assertThat(result.semanticFilters()).hasSize(1);
        assertThat(result.preferences()).hasSize(1);
        assertThat(result.exclusions()).hasSize(1);
        assertThat(result.sort().field()).isEqualTo("price");
        assertThat(result.criteria()).hasSize(1);
        assertThat(result.judge().candidateLimit()).isEqualTo(8);
    }

    @Test
    void attributeHardFilterPassesThroughButUnsafeAttributesAreDropped() {
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results",
                "STRICT_FILTER",
                "mouse",
                List.of(
                        new SemanticActionPlan.HardFilter("attribute:类型", "EQUALS", "有线"),
                        new SemanticActionPlan.HardFilter("attribute:<script>", "equals", "bad"),
                        new SemanticActionPlan.HardFilter("attribute:", "equals", "bad"),
                        new SemanticActionPlan.HardFilter("attribute:name", "equals", "<script>bad</script>"),
                        new SemanticActionPlan.HardFilter("attribute:类型", "equals", true),
                        new SemanticActionPlan.HardFilter("unknown_field", "equals", "x")
                ),
                null, null, null, null, "KEEP_PREVIOUS_RESULTS");

        SemanticActionPlan result = SemanticActionPlanValidator.validate(plan);

        assertThat(result).isNotNull();
        assertThat(result.hardFilters()).hasSize(1);
        assertThat(result.hardFilters().get(0).field()).isEqualTo("attribute:类型");
        assertThat(result.hardFilters().get(0).operator()).isEqualTo("equals");
        assertThat(result.hardFilters().get(0).value()).isEqualTo("有线");
    }

    @Test
    void diverseObjectiveAttributeHardFiltersAreAllowed() {
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results",
                "STRICT_FILTER",
                "product",
                List.of(
                        new SemanticActionPlan.HardFilter("attribute:\u8fde\u63a5\u65b9\u5f0f", "equals", "\u84dd\u7259"),
                        new SemanticActionPlan.HardFilter("attribute:\u63a5\u53e3", "equals", "Type-C"),
                        new SemanticActionPlan.HardFilter("attribute:\u6750\u8d28", "equals", "\u7845\u80f6"),
                        new SemanticActionPlan.HardFilter("attribute:\u9002\u914d\u578b\u53f7", "equals", "iPhone15"),
                        new SemanticActionPlan.HardFilter("attribute:\u6b3e\u5f0f", "in",
                                List.of("\u900f\u660e", "\u78e8\u7802"))
                ),
                null, null, null, null, "KEEP_PREVIOUS_RESULTS");

        SemanticActionPlan result = SemanticActionPlanValidator.validate(plan);

        assertThat(result).isNotNull();
        assertThat(result.hardFilters()).hasSize(5);
        assertThat(result.hardFilters()).extracting(SemanticActionPlan.HardFilter::field)
                .containsExactly(
                        "attribute:\u8fde\u63a5\u65b9\u5f0f",
                        "attribute:\u63a5\u53e3",
                        "attribute:\u6750\u8d28",
                        "attribute:\u9002\u914d\u578b\u53f7",
                        "attribute:\u6b3e\u5f0f");
    }

    @Test
    void invalidCoreFieldsReturnNull() {
        SemanticActionPlan badIntent = new SemanticActionPlan(
                "delete_everything", "STRICT_FILTER", null,
                null, null, null, null, null, "KEEP_PREVIOUS_RESULTS");
        SemanticActionPlan badMode = new SemanticActionPlan(
                "filter_current_results", "RUN_CODE", null,
                null, null, null, null, null, "KEEP_PREVIOUS_RESULTS");

        assertThat(SemanticActionPlanValidator.validate(badIntent)).isNull();
        assertThat(SemanticActionPlanValidator.validate(badMode)).isNull();
    }

    @Test
    void invalidChildFieldsAreDroppedAndNumbersAreClamped() {
        SemanticActionPlan plan = new SemanticActionPlan(
                "filter_current_results",
                "LLM_RERANK",
                "phone",
                List.of(
                        new SemanticActionPlan.HardFilter("price", "<=", 99),
                        new SemanticActionPlan.HardFilter("price", "script", 99),
                        new SemanticActionPlan.HardFilter("unknown_field", "equals", "x"),
                        new SemanticActionPlan.HardFilter("rating", ">=", 9)
                ),
                null,
                List.of(new SemanticActionPlan.PreferenceRule("gift", "gift fit", 2.0)),
                null,
                new SemanticActionPlan.SortRule("script", "sideways"),
                "DROP_RESULTS",
                List.of(new SemanticActionPlan.DynamicCriterion(
                        "gift",
                        "unknown_type",
                        false,
                        -0.5,
                        List.of(
                                new SemanticActionPlan.CriterionSignal(
                                        "keyword", "all_text", "contains_any", null,
                                        List.of("gift"), null, 2.0, "gift keyword", "BAD_BUCKET"),
                                new SemanticActionPlan.CriterionSignal(
                                        "keyword", "all_text", "eval", "danger",
                                        null, null, 0.5, "bad op", null)
                        ),
                        "BAD_POLICY",
                        "gift fit")),
                null,
                new SemanticActionPlan.JudgePlan(true, "judge it",
                        List.of("positive"), List.of("negative"), 80, 50, -1.0),
                null,
                null
        );

        SemanticActionPlan result = SemanticActionPlanValidator.validate(plan);

        assertThat(result).isNotNull();
        assertThat(result.zeroResultPolicy()).isEqualTo("KEEP_PREVIOUS_RESULTS");
        assertThat(result.hardFilters()).hasSize(1);
        assertThat(result.hardFilters().get(0).field()).isEqualTo("price");
        assertThat(result.sort()).isNull();
        assertThat(result.preferences().get(0).weight()).isEqualTo(1.0);
        assertThat(result.criteria()).hasSize(1);
        assertThat(result.criteria().get(0).type()).isEqualTo("evidence_or_score");
        assertThat(result.criteria().get(0).weight()).isEqualTo(0.0);
        assertThat(result.criteria().get(0).unknownPolicy()).isNull();
        assertThat(result.criteria().get(0).signals()).hasSize(1);
        assertThat(result.criteria().get(0).signals().get(0).score()).isEqualTo(1.0);
        assertThat(result.criteria().get(0).signals().get(0).bucket()).isNull();
        assertThat(result.judge().candidateLimit()).isEqualTo(12);
        assertThat(result.judge().returnLimit()).isEqualTo(12);
        assertThat(result.judge().minScore()).isEqualTo(0.0);
    }
}
