package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * English-only product intent matcher for overseas traffic.
 *
 * <p>This class is intentionally isolated from domestic Chinese intent rules. It is selected by
 * request region, not by platform, so future overseas platforms can reuse the same path without
 * changing domestic filtering behavior.</p>
 */
@Component
public class OverseasEnglishIntentMatcher {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9+.#]+");
    private static final Pattern CAPACITY = Pattern.compile(
            "(?i)\\b(\\d{4,6}\\s?mah|\\d+(?:\\.\\d+)?\\s?ah|\\d+(?:\\.\\d+)?\\s?wh)\\b");

    private static final Map<String, FamilyRules> RULES = new LinkedHashMap<>();

    static {
        RULES.put("mouse", new FamilyRules(
                List.of("wireless mouse", "bluetooth mouse", "gaming mouse", "computer mouse",
                        "optical mouse", "ergonomic mouse", "vertical mouse", "mouse"),
                List.of(),
                List.of("mouse pad", "mousepad", "mouse mat", "desk mat", "mouse skates",
                        "mouse feet", "grip tape", "side grips", "mouse shell", "mouse case",
                        "mouse bungee", "wrist rest"),
                List.of("wireless", "bluetooth", "gaming", "optical", "ergonomic", "vertical",
                        "logitech", "razer", "steelseries", "corsair")));

        RULES.put("power_bank", new FamilyRules(
                List.of("power bank", "powerbank", "portable charger", "portable power bank",
                        "battery pack", "power pack"),
                List.of("portable power station", "solar generator", "jump starter"),
                List.of("battery cell", "18650", "21700", "pcb", "pcba", "circuit board",
                        "module", "boost board", "charger board", "protection board", "diy kit",
                        "repair board", "case shell", "shell case", "empty case", "enclosure",
                        "battery holder", "battery box", "cell holder", "wall charger",
                        "power adapter", "charging cable", "usb cable", "car jump starter",
                        "motorcycle jump starter", "inverter", "lead acid battery"),
                List.of("20000mah", "10000mah", "30000mah", "20ah", "pd", "usb c",
                        "fast charge", "quick charge", "magsafe", "wireless charging",
                        "led display", "led indicator", "transparent")));

        RULES.put("phone_case", new FamilyRules(
                List.of("phone case", "iphone case", "samsung case", "galaxy case",
                        "case cover", "protective case", "bumper case", "clear case",
                        "magsafe case", "silicone case"),
                List.of(),
                List.of("screen protector", "tempered glass", "camera lens protector",
                        "phone holder", "phone stand", "phone mount", "charger", "charging cable",
                        "usb cable", "wallet", "pouch", "strap only"),
                List.of("magsafe", "clear", "transparent", "silicone", "leather", "shockproof",
                        "protective")));

        RULES.put("phone", new FamilyRules(
                List.of("smartphone", "mobile phone", "cell phone", "unlocked phone",
                        "android phone", "5g phone", "iphone", "samsung galaxy"),
                List.of(),
                List.of("phone case", "case cover", "protective case", "screen protector",
                        "tempered glass", "camera lens protector", "charger", "charging cable",
                        "usb cable", "phone holder", "phone stand", "phone mount", "back film",
                        "sticker"),
                List.of("5g", "unlocked", "android", "iphone", "galaxy", "128gb", "256gb")));

        RULES.put("earbuds", new FamilyRules(
                List.of("wireless earbuds", "bluetooth earbuds", "earbuds", "earbud",
                        "earphones", "earphone", "headphones", "headphone", "headset",
                        "airpods", "tws"),
                List.of(),
                List.of("earbud case", "earbuds case", "airpods case", "protective case",
                        "charging case", "ear tips", "eartips", "replacement tips",
                        "ear pads", "ear cushions", "headphone cable", "cleaning kit"),
                List.of("wireless", "bluetooth", "anc", "noise cancelling", "noise canceling",
                        "hi res", "airpods", "tws")));

        RULES.put("keyboard", new FamilyRules(
                List.of("mechanical keyboard", "wireless keyboard", "gaming keyboard",
                        "bluetooth keyboard", "keyboard"),
                List.of(),
                List.of("keycaps", "keycap", "switches", "keyboard switch", "keyboard cover",
                        "keyboard skin", "keyboard protector", "wrist rest", "keyboard lube",
                        "puller", "desk mat"),
                List.of("mechanical", "wireless", "bluetooth", "gaming", "rgb", "hot swap",
                        "hotswap")));

        RULES.put("shaver", new FamilyRules(
                List.of("electric shaver", "shaver", "razor", "beard trimmer", "trimmer",
                        "grooming kit"),
                List.of(),
                List.of("replacement head", "shaver head", "razor blade", "blade refill",
                        "foil cutter", "cleaning cartridge", "charger cord", "protective cap"),
                List.of("electric", "rechargeable", "wet dry", "waterproof", "philips",
                        "braun", "panasonic")));

        RULES.put("shoe", new FamilyRules(
                List.of("running shoes", "basketball shoes", "training shoes", "walking shoes",
                        "sneakers", "sneaker", "shoes", "shoe", "boots", "sandals", "slippers",
                        "trainers"),
                List.of(),
                List.of("shoelaces", "shoe laces", "insoles", "insole", "shoe horn",
                        "shoe rack", "shoe polish", "shoe cleaner", "cleaning kit",
                        "sole protector", "shoe charm"),
                List.of("running", "basketball", "training", "walking", "sneaker", "nike",
                        "adidas", "new balance", "asics")));

        RULES.put("cup", new FamilyRules(
                List.of("water bottle", "travel mug", "coffee mug", "thermos", "tumbler",
                        "cup", "mug"),
                List.of(),
                List.of("replacement lid", "cup lid", "tumbler lid", "straw lid",
                        "replacement straw", "straws", "cup sleeve", "coaster", "cup holder",
                        "bottle brush", "gasket", "seal ring"),
                List.of("insulated", "stainless steel", "vacuum", "leakproof", "with straw",
                        "stanley", "yeti")));
    }

