package com.visioncart.service.search;

import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PowerBankSearchRules {

    private static final Pattern SPEC_PATTERN = Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?\\s?(?:mah|ah|wh|w))");

    private static final List<String> POWER_BANK_PRODUCTS = List.of(
            "充电宝", "移动电源", "应急电源", "户外电源", "便携充电器");

    private static final List<String> STRONG_FEATURES = List.of(
            "快充", "大容量", "pd", "qc", "磁吸", "无线充电", "容量");

    private static final Set<String> WEAK_VISUAL_TERMS = Set.of(
            "透明", "黄色", "黄黑", "黑色", "led", "指示灯", "灯");

    private PowerBankSearchRules() {
    }

    static boolean isPowerBank(ProductIntent intent) {
        if (intent == null) return false;
        String family = StringUtils.defaultString(intent.productFamily());
        String product = StringUtils.defaultString(intent.canonicalProduct());
        return "power_bank".equals(family)
                || POWER_BANK_PRODUCTS.stream().anyMatch(term -> contains(product, term));
    }

    static boolean isPowerBank(SearchIntent intent) {
        if (intent == null) return false;
        if ("powerbank".equals(intent.mainCategoryCode())) return true;
        String text = String.join(" ",
                StringUtils.defaultString(intent.category()),
                StringUtils.defaultString(intent.coreProduct()),
                String.join(" ", intent.categoryChain()),
                String.join(" ", intent.keywords()));
        return POWER_BANK_PRODUCTS.stream().anyMatch(term -> contains(text, term));
    }

    static boolean shouldRejectComponentDominant(ProductIntent intent, String title) {
        return isPowerBank(intent) && PowerBankProductClassifier.classify(title) == PowerBankProductClassifier.Type.NON_TARGET;
    }

    static boolean shouldRejectComponentDominant(SearchIntent intent, String title) {
        return isPowerBank(intent) && PowerBankProductClassifier.classify(title) == PowerBankProductClassifier.Type.NON_TARGET;
    }

    static boolean shouldRejectLampDominant(ProductIntent intent, String title, BigDecimal price) {
        return shouldRejectComponentDominant(intent, title);
    }

    static boolean hasStrongPowerBankSignal(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) return false;
        if (!extractSpecs(normalized).isEmpty()) return true;
        return STRONG_FEATURES.stream().anyMatch(normalized::contains);
    }

    static boolean hasFinishedPowerBankSignal(String text) {
        return PowerBankProductClassifier.isFinishedProduct(text);
    }

    static List<String> extractSpecs(String text) {
        String value = SearchTextUtils.useful(text);
        if (value.isBlank()) return List.of();
        Matcher matcher = SPEC_PATTERN.matcher(value);
        LinkedHashSet<String> specs = new LinkedHashSet<>();
        while (matcher.find()) {
            specs.add(matcher.group(1).replaceAll("\\s+", ""));
        }
        return new ArrayList<>(specs);
    }

    static boolean isStrongSpecOrFeature(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) return false;
        if (!extractSpecs(text).isEmpty()) return true;
        return STRONG_FEATURES.stream().anyMatch(normalized::contains);
    }

    static String cleanDescriptor(String value, String canonicalProduct) {
        String cleaned = SearchTextUtils.useful(value);
        if (cleaned.isBlank()) return "";
        for (String product : POWER_BANK_PRODUCTS) {
            cleaned = cleaned.replace(product, "");
        }
        if (canonicalProduct != null && !canonicalProduct.isBlank()) {
            cleaned = cleaned.replace(canonicalProduct, "");
        }
        cleaned = cleaned.replaceAll("[,，;；|/]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return SearchTextUtils.useful(cleaned);
    }

    static boolean isUsefulVisualDescriptor(String value) {
        String cleaned = SearchTextUtils.useful(value);
        if (cleaned.isBlank()) return false;
        String normalized = normalize(cleaned);
        if (normalized.length() < 3) return false;
        if (WEAK_VISUAL_TERMS.contains(normalized)) return false;
        return true;
    }

    static boolean isSafeRetrievalVisual(String value) {
        return PowerBankProductClassifier.isSafeRetrievalVisual(value);
    }

    static boolean isUnsafePowerBankRetrievalTerm(String value) {
        return PowerBankProductClassifier.isUnsafeRetrievalTerm(value);
    }

    static List<String> expandVisualDescriptor(String value) {
        String cleaned = SearchTextUtils.useful(value);
        if (cleaned.isBlank()) return List.of();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(cleaned);
        String normalized = normalize(cleaned);
        if (normalized.contains("露电路板") || normalized.contains("裸露电路板")) {
            terms.add("露电路板");
        }
        if (normalized.contains("透明外壳")) {
            terms.add("透明外壳");
        }
        if (normalized.contains("电路板")) {
            terms.add("电路板");
        }
        if (normalized.contains("黄色led指示灯")) {
            terms.add("黄色LED指示灯");
        } else if (normalized.contains("led指示灯")) {
            terms.add("LED指示灯");
        }
        if (normalized.contains("黄黑")) {
            terms.add("黄黑配色");
        }
        return terms.stream()
                .map(SearchTextUtils::useful)
                .filter(term -> !term.isBlank())
                .toList();
    }

    private static boolean containsAny(String normalizedText, List<String> terms) {
        for (String term : terms) {
            if (normalizedText.contains(normalize(term))) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String text, String term) {
        return normalize(text).contains(normalize(term));
    }

    static String normalize(String value) {
        return SearchTextUtils.normalizeForMatch(value).toLowerCase(Locale.ROOT);
    }
}
