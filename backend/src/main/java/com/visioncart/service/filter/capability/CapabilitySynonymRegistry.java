package com.visioncart.service.filter.capability;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Centralized capability synonym registry.
 * Single source of truth for ALL capability/preference codes, their display text,
 * and the keywords that map to them.
 *
 * Used by:
 * - RuleBasedNlpParser (rule-based NLP fallback)
 * - NlpFilterController.buildFilterClauses() (keyword detection)
 * - NlpConversationManager.resolveCapabilityCode() (display text → code)
 * - SafeActionExecutor / NlpFilterController (code → display text)
 * - FilterPlanner (clause classification)
 * - CapabilityEvaluator positive keywords
 * - FilterTag display
 * - Regression tests
 *
 * IMPORTANT: When adding a new capability, update this registry AND nlp-system.yml prompt.
 */
@Component
public class CapabilitySynonymRegistry {

    /**
     * Capability entry with all metadata.
     */
    public record CapabilityEntry(
            String code,
            String displayText,
            List<String> aliases,       // User-facing synonyms for NLP parsing
            List<String> positiveKeywords, // Product-side keywords for capability evaluation
            List<String> negativeKeywords, // Product-side exclusion keywords
            List<String> categories      // Applicable product categories (empty = all)
    ) {}

    private static final Map<String, CapabilityEntry> CAPABILITIES;
    private static final Map<String, String> ALIAS_TO_CODE; // alias → capability code
    private static final Map<String, String> KEYWORD_TO_CODE; // keyword fragment → capability code