    public IntentGate.IntentTier classify(ProductIntent intent, ProductCard product) {
        if (product == null || StringUtils.isBlank(product.title())) {
            return IntentGate.IntentTier.REJECT;
        }
        if (intent == null || StringUtils.isBlank(intent.canonicalProduct())) {
            return IntentGate.IntentTier.EXACT_MAIN;
        }

        String family = normalizeFamily(intent);
        FamilyRules rules = RULES.get(family);
        if (rules == null) {
            return IntentGate.IntentTier.EXACT_MAIN;
        }

        String text = productText(product);
        if (text.isBlank()) {
            return IntentGate.IntentTier.REJECT;
        }
        if (containsAny(text, rules.nonTargetTerms())
                && (!"power_bank".equals(family) || !isFinishedPowerBankWithBundledCable(text, rules))) {
            return IntentGate.IntentTier.REJECT;
        }

        return switch (family) {
            case "phone_case" -> classifyPhoneCase(text, rules);
            case "phone" -> classifyPhone(text, rules);
            case "power_bank" -> classifyPowerBank(text, rules);
            default -> classifyDefault(text, rules);
        };
    }

    public List<ProductCard> sortWithinTier(ProductIntent intent, List<ProductCard> products) {
        if (products == null || products.size() <= 1) {
            return products == null ? List.of() : products;
        }
        return products.stream()
                .sorted(java.util.Comparator.comparingDouble(
                        (ProductCard product) -> relevanceScore(intent, product)).reversed())
                .toList();
    }

    double relevanceScore(ProductIntent intent, ProductCard product) {
        String family = normalizeFamily(intent);
        FamilyRules rules = RULES.get(family);
        if (rules == null || product == null) {
            return 0.0;
        }
        String text = productText(product);
        double score = 0.0;
        for (String signal : rules.featureSignals()) {
            if (containsTerm(text, signal)) {
                score += signal.length() >= 8 ? 2.0 : 1.0;
            }
        }
        for (String value : safeValues(intent == null ? null : intent.hardAttributes())) {
            score += scoreIntentValue(text, value);
        }
        for (String value : safeValues(intent == null ? null : intent.softAttributes())) {
            score += scoreIntentValue(text, value);
        }
        for (String value : safeList(intent == null ? null : intent.featureTerms())) {
            score += scoreIntentValue(text, value);
        }
        if ("power_bank".equals(family) && CAPACITY.matcher(text).find()) {
            score += 2.5;
        }
        return score;
    }

