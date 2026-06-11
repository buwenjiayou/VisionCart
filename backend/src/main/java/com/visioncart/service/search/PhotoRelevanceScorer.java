package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Scores how closely a product title matches visual/spec signals extracted from the photo.
 */
final class PhotoRelevanceScorer {

    double score(ProductIntent intent, ProductCard product) {
        if (intent == null || product == null || product.title() == null) {
            return 0.0;
        }
        String title = PowerBankSearchRules.normalize(product.title());
        if (title.isBlank()) {
            return 0.0;
        }

        double score = 0.0;
        boolean powerBank = PowerBankSearchRules.isPowerBank(intent);

        for (String spec : specTerms(intent)) {
            if (title.contains(PowerBankSearchRules.normalize(spec))) {
                score += 4.0;
            }
        }

        for (String phrase : visualPhrases(intent, powerBank)) {
            String normalized = PowerBankSearchRules.normalize(phrase);
            if (normalized.isBlank()) {
                continue;
            }
            if (title.contains(normalized)) {
                score += normalized.length() >= 6 ? 3.0 : 1.5;
            } else if (powerBank && partialVisualMatch(title, normalized)) {
                score += 0.9;
            }
        }

        if (powerBank) {
            PowerBankProductClassifier.Type type = PowerBankProductClassifier.classify(product.title());
            if (type == PowerBankProductClassifier.Type.NON_TARGET) {
                score -= 20.0;
            } else if (type == PowerBankProductClassifier.Type.RELATED_BUT_NOT_TARGET) {
                score -= 3.0;
            } else if (type == PowerBankProductClassifier.Type.FINISHED_POWER_BANK) {
                score += 2.0;
            }
        }
        return score;
    }

    private List<String> specTerms(ProductIntent intent) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addSpecs(terms, intent.model());
        for (String value : safeValues(intent.hardAttributes())) {
            addSpecs(terms, value);
        }
        for (String feature : safeList(intent.featureTerms())) {
            if (PowerBankSearchRules.isStrongSpecOrFeature(feature)) {
                terms.add(feature);
            }
            addSpecs(terms, feature);
        }
        return new ArrayList<>(terms);
    }

    private List<String> visualPhrases(ProductIntent intent, boolean powerBank) {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        for (String value : safeValues(intent.softAttributes())) {
            addVisualPhrase(phrases, value, intent.canonicalProduct(), powerBank);
        }
        for (String feature : safeList(intent.featureTerms())) {
            if (!PowerBankSearchRules.isStrongSpecOrFeature(feature)) {
                addVisualPhrase(phrases, feature, intent.canonicalProduct(), powerBank);
            }
        }
        return new ArrayList<>(phrases);
    }

    private void addVisualPhrase(LinkedHashSet<String> phrases, String value,
                                 String canonicalProduct, boolean powerBank) {
        String phrase = powerBank
                ? PowerBankSearchRules.cleanDescriptor(value, canonicalProduct)
                : SearchTextUtils.useful(value);
        if (!PowerBankSearchRules.isUsefulVisualDescriptor(phrase)) {
            return;
        }
        if (powerBank) {
            phrases.addAll(PowerBankSearchRules.expandVisualDescriptor(phrase));
        } else {
            phrases.add(phrase);
        }
    }

    private void addSpecs(LinkedHashSet<String> terms, String value) {
        terms.addAll(PowerBankSearchRules.extractSpecs(value));
    }

    private boolean partialVisualMatch(String title, String phrase) {
        int matched = 0;
        int total = 0;
        for (String token : splitVisualTokens(phrase)) {
            total++;
            if (title.contains(token)) {
                matched++;
            }
        }
        return total >= 2 && matched >= 2;
    }

    private List<String> splitVisualTokens(String phrase) {
        List<String> tokens = new ArrayList<>();
        if (phrase.contains("透明")) tokens.add("透明");
        if (phrase.contains("外壳")) tokens.add("外壳");
        if (phrase.contains("电路板")) tokens.add("电路板");
        if (phrase.contains("黄色")) tokens.add("黄色");
        if (phrase.contains("led")) tokens.add("led");
        if (phrase.contains("指示灯")) tokens.add("指示灯");
        if (phrase.contains("黄黑")) tokens.add("黄黑");
        return tokens;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<String> safeValues(Map<String, String> values) {
        return values == null ? List.of() : values.values().stream().toList();
    }
}