    static {
        Map<String, CapabilityEntry> caps = new LinkedHashMap<>();

        // ===== Capability codes (type=capability) =====

        caps.put("airplane_allowed", new CapabilityEntry(
                "airplane_allowed", "可带上飞机",
                List.of("可带上飞机", "可以带上飞机", "可以上飞机", "能带上飞机", "能上飞机",
                        "可上飞机", "可登机", "登机可带", "上机", "民航", "航空允许", "航空",
                        "随身携带", "carry on", "cabin allowed", "出差能用", "飞机上能用"),
                List.of("可登机", "民航认可", "航空", "100wh以下", "登机", "可上飞机", "能上飞机",
                        "飞机", "随身携带", "民航"),
                List.of("户外电源", "220v", "ac输出", "电源站", "移动电站"),
                List.of("power_bank", "充电宝", "移动电源", "battery", "power", "电源")
        ));

        caps.put("waterproof", new CapabilityEntry(
                "waterproof", "防水",
                List.of("防水", "防水的", "下雨能用", "水下", "雨天", "游泳防水", "淋雨"),
                List.of("防水", "ipx", "ip67", "ip68", "防水等级", "生活防水", "游泳防水", "深度防水"),
                List.of("不防水", "怕水", "防水袋", "防水壳", "防水套", "防水包"),
                List.of()
        ));

        caps.put("fast_charging", new CapabilityEntry(
                "fast_charging", "快充",
                List.of("快充", "快充的", "闪充", "超级快充", "快速充电", "充电快"),
                List.of("快充", "闪充", "快速充电", "超级快充", "pd充电", "qc快充", "65w", "100w", "120w", "200w"),
                List.of("慢充"),
                List.of()
        ));

        caps.put("eye_protection", new CapabilityEntry(
                "eye_protection", "护眼",
                List.of("护眼", "护眼的", "不伤眼", "防蓝光", "无频闪", "保护眼睛", "柔光"),
                List.of("护眼", "不伤眼", "无频闪", "防蓝光", "减蓝光", "柔光", "阅读灯", "学习灯", "国aa级"),
                List.of("频闪严重", "护眼贴", "护眼罩", "眼罩"),
                List.of()
        ));

        caps.put("noise_cancelling", new CapabilityEntry(
                "noise_cancelling", "降噪",
                List.of("降噪", "降噪的", "主动降噪", "anc", "安静", "隔音", "消除噪音"),
                List.of("降噪", "主动降噪", "anc", "enc通话降噪", "深度降噪", "智能降噪"),
                List.of("无降噪"),
                List.of()
        ));

        caps.put("long_battery", new CapabilityEntry(
                "long_battery", "长续航",
                List.of("长续航", "续航长", "续航久", "大电池", "持久电量", "待机长", "电量耐用"),
                List.of("长续航", "续航长", "续航久", "超长续航", "大电池", "持久续航", "待机长"),
                List.of("续航短"),
                List.of()
        ));

        caps.put("baby_safe", new CapabilityEntry(
                "baby_safe", "婴儿安全",
                List.of("婴儿安全", "婴儿可用", "宝宝", "母婴", "食品级", "婴幼儿", "适合婴儿", "无毒"),
                List.of("婴儿", "宝宝", "儿童安全", "母婴", "无毒", "食品级", "婴幼儿"),
                List.of("不适合儿童", "成人用品"),
                List.of()
        ));

        caps.put("running_suitable", new CapabilityEntry(
                "running_suitable", "适合跑步",
                List.of("适合跑步", "适合长跑", "跑步用", "跑鞋", "马拉松", "运动跑鞋"),
                List.of("跑步", "长跑", "马拉松", "运动鞋", "跑鞋", "缓震", "透气运动"),
                List.of("不适合运动", "正装鞋", "皮鞋", "高跟鞋", "鞋垫", "护膝"),
                List.of()
        ));

        caps.put("gift_friendly", new CapabilityEntry(
                "gift_friendly", "适合送礼",
                List.of("适合送礼", "送礼", "礼品", "礼盒", "送人", "送女生", "送男生", "生日礼物"),
                List.of("礼盒", "送礼", "礼品", "包装精美", "高端礼盒", "送男", "送女", "生日礼物"),
                List.of(),
                List.of()
        ));

        caps.put("commuting_friendly", new CapabilityEntry(
                "commuting_friendly", "适合通勤",
                List.of("适合通勤", "通勤", "上下班", "日常用", "上班用"),
                List.of("通勤", "便携", "轻便", "轻量", "折叠", "随身", "日常", "百搭", "简约", "单肩", "斜挎"),
                List.of("专业户外", "登山", "露营", "重型"),
                List.of()
        ));

        caps.put("dustproof", new CapabilityEntry(
                "dustproof", "防尘",
                List.of("防尘", "防尘的", "防灰尘", "防沙"),
                List.of("防尘", "防灰尘", "防沙", "密封", "防尘等级"),
                List.of(),
                List.of()
        ));

        caps.put("shockproof", new CapabilityEntry(
                "shockproof", "防摔",
                List.of("防摔", "防摔的", "抗摔", "防跌落", "耐摔", "抗震", "防震"),
                List.of("防摔", "抗摔", "防跌落", "耐摔", "抗震", "防震", "军工级"),
                List.of(),
                List.of()
        ));

        // ===== Preference codes (type=preference) =====

        caps.put("cost_effective", new CapabilityEntry(
                "cost_effective", "性价比高",
                List.of("性价比高", "性价比", "划算", "实惠", "超值", "平价", "便宜", "物美价廉"),
                List.of("性价比", "实惠", "超值", "平价", "便宜", "划算", "物美价廉"),
                List.of(),
                List.of()
        ));

        caps.put("lightweight", new CapabilityEntry(
                "lightweight", "轻便",
                List.of("轻便", "轻巧", "轻量", "超轻", "轻薄", "不重", "不要太重"),
                List.of("轻便", "轻量", "轻巧", "超轻", "轻薄", "mini", "迷你"),
                List.of(),
                List.of()
        ));

        caps.put("premium", new CapabilityEntry(
                "premium", "高端品质",
                List.of("高端", "高级", "品质好", "精品", "旗舰", "奢华", "轻奢"),
                List.of("高端", "高级", "品质", "精品", "旗舰", "奢华", "轻奢"),
                List.of("低端"),
                List.of()
        ));

        caps.put("appearance", new CapabilityEntry(
                "appearance", "高颜值",
                List.of("好看", "高颜值", "外观好看", "时尚", "潮流", "设计感"),
                List.of("好看", "外观", "颜值", "时尚", "潮流", "设计感", "高颜值"),
                List.of(),
                List.of()
        ));

        CAPABILITIES = Collections.unmodifiableMap(caps);

        // Build alias → code lookup
        Map<String, String> aliasMap = new HashMap<>();
        Map<String, String> keywordMap = new HashMap<>();
        for (CapabilityEntry entry : caps.values()) {
            for (String alias : entry.aliases()) {
                aliasMap.put(alias.toLowerCase(), entry.code());
            }
            for (String keyword : entry.positiveKeywords()) {
                keywordMap.put(keyword.toLowerCase(), entry.code());
            }
        }
        ALIAS_TO_CODE = Collections.unmodifiableMap(aliasMap);
        KEYWORD_TO_CODE = Collections.unmodifiableMap(keywordMap);
    }

