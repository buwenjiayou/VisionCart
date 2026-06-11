package com.visioncart.service.nlp;

import com.visioncart.api.dto.SemanticActionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Guards LLM-generated objective attribute filters against leaking recognition context.
 *
 * The planner receives product/category context such as "wired mouse" to understand the
 * domain. That context is not a user constraint. Attribute hard filters must be grounded in
 * the current utterance or previous successful user utterances.
 */
public final class SemanticPlanConstraintProvenanceGuard {

    private static final Logger log = LoggerFactory.getLogger(SemanticPlanConstraintProvenanceGuard.class);
    private static final Pattern MATCH_IGNORED_CHARS = Pattern.compile("[\\s\\p{Punct}，。、《》“”‘’（）【】]+");
    private static final String ATTRIBUTE_PREFIX = "attribute:";

    private static final Map<String, List<String>> VALUE_ALIASES = Map.ofEntries(
            Map.entry(norm("有线"), normalizedList("有线", "带线", "插线", "usb线", "wired", "cable")),
            Map.entry(norm("无线"), normalizedList("无线", "蓝牙", "2.4g", "2.4ghz", "bluetooth", "wireless")),
            Map.entry(norm("蓝牙"), normalizedList("蓝牙", "bluetooth")),
            Map.entry(norm("Type-C"), normalizedList("typec", "type-c", "usb-c", "usbc")),
            Map.entry(norm("USB-C"), normalizedList("typec", "type-c", "usb-c", "usbc")),
            Map.entry(norm("硅胶"), normalizedList("硅胶", "silicone")),
            Map.entry(norm("透明"), normalizedList("透明", "transparent")),
            Map.entry(norm("磨砂"), normalizedList("磨砂", "matte")),
            Map.entry(norm("电动"), normalizedList("电动", "electric")),
            Map.entry(norm("手动"), normalizedList("手动", "manual"))
    );

    private SemanticPlanConstraintProvenanceGuard() {
    }

    public static SemanticActionPlan enforce(SemanticActionPlan plan, String currentInput, String historyText) {
        if (plan == null || plan.hardFilters() == null || plan.hardFilters().isEmpty()) {
            return plan;
        }

        String currentSource = norm(currentInput);
        String allUserSource = norm(safe(historyText) + " " + safe(currentInput));
        List<SemanticActionPlan.HardFilter> kept = new ArrayList<>();
        boolean changed = false;

        for (SemanticActionPlan.HardFilter filter : plan.hardFilters()) {
            if (filter == null || !isAttributeField(filter.field())) {
                kept.add(filter);
                continue;
            }
            Object groundedValue = groundedAttributeValue(filter.value(), allUserSource, currentSource);
            if (groundedValue == null) {
                changed = true;
                log.info("Dropped ungrounded NLP attribute hard filter: field={}, value={}",
                        filter.field(), filter.value());
                continue;
            }
            if (!Objects.equals(groundedValue, filter.value())) {
                changed = true;
                kept.add(new SemanticActionPlan.HardFilter(filter.field(), filter.operator(), groundedValue));
            } else {
                kept.add(filter);
            }
        }

        if (!changed) {
            return plan;
        }
        return new SemanticActionPlan(
                plan.intent(),
                plan.executionMode(),
                plan.targetProduct(),
                kept.isEmpty() ? null : kept,
                plan.semanticFilters(),
                plan.preferences(),
                plan.exclusions(),
                plan.sort(),
                plan.zeroResultPolicy(),
                plan.criteria(),
                plan.negativeCriteria(),
                plan.judge(),
                plan.rankingGoal(),
                plan.resultStrategy());
    }

    private static Object groundedAttributeValue(Object value, String allUserSource, String currentSource) {
        if (value instanceof List<?> list) {
            List<String> grounded = list.stream()
                    .map(item -> item == null ? null : String.valueOf(item))
                    .filter(item -> item != null && isGrounded(item, allUserSource, currentSource))
                    .distinct()
                    .toList();
            return grounded.isEmpty() ? null : grounded;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return isGrounded(text, allUserSource, currentSource) ? value : null;
    }

    private static boolean isGrounded(String value, String allUserSource, String currentSource) {
        List<String> aliases = aliases(value);
        if (aliases.stream().anyMatch(alias -> isNegated(alias, currentSource))) {
            return false;
        }
        return aliases.stream().anyMatch(allUserSource::contains);
    }

    private static boolean isNegated(String alias, String currentSource) {
        return currentSource.contains("不要" + alias)
                || currentSource.contains("不需要" + alias)
                || currentSource.contains("不用" + alias)
                || currentSource.contains("别要" + alias)
                || currentSource.contains("取消" + alias)
                || currentSource.contains("去掉" + alias)
                || currentSource.contains("不是" + alias);
    }

    private static List<String> aliases(String value) {
        String normalized = norm(value);
        List<String> aliases = VALUE_ALIASES.get(normalized);
        if (aliases == null) {
            return List.of(normalized);
        }
        List<String> merged = new ArrayList<>();
        merged.add(normalized);
        merged.addAll(aliases);
        return merged.stream().filter(text -> text != null && !text.isBlank()).distinct().toList();
    }

    private static boolean isAttributeField(String field) {
        return field != null && field.trim().toLowerCase(Locale.ROOT).startsWith(ATTRIBUTE_PREFIX);
    }

    private static List<String> normalizedList(String... values) {
        List<String> list = new ArrayList<>();
        for (String value : values) {
            list.add(norm(value));
        }
        return list.stream().filter(text -> text != null && !text.isBlank()).distinct().toList();
    }

    private static String norm(String value) {
        if (value == null) {
            return "";
        }
        return MATCH_IGNORED_CHARS.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
