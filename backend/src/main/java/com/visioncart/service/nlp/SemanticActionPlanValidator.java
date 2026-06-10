package com.visioncart.service.nlp;

import com.visioncart.api.dto.SemanticActionPlan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SemanticActionPlanValidator {

    private static final Set<String> ALLOWED_INTENTS = Set.of(
            "filter_current_results", "new_search", "correction", "chat");
    private static final Set<String> ALLOWED_EXECUTION_MODES = Set.of(
            "STRICT_FILTER", "SEMANTIC_SCREENING", "PREFERENCE_RERANK",
            "LLM_RERANK", "COMBINED", "EXCLUSION", "NEW_SEARCH", "CORRECTION");
    private static final Set<String> ALLOWED_ZERO_RESULT_POLICIES = Set.of(
            "KEEP_PREVIOUS_RESULTS", "KEEP_AS_SECONDARY", "EXCLUDE", "SHOW_WARNING");
    private static final Set<String> ALLOWED_HARD_FIELDS = Set.of(
            "price", "platform", "color", "brand", "rating", "self_operated");
    private static final Set<String> ALLOWED_HARD_OPERATORS = Set.of(
            "=", "<=", ">=", "<", ">", "in", "equals", "le", "ge", "lt", "gt");
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "price", "sales", "rating", "reviews", "review_quality",
            "rating_desc", "shop_trust", "seller_trust", "relevance", "value_score");
    private static final Set<String> ALLOWED_SORT_ORDERS = Set.of("asc", "desc");
    private static final Set<String> ALLOWED_MATCH_LOGIC = Set.of("EVIDENCE_OR_SCORE");
    private static final Set<String> ALLOWED_SIGNAL_TYPES = Set.of("evidence_or_score", "preference");
    private static final Set<String> ALLOWED_SIGNAL_FIELDS = Set.of(
            "title", "tags", "all_text", "allText", "energy_wh", "capacity_mah",
            "voltage", "wattage", "ip_rating", "product_role", "certifications",
            "brand", "price", "rating", "sales", "shop_name");
    private static final Set<String> ALLOWED_SIGNAL_OPERATORS = Set.of(
            "contains", "contains_any", "contains_all", "regex", "equals",
            "<=", ">=", "<", ">", "le", "ge", "lt", "gt", "formula_lte", "formula_gte");
    private static final Set<String> ALLOWED_FORMULAS = Set.of(
            "ENERGY_WH_FROM_MAH_VOLTAGE", "ENERGY_WH_FROM_MAH_DEFAULT");
    private static final Set<String> ALLOWED_BUCKETS = Set.of(
            "EXPLICIT_MATCH", "INFERRED_MATCH", "UNCERTAIN_RELATED", "REJECTED");
    private static final Set<String> ALLOWED_UNKNOWN_POLICIES = Set.of(
            "KEEP_AS_SECONDARY", "EXCLUDE", "SHOW_WARNING", "KEEP_PREVIOUS_RESULTS");
    private static final Set<String> ALLOWED_NEGATIVE_ACTIONS = Set.of("reject", "keep_secondary");

    private static final int MAX_STRING_LENGTH = 100;
    private static final int MAX_LONG_STRING_LENGTH = 300;
    private static final int MAX_ITEMS = 20;
    private static final int MAX_SYNC_JUDGE_CANDIDATES = 12;
    private static final double MAX_PRICE = 10_000_000;

    private SemanticActionPlanValidator() {
    }

    public static SemanticActionPlan validate(SemanticActionPlan plan) {
        if (plan == null) {
            return null;
        }

        String intent = clean(plan.intent());
        if (!ALLOWED_INTENTS.contains(intent)) {
            return null;
        }

        String executionMode = clean(plan.executionMode());
        if (!ALLOWED_EXECUTION_MODES.contains(executionMode)) {
            return null;
        }

        String zeroResultPolicy = clean(plan.zeroResultPolicy());
        if (!ALLOWED_ZERO_RESULT_POLICIES.contains(zeroResultPolicy)) {
            zeroResultPolicy = "KEEP_PREVIOUS_RESULTS";
        }

        return new SemanticActionPlan(
                intent,
                executionMode,
                truncate(plan.targetProduct()),
                emptyToNull(validateHardFilters(plan.hardFilters())),
                emptyToNull(validateSemanticFilters(plan.semanticFilters())),
                emptyToNull(validatePreferences(plan.preferences())),
                emptyToNull(validateExclusions(plan.exclusions())),
                validateSort(plan.sort()),
                zeroResultPolicy,
                emptyToNull(validateCriteria(plan.criteria())),
                emptyToNull(validateNegativeCriteria(plan.negativeCriteria())),
                validateJudge(plan.judge()),
                truncate(plan.rankingGoal(), MAX_LONG_STRING_LENGTH),
                validateResultStrategy(plan.resultStrategy())
        );
    }

    private static List<SemanticActionPlan.HardFilter> validateHardFilters(
            List<SemanticActionPlan.HardFilter> filters) {
        List<SemanticActionPlan.HardFilter> result = new ArrayList<>();
        if (filters == null) {
            return result;
        }
        for (SemanticActionPlan.HardFilter filter : filters.stream().limit(MAX_ITEMS).toList()) {
            if (filter == null) {
                continue;
            }
            String field = cleanLower(filter.field());
            if (!ALLOWED_HARD_FIELDS.contains(field)) {
                continue;
            }
            String operator = clean(filter.operator());
            if (operator == null || operator.isBlank()) {
                operator = "equals";
            }
            if (!ALLOWED_HARD_OPERATORS.contains(operator)) {
                continue;
            }
            Object value = sanitizeValue(filter.value());
            if (!isSafeHardValue(field, value)) {
                continue;
            }
            result.add(new SemanticActionPlan.HardFilter(field, operator, value));
        }
        return result;
    }

    private static List<SemanticActionPlan.SemanticFilter> validateSemanticFilters(
            List<SemanticActionPlan.SemanticFilter> filters) {
        List<SemanticActionPlan.SemanticFilter> result = new ArrayList<>();
        if (filters == null) {
            return result;
        }
        for (SemanticActionPlan.SemanticFilter filter : filters.stream().limit(MAX_ITEMS).toList()) {
            if (filter == null) {
                continue;
            }
            String code = sanitizeCode(filter.code());
            if (code == null) {
                continue;
            }
            String matchLogic = clean(filter.matchLogic());
            if (!ALLOWED_MATCH_LOGIC.contains(matchLogic)) {
                matchLogic = "EVIDENCE_OR_SCORE";
            }
            String unknownPolicy = clean(filter.unknownPolicy());
            if (!ALLOWED_UNKNOWN_POLICIES.contains(unknownPolicy)) {
                unknownPolicy = "KEEP_AS_SECONDARY";
            }
            result.add(new SemanticActionPlan.SemanticFilter(
                    code,
                    truncate(filter.userMeaning()),
                    matchLogic,
                    emptyToNull(validateEvidenceRules(filter.positiveEvidence())),
                    emptyToNull(validateEvidenceRules(filter.negativeEvidence())),
                    unknownPolicy));
        }
        return result;
    }

    private static List<SemanticActionPlan.EvidenceRule> validateEvidenceRules(
            List<SemanticActionPlan.EvidenceRule> rules) {
        List<SemanticActionPlan.EvidenceRule> result = new ArrayList<>();
        if (rules == null) {
            return result;
        }
        for (SemanticActionPlan.EvidenceRule rule : rules.stream().limit(MAX_ITEMS).toList()) {
            SemanticActionPlan.EvidenceRule validated = validateEvidenceRule(rule);
            if (validated != null) {
                result.add(validated);
            }
        }
        return result;
    }

    private static SemanticActionPlan.EvidenceRule validateEvidenceRule(SemanticActionPlan.EvidenceRule rule) {
        if (rule == null) {
            return null;
        }
        String operator = clean(rule.operator());
        if (!ALLOWED_SIGNAL_OPERATORS.contains(operator)) {
            return null;
        }
        String formula = clean(rule.formula());
        if (formula != null && !ALLOWED_FORMULAS.contains(formula)) {
            return null;
        }
        List<String> fields = validateSignalFields(rule.fields());
        if (fields.isEmpty() && formula == null) {
            return null;
        }
        String bucket = clean(rule.bucket());
        if (!ALLOWED_BUCKETS.contains(bucket)) {
            bucket = "UNCERTAIN_RELATED";
        }
        String action = cleanLower(rule.action());
        if (action != null && !ALLOWED_NEGATIVE_ACTIONS.contains(action) && !"accept".equals(action)) {
            action = null;
        }
        return new SemanticActionPlan.EvidenceRule(
                sanitizeCode(rule.id()),
                truncate(rule.description(), MAX_LONG_STRING_LENGTH),
                fields,
                operator,
                sanitizeValue(rule.value()),
                emptyToNull(truncateList(rule.values())),
                formula,
                clamp01(rule.score()),
                bucket,
                action,
                truncate(rule.warning(), MAX_LONG_STRING_LENGTH));
    }

    private static List<SemanticActionPlan.PreferenceRule> validatePreferences(
            List<SemanticActionPlan.PreferenceRule> preferences) {
        List<SemanticActionPlan.PreferenceRule> result = new ArrayList<>();
        if (preferences == null) {
            return result;
        }
        for (SemanticActionPlan.PreferenceRule preference : preferences.stream().limit(MAX_ITEMS).toList()) {
            if (preference == null) {
                continue;
            }
            String code = sanitizeCode(preference.code());
            if (code == null) {
                continue;
            }
            result.add(new SemanticActionPlan.PreferenceRule(
                    code,
                    truncate(preference.userMeaning()),
                    clamp01(preference.weight())));
        }
        return result;
    }

    private static List<SemanticActionPlan.ExclusionRule> validateExclusions(
            List<SemanticActionPlan.ExclusionRule> exclusions) {
        List<SemanticActionPlan.ExclusionRule> result = new ArrayList<>();
        if (exclusions == null) {
            return result;
        }
        for (SemanticActionPlan.ExclusionRule exclusion : exclusions.stream().limit(MAX_ITEMS).toList()) {
            if (exclusion == null) {
                continue;
            }
            String code = sanitizeCode(exclusion.code());
            if (code == null) {
                continue;
            }
            result.add(new SemanticActionPlan.ExclusionRule(
                    code,
                    truncate(exclusion.userMeaning()),
                    emptyToNull(truncateList(exclusion.keywords())),
                    emptyToNull(sanitizeRoleList(exclusion.roles()))));
        }
        return result;
    }

    private static SemanticActionPlan.SortRule validateSort(SemanticActionPlan.SortRule sort) {
        if (sort == null) {
            return null;
        }
        String field = cleanLower(sort.field());
        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            return null;
        }
        String order = cleanLower(sort.order());
        if (!ALLOWED_SORT_ORDERS.contains(order)) {
            order = "desc";
        }
        return new SemanticActionPlan.SortRule(field, order);
    }

    private static List<SemanticActionPlan.DynamicCriterion> validateCriteria(
            List<SemanticActionPlan.DynamicCriterion> criteria) {
        List<SemanticActionPlan.DynamicCriterion> result = new ArrayList<>();
        if (criteria == null) {
            return result;
        }
        for (SemanticActionPlan.DynamicCriterion criterion : criteria.stream().limit(MAX_ITEMS).toList()) {
            if (criterion == null) {
                continue;
            }
            String name = truncate(criterion.name());
            if (name == null) {
                continue;
            }
            String type = cleanLower(criterion.type());
            if (!ALLOWED_SIGNAL_TYPES.contains(type)) {
                type = "evidence_or_score";
            }
            String unknownPolicy = clean(criterion.unknownPolicy());
            if (unknownPolicy != null && !ALLOWED_UNKNOWN_POLICIES.contains(unknownPolicy)) {
                unknownPolicy = null;
            }
            result.add(new SemanticActionPlan.DynamicCriterion(
                    name,
                    type,
                    criterion.required(),
                    clampNullable01(criterion.weight()),
                    emptyToNull(validateSignals(criterion.signals())),
                    unknownPolicy,
                    truncate(criterion.userMeaning())));
        }
        return result;
    }

    private static List<SemanticActionPlan.NegativeCriterion> validateNegativeCriteria(
            List<SemanticActionPlan.NegativeCriterion> criteria) {
        List<SemanticActionPlan.NegativeCriterion> result = new ArrayList<>();
        if (criteria == null) {
            return result;
        }
        for (SemanticActionPlan.NegativeCriterion criterion : criteria.stream().limit(MAX_ITEMS).toList()) {
            if (criterion == null) {
                continue;
            }
            String name = truncate(criterion.name());
            if (name == null) {
                continue;
            }
            String action = cleanLower(criterion.action());
            if (!ALLOWED_NEGATIVE_ACTIONS.contains(action)) {
                action = "reject";
            }
            result.add(new SemanticActionPlan.NegativeCriterion(
                    name,
                    action,
                    emptyToNull(validateSignals(criterion.signals())),
                    truncate(criterion.userMeaning())));
        }
        return result;
    }

    private static List<SemanticActionPlan.CriterionSignal> validateSignals(
            List<SemanticActionPlan.CriterionSignal> signals) {
        List<SemanticActionPlan.CriterionSignal> result = new ArrayList<>();
        if (signals == null) {
            return result;
        }
        for (SemanticActionPlan.CriterionSignal signal : signals.stream().limit(MAX_ITEMS).toList()) {
            if (signal == null) {
                continue;
            }
            String field = clean(signal.field());
            if (field != null && !ALLOWED_SIGNAL_FIELDS.contains(field)) {
                continue;
            }
            String operator = clean(signal.operator());
            if (operator != null && !ALLOWED_SIGNAL_OPERATORS.contains(operator)) {
                continue;
            }
            String formula = clean(signal.formula());
            if (formula != null && !ALLOWED_FORMULAS.contains(formula)) {
                continue;
            }
            Object value = sanitizeValue(signal.value());
            List<String> values = truncateList(signal.values());
            if (field == null && operator == null && value == null && values.isEmpty() && formula == null) {
                continue;
            }
            String bucket = clean(signal.bucket());
            if (bucket != null && !ALLOWED_BUCKETS.contains(bucket)) {
                bucket = null;
            }
            result.add(new SemanticActionPlan.CriterionSignal(
                    sanitizeCode(signal.kind()),
                    field,
                    operator,
                    value,
                    emptyToNull(values),
                    formula,
                    clampNullable01(signal.score()),
                    truncate(signal.description(), MAX_LONG_STRING_LENGTH),
                    bucket));
        }
        return result;
    }

    private static SemanticActionPlan.JudgePlan validateJudge(SemanticActionPlan.JudgePlan judge) {
        if (judge == null) {
            return null;
        }
        Boolean required = judge.required();
        return new SemanticActionPlan.JudgePlan(
                required,
                truncate(judge.userMeaning(), MAX_LONG_STRING_LENGTH),
                emptyToNull(truncateList(judge.positiveSignals())),
                emptyToNull(truncateList(judge.negativeSignals())),
                clampLimit(judge.candidateLimit()),
                clampLimit(judge.returnLimit()),
                clampNullable01(judge.minScore()));
    }

    private static SemanticActionPlan.ResultStrategy validateResultStrategy(
            SemanticActionPlan.ResultStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        String unknownPolicy = clean(strategy.unknownPolicy());
        if (unknownPolicy != null && !ALLOWED_UNKNOWN_POLICIES.contains(unknownPolicy)) {
            unknownPolicy = null;
        }
        String fallbackPolicy = clean(strategy.fallbackPolicy());
        if (fallbackPolicy != null && !ALLOWED_ZERO_RESULT_POLICIES.contains(fallbackPolicy)) {
            fallbackPolicy = null;
        }
        if (unknownPolicy == null && fallbackPolicy == null && strategy.preservePreviousOnEmpty() == null) {
            return null;
        }
        return new SemanticActionPlan.ResultStrategy(
                unknownPolicy, fallbackPolicy, strategy.preservePreviousOnEmpty());
    }

    private static boolean isSafeHardValue(String field, Object value) {
        if ("self_operated".equals(field)) {
            return true;
        }
        if (value == null) {
            return false;
        }
        if ("price".equals(field)) {
            Double number = toDouble(value);
            return number != null && number >= 0 && number <= MAX_PRICE;
        }
        if ("rating".equals(field)) {
            Double number = toDouble(value);
            return number != null && number >= 0 && number <= 5;
        }
        return true;
    }

    private static List<String> validateSignalFields(List<String> fields) {
        if (fields == null) {
            return List.of();
        }
        return fields.stream()
                .limit(MAX_ITEMS)
                .map(SemanticActionPlanValidator::clean)
                .filter(field -> field != null && ALLOWED_SIGNAL_FIELDS.contains(field))
                .distinct()
                .toList();
    }

    private static List<String> truncateList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .limit(MAX_ITEMS)
                .map(SemanticActionPlanValidator::truncate)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> sanitizeRoleList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .limit(MAX_ITEMS)
                .map(SemanticActionPlanValidator::cleanLower)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private static Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return truncate(text);
        }
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            return Double.isFinite(numeric) ? numeric : null;
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .limit(MAX_ITEMS)
                    .map(item -> item == null ? null : truncate(String.valueOf(item)))
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .toList();
        }
        return truncate(String.valueOf(value));
    }

    private static String sanitizeCode(String value) {
        String text = clean(value);
        if (text == null) {
            return null;
        }
        String sanitized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\-]+", "_")
                .replaceAll("^_+|_+$", "");
        return sanitized.isBlank() ? null : truncate(sanitized);
    }

    private static String truncate(String value) {
        return truncate(value, MAX_STRING_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        String text = clean(value);
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isBlank() ? null : text;
    }

    private static String cleanLower(String value) {
        String text = clean(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    private static Double clampNullable01(Double value) {
        return value == null || !Double.isFinite(value) ? null : Math.max(0.0, Math.min(1.0, value));
    }

    private static Integer clampLimit(Integer value) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            return 1;
        }
        return Math.min(value, MAX_SYNC_JUDGE_CANDIDATES);
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static <T> List<T> emptyToNull(List<T> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return new ArrayList<>(new LinkedHashSet<>(values));
    }
}
