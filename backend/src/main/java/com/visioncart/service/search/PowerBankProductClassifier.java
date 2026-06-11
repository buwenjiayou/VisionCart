package com.visioncart.service.search;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Classifies power-bank search results into finished products vs. nearby non-target items.
 */
final class PowerBankProductClassifier {

    enum Type {
        FINISHED_POWER_BANK,
        RELATED_BUT_NOT_TARGET,
        NON_TARGET,
        UNKNOWN
    }

    private static final Pattern CAPACITY_PATTERN = Pattern.compile(
            "(?i)(\\d{4,6}\\s?(?:mah)|\\d{4,6}\\s?毫安|\\d+(?:\\.\\d+)?\\s?(?:ah|wh)|\\d+(?:\\.\\d+)?\\s?(?:安|瓦时))");
    private static final Pattern VOLTAGE_PATTERN = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?\\s?v|\\d+(?:\\.\\d+)?\\s?伏)");

    private static final List<String> FINISHED_CORE_TERMS = List.of(
            "充电宝", "移动电源", "powerbank", "power bank", "便携充电器");
    private static final List<String> RELATED_POWER_TERMS = List.of(
            "户外电源", "储能电源", "应急电源");
    private static final List<String> FINISHED_SIGNALS = List.of(
            "手机通用", "可登机", "登机", "便携", "自带线", "快充", "闪充",
            "pd", "qc", "22.5w", "20w", "30w", "65w", "100w", "数显",
            "电量显示", "led显示", "led指示灯", "无线充", "磁吸", "大容量");

    private static final List<String> COMPONENT_TERMS = List.of(
            "模块", "主板", "升压板", "降压板", "保护板", "充电板", "pcb", "pcba",
            "维修", "替换", "套件", "电池盒", "电瓶盒", "移动电源盒", "充电宝盒",
            "空盒", "外接", "免焊", "组装");
    private static final List<String> CIRCUIT_CONTEXT_TERMS = List.of(
            "模块", "主板", "升压板", "降压板", "保护板", "充电板", "维修",
            "替换", "套件", "pcb", "pcba", "电池盒", "电瓶盒", "移动电源盒", "充电宝盒");
    private static final List<String> SHELL_BOX_CONTEXT_TERMS = List.of(
            "电池盒", "电瓶盒", "移动电源盒", "充电宝盒", "空盒", "套件", "外接",
            "12v", "铅酸", "电瓶");

    private static final List<String> RAW_BATTERY_TERMS = List.of(
            "聚合物电池", "锂电池", "磷酸铁锂", "铅酸", "电瓶", "电芯",
            "电池组", "电池包", "软包电池", "防爆电芯", "18650", "21700");
    private static final List<String> RAW_BATTERY_DOMINANT_TERMS = List.of(
            "聚合物电池", "磷酸铁锂", "铅酸", "电瓶", "电芯", "电池组",
            "电池包", "软包电池", "防爆电芯", "18650", "21700");
    private static final List<String> VEHICLE_INDUSTRIAL_TERMS = List.of(
            "汽车启动", "摩托车启动", "启动电源", "车载", "逆变器", "电台",
            "音响", "孤灯", "防爆电芯");

    private static final List<String> LAMP_PRODUCT_TERMS = List.of(
            "小夜灯", "小台灯", "台灯", "灯泡", "护眼灯", "氛围灯", "usb灯", "usb小灯", "随身灯");
    private static final List<String> FILM_STICKER_TERMS = List.of(
            "手机背膜", "碳纤维背膜", "改色贴纸", "a4尺寸", "贴纸膜", "贴纸", "背膜", "防指纹膜");
    private static final List<String> ACCESSORY_ONLY_TERMS = List.of(
            "保护套", "收纳袋", "数据线", "充电线", "充电头", "快充头", "贴膜", "手机膜");

    private PowerBankProductClassifier() {
    }

    static Type classify(String title) {
        String normalized = PowerBankSearchRules.normalize(title);
        if (normalized.isBlank()) {
            return Type.UNKNOWN;
        }
        if (isNonTarget(normalized)) {
            return Type.NON_TARGET;
        }
        if (containsAny(normalized, RELATED_POWER_TERMS)) {
            return Type.RELATED_BUT_NOT_TARGET;
        }
        if (hasFinishedCoreTerm(normalized)) {
            return Type.FINISHED_POWER_BANK;
        }
        if (hasCapacitySignal(normalized) && containsAny(normalized, FINISHED_SIGNALS)) {
            return Type.FINISHED_POWER_BANK;
        }
        return Type.UNKNOWN;
    }

    static boolean isNonTarget(String title) {
        String normalized = PowerBankSearchRules.normalize(title);
        if (normalized.isBlank()) {
            return false;
        }
        if (containsAny(normalized, FILM_STICKER_TERMS)) {
            return true;
        }
        if (isAccessoryOnly(normalized)) {
            return true;
        }
        if (containsAny(normalized, LAMP_PRODUCT_TERMS)) {
            return true;
        }
        if (containsAny(normalized, VEHICLE_INDUSTRIAL_TERMS)) {
            return true;
        }
        if (containsAny(normalized, COMPONENT_TERMS)) {
            return true;
        }
        if (normalized.contains("外壳") && containsAny(normalized, SHELL_BOX_CONTEXT_TERMS)) {
            return true;
        }
        if ((normalized.contains("电路板") || normalized.contains("线路板"))
                && containsAny(normalized, CIRCUIT_CONTEXT_TERMS)) {
            return true;
        }
        if (normalized.contains("diy") && containsAny(normalized, COMPONENT_TERMS)) {
            return true;
        }
        return isRawBatteryDominant(normalized);
    }

    static boolean isFinishedProduct(String title) {
        return classify(title) == Type.FINISHED_POWER_BANK;
    }

    static boolean isUnsafeRetrievalTerm(String value) {
        String normalized = PowerBankSearchRules.normalize(value);
        return normalized.contains("电路板")
                || normalized.contains("露电路板")
                || normalized.contains("裸露电路板")
                || normalized.contains("外壳")
                || containsAny(normalized, COMPONENT_TERMS)
                || containsAny(normalized, RAW_BATTERY_TERMS)
                || containsAny(normalized, VEHICLE_INDUSTRIAL_TERMS)
                || containsAny(normalized, LAMP_PRODUCT_TERMS)
                || containsAny(normalized, FILM_STICKER_TERMS)
                || (containsAny(normalized, ACCESSORY_ONLY_TERMS) && !normalized.contains("自带线"));
    }

    static boolean isSafeRetrievalVisual(String value) {
        String normalized = PowerBankSearchRules.normalize(value);
        return "透明外壳".equals(normalized)
                || "黄色led指示灯".equals(normalized)
                || "led指示灯".equals(normalized);
    }

    private static boolean isAccessoryOnly(String normalized) {
        if (normalized.contains("自带线")) {
            return false;
        }
        if (normalized.contains("便携充电器") && (hasCapacitySignal(normalized) || containsAny(normalized, FINISHED_SIGNALS))) {
            return false;
        }
        return containsAny(normalized, ACCESSORY_ONLY_TERMS)
                || (normalized.contains("充电器") && !hasFinishedCoreTerm(normalized) && !hasCapacitySignal(normalized));
    }

    private static boolean isRawBatteryDominant(String normalized) {
        if (!containsAny(normalized, RAW_BATTERY_TERMS)) {
            return false;
        }
        if (containsAny(normalized, VEHICLE_INDUSTRIAL_TERMS) || VOLTAGE_PATTERN.matcher(normalized).find()) {
            return true;
        }
        if (containsAny(normalized, RAW_BATTERY_DOMINANT_TERMS)) {
            return true;
        }
        return !hasFinishedCoreTerm(normalized);
    }

    private static boolean hasFinishedCoreTerm(String normalized) {
        return containsAny(normalized, FINISHED_CORE_TERMS);
    }

    private static boolean hasCapacitySignal(String normalized) {
        return CAPACITY_PATTERN.matcher(normalized).find();
    }

    private static boolean containsAny(String normalizedText, List<String> terms) {
        for (String term : terms) {
            if (normalizedText.contains(PowerBankSearchRules.normalize(term))) {
                return true;
            }
        }
        return false;
    }
}
