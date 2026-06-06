package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized feature extractor for product capabilities.
 * Extracts structured features from product title, tags, shop name, brand, and specs.
 * All evaluators use this instead of writing their own regex.
 */
@Component
public class ProductFeatureExtractor {

    // --- Capacity ---
    private static final Pattern MAH_PATTERN = Pattern.compile(
            "(\\d{1,3}(?:[,，\\s]\\d{3})+|\\d{4,6})\\s*(?:mAh|mah|毫安|毫安时)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WH_PATTERN = Pattern.compile(
            "(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:Wh|wh|瓦时)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAH_CHINESE = Pattern.compile("(\\d{4,6})\\s*(?:毫安|毫安时)");
    private static final Pattern WAN_MAH = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*万\\s*(?:毫安|毫安时|mAh|mah)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITRE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*[Ll](?:\\s|$|[^a-zA-Z])");
    private static final Pattern ML_PATTERN = Pattern.compile("(\\d+)\\s*[mM][lL]");
    private static final Pattern GRAM_PATTERN = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*[gG](?:\\s|$|[^a-zA-Z])");
    private static final Pattern KG_PATTERN = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*[kK][gG]");

    // --- Power ---
    private static final Pattern WATT_PATTERN = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,2})?)\\s*[Ww](?:\\s|$|[^a-zA-Z])");
    private static final Pattern VOLT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*[Vv](?:\\s|$|[^a-zA-Z])");
    private static final Pattern AMP_PATTERN = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*[Aa](?:\\s|$|[^a-zA-Z])");
    // Pattern for Wh preceded by "额定能量" or similar Chinese prefix (e.g. "额定能量74Wh")
    private static final Pattern WH_PREFIX_PATTERN = Pattern.compile(
            "(?:额定能量|额定容量|电池容量|能量)\\s*(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:Wh|wh|瓦时)", Pattern.CASE_INSENSITIVE);
    // Pattern for mAh with space before unit (e.g. "20000 mAh", "10000 mah")
    private static final Pattern MAH_SPACED = Pattern.compile(
            "(\\d{4,6})\\s+(?:mAh|mah)", Pattern.CASE_INSENSITIVE);

    // --- Size ---
    private static final Pattern INCH_PATTERN = Pattern.compile("(\\d{1,2}(?:\\.\\d{1,2})?)\\s*(?:英寸|寸|inch|\")", Pattern.CASE_INSENSITIVE);
    private static final Pattern CM_PATTERN = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:cm|厘米)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MM_PATTERN = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,2})?)\\s*(?:mm|毫米)", Pattern.CASE_INSENSITIVE);

    // --- Protection ---
    private static final Pattern IP_RATING_PATTERN = Pattern.compile("IP[0-9X]{2,4}", Pattern.CASE_INSENSITIVE);

    // --- Certification ---
    private static final Pattern CERT_PATTERN = Pattern.compile(
            "\\b(?:3C|CCC|CE|FCC|FDA|UL|RoHS|REACH|IPX\\d|IP\\d{2})\\b", Pattern.CASE_INSENSITIVE);

    // --- Material keywords ---
    private static final String[] MATERIAL_KEYWORDS = {
            "不锈钢", "皮革", "真皮", "pu皮", "棉", "纯棉", "聚酯纤维", "涤纶",
            "硅胶", "tpu", "abs", "pc", "铝合金", "碳纤维", "尼龙", "帆布",
            "玻璃", "钢化玻璃", "陶瓷", "橡胶", "塑料", "木质", "竹",
            "stainless steel", "leather", "cotton", "silicone", "aluminum"
    };

    // --- Target audience ---
    private static final String[] AUDIENCE_KEYWORDS = {
            "婴儿", "宝宝", "母婴", "儿童", "学生", "老人", "女性", "女生", "男士", "男性",
            "baby", "kid", "child", "student", "elder", "women", "men"
    };

    // --- Risk words (accessory/substitute indicators) ---
    private static final String[] RISK_WORDS = {
            "防水袋", "手机壳", "保护壳", "保护套", "鞋垫", "护膝", "替换装", "备用",
            "配件", "周边", "附件", "底座", "支架", "贴膜", "钢化膜", "防尘塞",
            "转接头", "收纳袋", "收纳盒", "充电底座", "表带", "腕带",
            "户外电源", "电源站", "移动电站", "220v", "ac输出", "ac 输出", "220v ac",
            "screen protector", "case", "cover", "stand", "dock", "adapter"
    };

    /**
     * Build combined lowercase text from product for keyword matching.
     */
    public static String buildText(ProductCard product) {
        StringBuilder sb = new StringBuilder();
        if (product.title() != null) sb.append(product.title().toLowerCase()).append(" ");
        if (product.brand() != null) sb.append(product.brand().toLowerCase()).append(" ");
        if (product.shopName() != null) sb.append(product.shopName().toLowerCase()).append(" ");
        if (product.productRole() != null) sb.append(product.productRole().toLowerCase()).append(" ");
        if (product.mainCategoryCode() != null) sb.append(product.mainCategoryCode().toLowerCase()).append(" ");
        if (product.tags() != null) {
            for (String tag : product.tags()) {
                sb.append(tag.toLowerCase()).append(" ");
            }
        }
        return sb.toString();
    }

    /**
     * Extract all features from a product into a structured object.
     */
    public ProductFeatures extract(ProductCard product) {
        String title = safeLower(product.title());
        String brand = safeLower(product.brand());
        String shop = safeLower(product.shopName());
        String role = safeLower(product.productRole());
        String category = safeLower(product.mainCategoryCode());
        List<String> tags = product.tags() != null ? product.tags().stream().map(String::toLowerCase).toList() : List.of();
        String allText = title + " " + brand + " " + shop + " " + String.join(" ", tags);

        return new ProductFeatures(
                product,
                title, brand, shop, role, category, tags, allText,
                // Capacity
                extractmAh(allText),
                extractWh(allText),
                extractLitre(allText),
                extractMl(allText),
                extractGram(allText),
                extractKg(allText),
                // Power
                extractWattage(allText),
                extractVolt(allText),
                extractAmp(allText),
                // Protection
                extractIpRating(allText),
                // Material
                extractMaterials(allText),
                // Audience
                extractAudience(allText),
                // Certifications
                extractCertifications(allText),
                // Risk words
                extractRiskWords(allText),
                // Product type flags
                isAccessory(role),
                isPowerBank(category, title),
                isCharger(category, title),
                isCable(category, title),
                isCase(category, title),
                isLamp(category, title),
                isMonitor(category, title),
                isScreenProtector(category, title),
                isShoe(category, title),
                isBag(category, title),
                isBabyProduct(category, title),
                isEarphone(category, title)
        );
    }

    public record ProductFeatures(
            ProductCard product,
            String title, String brand, String shop, String role, String category,
            List<String> tags, String allText,
            // Capacity
            OptionalInt mAh, OptionalDouble wh,
            OptionalDouble litre, OptionalInt ml,
            OptionalInt gram, OptionalDouble kg,
            // Power
            OptionalInt wattage, OptionalDouble volt, OptionalDouble amp,
            // Protection
            Optional<String> ipRating,
            // Structured specs
            List<String> materials,
            List<String> audience,
            List<String> certifications,
            List<String> riskWords,
            // Product type flags
            boolean isAccessory,
            boolean isPowerBank, boolean isCharger, boolean isCable,
            boolean isCase, boolean isLamp, boolean isMonitor,
            boolean isScreenProtector, boolean isShoe, boolean isBag,
            boolean isBabyProduct, boolean isEarphone
    ) {
        /** True if any risk word is found (accessory/substitute indicator). */
        public boolean hasRiskWords() { return !riskWords.isEmpty(); }
        /** True if product is main role (not accessory/part/consumable). */
        public boolean isMainProduct() { return !isAccessory; }
        /** True if product has high protection rating (IP67+). */
        public boolean hasHighProtection() {
            return ipRating.map(ip -> {
                String digits = ip.replaceAll("[^0-9Xx]", "");
                if (digits.length() >= 2) {
                    try {
                        int first = digits.charAt(0) - '0';
                        int second = digits.charAt(1) - '0';
                        return first >= 6 && second >= 7; // IP67+
                    } catch (NumberFormatException e) { return false; }
                }
                return false;
            }).orElse(false);
        }
    }

    // --- Capacity extractors ---

    public static OptionalInt extractmAhFromText(String text) {
        return new ProductFeatureExtractor().extractmAh(text);
    }

    private OptionalInt extractmAh(String text) {
        Matcher m = MAH_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return OptionalInt.of(Integer.parseInt(normalizeNumber(m.group(1))));
            } catch (NumberFormatException ignored) {}
        }
        Matcher chinese = MAH_CHINESE.matcher(text);
        if (chinese.find()) { try { return OptionalInt.of(Integer.parseInt(chinese.group(1))); } catch (NumberFormatException ignored) {} }
        Matcher wan = WAN_MAH.matcher(text);
        if (wan.find()) {
            try {
                return OptionalInt.of((int) Math.round(Double.parseDouble(wan.group(1)) * 10000));
            } catch (NumberFormatException ignored) {}
        }
        Matcher spaced = MAH_SPACED.matcher(text);
        if (spaced.find()) { try { return OptionalInt.of(Integer.parseInt(spaced.group(1))); } catch (NumberFormatException ignored) {} }
        return OptionalInt.empty();
    }

    private String normalizeNumber(String value) {
        return value.replaceAll("[,，\\s]", "");
    }

    private OptionalDouble extractWh(String text) {
        // Try prefix pattern first (e.g. "额定能量74Wh")
        Matcher prefix = WH_PREFIX_PATTERN.matcher(text);
        if (prefix.find()) { try { return OptionalDouble.of(Double.parseDouble(prefix.group(1))); } catch (NumberFormatException ignored) {} }
        Matcher m = WH_PATTERN.matcher(text);
        if (m.find()) { try { return OptionalDouble.of(Double.parseDouble(m.group(1))); } catch (NumberFormatException ignored) {} }
        return OptionalDouble.empty();
    }

    private OptionalDouble extractLitre(String text) {
        Matcher m = LITRE_PATTERN.matcher(text);
        if (m.find()) { try { return OptionalDouble.of(Double.parseDouble(m.group(1))); } catch (NumberFormatException ignored) {} }
        return OptionalDouble.empty();
    }

    private OptionalInt extractMl(String text) {
        Matcher m = ML_PATTERN.matcher(text);
        if (m.find()) { try { return OptionalInt.of(Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) {} }
        return OptionalInt.empty();
    }

    private OptionalInt extractGram(String text) {
        Matcher m = GRAM_PATTERN.matcher(text);
        if (m.find()) { try { return OptionalInt.of((int) Double.parseDouble(m.group(1))); } catch (NumberFormatException ignored) {} }
        return OptionalInt.empty();
    }

    private OptionalDouble extractKg(String text) {
        Matcher m = KG_PATTERN.matcher(text);
        if (m.find()) { try { return OptionalDouble.of(Double.parseDouble(m.group(1))); } catch (NumberFormatException ignored) {} }
        return OptionalDouble.empty();
    }

    // --- Power extractors ---

    private OptionalInt extractWattage(String text) {
        Matcher m = WATT_PATTERN.matcher(text);
        if (m.find()) { try { return OptionalInt.of((int) Double.parseDouble(m.group(1))); } catch (NumberFormatException ignored) {} }
        return OptionalInt.empty();
    }

    private OptionalDouble extractVolt(String text) {
        Matcher m = VOLT_PATTERN.matcher(text);
        if (m.find()) { try { return OptionalDouble.of(Double.parseDouble(m.group(1))); } catch (NumberFormatException ignored) {} }
        return OptionalDouble.empty();
    }

    private OptionalDouble extractAmp(String text) {
        Matcher m = AMP_PATTERN.matcher(text);
        if (m.find()) { try { return OptionalDouble.of(Double.parseDouble(m.group(1))); } catch (NumberFormatException ignored) {} }
        return OptionalDouble.empty();
    }

    // --- Protection ---

    private Optional<String> extractIpRating(String text) {
        Matcher m = IP_RATING_PATTERN.matcher(text);
        if (m.find()) { return Optional.of(m.group().toUpperCase()); }
        return Optional.empty();
    }

    // --- Material ---

    private List<String> extractMaterials(String text) {
        List<String> found = new ArrayList<>();
        for (String mat : MATERIAL_KEYWORDS) {
            if (text.contains(mat.toLowerCase())) {
                found.add(mat);
            }
        }
        return found;
    }

    // --- Audience ---

    private List<String> extractAudience(String text) {
        List<String> found = new ArrayList<>();
        for (String aud : AUDIENCE_KEYWORDS) {
            if (text.contains(aud.toLowerCase())) {
                found.add(aud);
            }
        }
        return found;
    }

    // --- Certifications ---

    private List<String> extractCertifications(String text) {
        List<String> found = new ArrayList<>();
        Matcher m = CERT_PATTERN.matcher(text);
        while (m.find()) {
            found.add(m.group().toUpperCase());
        }
        return found;
    }

    // --- Risk words ---

    private List<String> extractRiskWords(String text) {
        List<String> found = new ArrayList<>();
        for (String risk : RISK_WORDS) {
            if (text.contains(risk.toLowerCase())) {
                found.add(risk);
            }
        }
        return found;
    }

    // --- Product type detectors ---

    private boolean isAccessory(String role) {
        if (role == null) return false;
        return role.contains("accessory") || role.contains("case") || role.contains("part")
                || role.contains("consumable") || role.contains("storage") || role.contains("配件")
                || role.contains("保护壳") || role.contains("收纳");
    }

    private boolean isPowerBank(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("充电宝") || combined.contains("移动电源") || combined.contains("power bank")
                || combined.contains("powerbank") || combined.contains("应急电源");
    }

    private boolean isCharger(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("充电器") || combined.contains("charger") || combined.contains("适配器")
                || combined.contains("快充头") || combined.contains("电源适配器");
    }

    private boolean isCable(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("数据线") || combined.contains("充电线") || combined.contains("cable")
                || combined.contains("type-c") || combined.contains("lightning");
    }

    private boolean isCase(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("保护壳") || combined.contains("手机壳") || combined.contains("case")
                || combined.contains("保护套") || combined.contains("皮套");
    }

    private boolean isLamp(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("台灯") || combined.contains("护眼灯") || combined.contains("阅读灯")
                || combined.contains("desk lamp") || combined.contains("reading light")
                || combined.contains("led灯") || combined.contains("工作灯");
    }

    private boolean isMonitor(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("显示器") || combined.contains("monitor") || combined.contains("显示屏")
                || combined.contains("屏幕");
    }

    private boolean isScreenProtector(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("屏幕贴膜") || combined.contains("钢化膜") || combined.contains("screen protector")
                || combined.contains("贴膜") || combined.contains("防窥膜");
    }

    private boolean isShoe(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("鞋") || combined.contains("shoe") || combined.contains("跑鞋")
                || combined.contains("运动鞋") || combined.contains("靴") || combined.contains("sneaker");
    }

    private boolean isBag(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("包") || combined.contains("bag") || combined.contains("背包")
                || combined.contains("手提包") || combined.contains("书包") || combined.contains("挎包");
    }

    private boolean isBabyProduct(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("婴儿") || combined.contains("baby") || combined.contains("母婴")
                || combined.contains("儿童") || combined.contains("kid") || combined.contains("child")
                || combined.contains("宝宝") || combined.contains("幼儿");
    }

    private boolean isEarphone(String category, String title) {
        String combined = (category + " " + title).toLowerCase();
        return combined.contains("耳机") || combined.contains("earphone") || combined.contains("headphone")
                || combined.contains("蓝牙耳机") || combined.contains("入耳式") || combined.contains("头戴式");
    }

    private String safeLower(String s) {
        return s != null ? s.toLowerCase() : "";
    }
}
