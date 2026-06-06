package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates whether a product (primarily power banks) can be taken on an airplane.
 * Rules:
 * - Power banks: energy Wh <= 100Wh is generally allowed; 100-160Wh needs airline approval; >160Wh prohibited
 * - Energy = capacity_mAh * voltage_V / 1000
 * - Keywords: loaded from CapabilitySynonymRegistry
 * - Exclusions: loaded from CapabilitySynonymRegistry
 */
@Component
public class AirplaneAllowedEvaluator implements CapabilityEvaluator {

    private static final Pattern MAH_PATTERN = Pattern.compile(
            "(\\d{1,3}(?:[,，\\s]\\d{3})+|\\d{4,6})\\s*(?:mAh|mah|毫安|毫安时)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_WAN_MAH_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*万\\s*(?:毫安|毫安时|mAh|mah)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WH_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:Wh|wh|瓦时)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLT_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d{1,2})?)\\s*[Vv](?:\\s|$|[^a-zA-Z])");

    // Extra keywords specific to airplane evaluation (beyond what registry provides)
    private static final String[] EXTRA_POSITIVE = {"100wh以下", "飞机上"};

    private final CapabilitySynonymRegistry capabilityRegistry;

    public AirplaneAllowedEvaluator(CapabilitySynonymRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    @Override
    public String capabilityCode() {
        return "airplane_allowed";
    }

    @Override
    public boolean supports(String categoryCode) {
        if (categoryCode == null) return true; // generic fallback
        String cat = categoryCode.toLowerCase();
        return cat.contains("power_bank") || cat.contains("充电宝") || cat.contains("移动电源") ||
                cat.contains("battery") || cat.contains("power") || cat.contains("电源");
    }

    @Override
    public CapabilityResult evaluate(ProductCard product, String category) {
        String text = productText(product);
        List<String> evidence = new ArrayList<>();

        // Load keywords from centralized CapabilitySynonymRegistry
        String[] positiveKeywords = capabilityRegistry.positiveKeywords("airplane_allowed");
        String[] negativeKeywords = capabilityRegistry.negativeKeywords("airplane_allowed");

        // Check negative keywords first — if found, likely NOT airplane-safe
        for (String neg : negativeKeywords) {
            if (text.contains(neg)) {
                evidence.add("标题包含「" + neg + "」，可能为户外电源/大功率设备");
                return CapabilityResult.noMatch(evidence);
            }
        }

        // Check positive keywords from registry + extra evaluator-specific ones
        boolean hasPositiveKeyword = false;
        for (String pos : positiveKeywords) {
            if (text.contains(pos)) {
                evidence.add("标题包含「" + pos + "」");
                hasPositiveKeyword = true;
            }
        }
        for (String pos : EXTRA_POSITIVE) {
            if (text.contains(pos)) {
                evidence.add("标题包含「" + pos + "」");
                hasPositiveKeyword = true;
            }
        }

        // Try to extract Wh directly
        Double wh = extractWh(text);
        if (wh != null) {
            evidence.add(String.format("额定能量 %.1fWh", wh));
            if (wh <= 100) {
                evidence.add("低于常见民航100Wh限制");
                return CapabilityResult.match(0.9, evidence);
            } else if (wh <= 160) {
                evidence.add("100-160Wh，部分航空公司需提前申请");
                return CapabilityResult.match(0.6, evidence,
                        "100-160Wh需航空公司批准，请提前确认");
            } else {
                evidence.add("超过160Wh，通常不允许带上飞机");
                return CapabilityResult.noMatch(evidence);
            }
        }

        // Try to estimate from mAh
        Integer mah = extractMah(text);
        if (mah != null) {
            double voltage = extractVolt(text) != null ? extractVolt(text) : 3.7;
            double estimatedWh = mah * voltage / 1000.0;
            evidence.add(String.format("容量 %dmAh，按%.2fV估算约 %.0fWh", mah, voltage, estimatedWh));
            if (estimatedWh <= 100) {
                if (hasPositiveKeyword) {
                    return CapabilityResult.match(0.85, evidence);
                }
                return CapabilityResult.match(0.75, evidence,
                        "按标称容量估算，具体以商品详情为准");
            } else if (estimatedWh <= 160) {
                return CapabilityResult.match(0.55, evidence,
                        "估算能量在100-160Wh区间，需航空公司批准");
            } else {
                return CapabilityResult.noMatch(evidence);
            }
        }

        // No capacity info found — rely on keywords only
        if (hasPositiveKeyword) {
            return CapabilityResult.match(0.65, evidence, "未找到具体容量参数，请以商品详情为准");
        }

        // Cannot determine
        evidence.add("未找到容量参数或登机相关关键词");
        return CapabilityResult.uncertain(0.3, evidence, "无法判断是否可登机，请查看商品详情");
    }

    private Double extractWh(String text) {
        Matcher m = WH_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Integer extractMah(String text) {
        // Try "万毫安" pattern first (e.g., "2万毫安" = 20000)
        Matcher wanMatcher = CHINESE_WAN_MAH_PATTERN.matcher(text);
        if (wanMatcher.find()) {
            try {
                double wan = Double.parseDouble(wanMatcher.group(1));
                return (int) (wan * 10000);
            } catch (NumberFormatException e) {
                // fall through
            }
        }

        // Try standard pattern (e.g., "20000mAh", "20,000mAh", "20000毫安")
        Matcher m = MAH_PATTERN.matcher(text);
        if (m.find()) {
            try {
                String raw = m.group(1).replaceAll("[,，\\s]", "");
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Double extractVolt(String text) {
        Matcher m = VOLT_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String productText(ProductCard product) {
        StringBuilder sb = new StringBuilder();
        if (product.title() != null) sb.append(product.title()).append(" ");
        if (product.brand() != null) sb.append(product.brand()).append(" ");
        if (product.shopName() != null) sb.append(product.shopName()).append(" ");
        if (product.tags() != null) sb.append(String.join(" ", product.tags()));
        return sb.toString().toLowerCase();
    }
}