    /**
     * Get a capability entry by code.
     */
    public CapabilityEntry get(String code) {
        return CAPABILITIES.get(code);
    }

    /**
     * Get all capability codes.
     */
    public Set<String> allCodes() {
        return CAPABILITIES.keySet();
    }

    /**
     * Get display text for a capability code.
     * Returns the code itself if unknown.
     */
    public String displayText(String code) {
        CapabilityEntry entry = CAPABILITIES.get(code);
        return entry != null ? entry.displayText() : code;
    }

    /**
     * Resolve a user input string (alias, display text, or code) to a capability code.
     * Returns null if not recognized.
     *
     * Supports:
     * - Direct code match: "airplane_allowed" → "airplane_allowed"
     * - Alias match: "可以上飞机" → "airplane_allowed"
     * - Substring match: "飞机" → "airplane_allowed"
     */
    public String resolveToCode(String input) {
        if (input == null || input.isBlank()) return null;
        String lower = input.toLowerCase().trim();

        // 1. Direct code match
        if (CAPABILITIES.containsKey(lower)) {
            return lower;
        }

        // 2. Exact alias match
        String aliasMatch = ALIAS_TO_CODE.get(lower);
        if (aliasMatch != null) {
            return aliasMatch;
        }

        // 3. Substring match against aliases (longest match first)
        String bestMatch = null;
        int bestLength = 0;
        for (Map.Entry<String, String> entry : ALIAS_TO_CODE.entrySet()) {
            if (lower.contains(entry.getKey()) && entry.getKey().length() > bestLength) {
                bestMatch = entry.getValue();
                bestLength = entry.getKey().length();
            }
        }
        if (bestMatch != null) {
            return bestMatch;
        }

        // 4. Substring match against product keywords
        for (Map.Entry<String, String> entry : KEYWORD_TO_CODE.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Check if a user input contains any capability keyword.
     * Returns the matched capability code, or null.
     * Used by NlpFilterController.buildFilterClauses() and FilterPlanner.
     */
    public String detectCapability(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;
        String lower = userInput.toLowerCase();

        // Try alias matches (longest first to avoid partial matches)
        List<Map.Entry<String, String>> sorted = new ArrayList<>(ALIAS_TO_CODE.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        for (Map.Entry<String, String> entry : sorted) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Get all aliases for a capability code.
     * Used for NLP prompt generation and testing.
     */
    public List<String> aliases(String code) {
        CapabilityEntry entry = CAPABILITIES.get(code);
        return entry != null ? entry.aliases() : List.of();
    }

    /**
     * Get positive product-side keywords for a capability code.
     * Used by CapabilityEvaluators.
     */
    public String[] positiveKeywords(String code) {
        CapabilityEntry entry = CAPABILITIES.get(code);
        return entry != null ? entry.positiveKeywords().toArray(String[]::new) : new String[0];
    }

    /**
     * Get negative product-side keywords for a capability code.
     * Used by CapabilityEvaluators.
     */
    public String[] negativeKeywords(String code) {
        CapabilityEntry entry = CAPABILITIES.get(code);
        return entry != null ? entry.negativeKeywords().toArray(String[]::new) : new String[0];
    }

    /**
     * Check if a string is a known capability code.
     */
    public boolean isCapabilityCode(String code) {
        return CAPABILITIES.containsKey(code) && !isPreferenceCode(code);
    }

    /**
     * Check if a string is a known preference code.
     */
    public boolean isPreferenceCode(String code) {
        return "cost_effective".equals(code) || "lightweight".equals(code)
                || "premium".equals(code) || "appearance".equals(code);
    }

    /**
     * Get all capability codes (excluding preferences).
     */
    public Set<String> capabilityCodes() {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, CapabilityEntry> entry : CAPABILITIES.entrySet()) {
            if (!isPreferenceCode(entry.getKey())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Get all preference codes.
     */
    public Set<String> preferenceCodes() {
        return Set.of("cost_effective", "lightweight", "premium", "appearance");
    }
}