    private IntentGate.IntentTier classifyDefault(String text, FamilyRules rules) {
        if (containsAny(text, rules.mainTerms())) {
            return strongSignal(text, rules) ? IntentGate.IntentTier.EXACT_MAIN : IntentGate.IntentTier.SAME_FAMILY;
        }
        if (containsAny(text, rules.relatedTerms())) {
            return IntentGate.IntentTier.SAME_FAMILY;
        }
        return IntentGate.IntentTier.REJECT;
    }

    private IntentGate.IntentTier classifyPhoneCase(String text, FamilyRules rules) {
        if (containsAny(text, rules.mainTerms())) {
            return IntentGate.IntentTier.EXACT_MAIN;
        }
        if (containsAny(text, List.of("iphone", "samsung galaxy", "phone")) && containsTerm(text, "case")) {
            return IntentGate.IntentTier.EXACT_MAIN;
        }
        if (containsAny(text, List.of("iphone", "smartphone", "mobile phone", "cell phone", "samsung galaxy"))) {
            return IntentGate.IntentTier.REJECT;
        }
        return IntentGate.IntentTier.REJECT;
    }

    private IntentGate.IntentTier classifyPhone(String text, FamilyRules rules) {
        if (containsAny(text, rules.mainTerms())) {
            return IntentGate.IntentTier.EXACT_MAIN;
        }
        return IntentGate.IntentTier.REJECT;
    }

    private IntentGate.IntentTier classifyPowerBank(String text, FamilyRules rules) {
        if (containsAny(text, rules.relatedTerms())) {
            return IntentGate.IntentTier.SAME_FAMILY;
        }
        if (containsAny(text, List.of("battery pack")) && CAPACITY.matcher(text).find()) {
            return IntentGate.IntentTier.EXACT_MAIN;
        }
        if (containsAny(text, rules.mainTerms())) {
            return strongSignal(text, rules) || CAPACITY.matcher(text).find()
                    ? IntentGate.IntentTier.EXACT_MAIN
                    : IntentGate.IntentTier.SAME_FAMILY;
        }
        return IntentGate.IntentTier.REJECT;
    }

    private boolean isFinishedPowerBankWithBundledCable(String text, FamilyRules rules) {
        boolean bundledCable = containsAny(text, List.of(
                "built in cable", "built-in cable", "built in usb c", "built-in usb-c",
                "with built in", "with built-in", "with cable", "with usb c cable",
                "attached cable"));
        if (!bundledCable) {
            return false;
        }
        return containsAny(text, rules.mainTerms()) || CAPACITY.matcher(text).find();
    }

    private boolean strongSignal(String text, FamilyRules rules) {
        return containsAny(text, rules.featureSignals());
    }

    private String normalizeFamily(ProductIntent intent) {
        if (intent == null) {
            return "";
        }
        String family = StringUtils.defaultString(intent.productFamily()).trim();
        if ("headphone".equals(family) || "earphone".equals(family) || "headphones".equals(family)) {
            return "earbuds";
        }
        if ("powerbank".equals(family) || "portable_charger".equals(family)) {
            return "power_bank";
        }
        return family;
    }

    private String productText(ProductCard product) {
        String joined = String.join(" ",
                StringUtils.defaultString(product.title()),
                StringUtils.defaultString(product.brand()),
                StringUtils.defaultString(product.shopName()),
                String.join(" ", product.tags() == null ? List.of() : product.tags()));
        return normalize(joined);
    }

    private String normalize(String value) {
        String ascii = Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        String normalized = NON_ALNUM.matcher(ascii).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
        return " " + normalized + " ";
    }

    private boolean containsAny(String text, List<String> terms) {
        for (String term : terms) {
            if (containsTerm(text, term)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsTerm(String text, String term) {
        String normalizedTerm = normalize(term).trim();
        if (normalizedTerm.isBlank()) {
            return false;
        }
        return text.contains(" " + normalizedTerm + " ");
    }

    private double scoreIntentValue(String text, String value) {
        String normalized = normalize(value).trim();
        if (normalized.isBlank()) {
            return 0.0;
        }
        if (text.contains(" " + normalized + " ")) {
            return normalized.length() >= 8 ? 2.5 : 1.2;
        }
        double score = 0.0;
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 3 && text.contains(" " + token + " ")) {
                score += 0.5;
            }
        }
        return score;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<String> safeValues(Map<String, String> values) {
        return values == null ? List.of() : values.values().stream().toList();
    }

    private record FamilyRules(
            List<String> mainTerms,
            List<String> relatedTerms,
            List<String> nonTargetTerms,
            List<String> featureSignals
    ) {}
}
