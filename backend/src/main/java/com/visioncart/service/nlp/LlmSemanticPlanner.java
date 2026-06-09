package com.visioncart.service.nlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.NlpParseRequest;
import com.visioncart.api.dto.NlpParseResult;
import com.visioncart.api.dto.SemanticActionPlan;
import com.visioncart.service.ai.PromptLoader;
import com.visioncart.service.context.SessionContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LLM-first semantic planner.
 * Transforms user natural language into a SemanticActionPlan.
 *
 * The LLM's job is to UNDERSTAND and PLAN, not to directly filter products.
 * It outputs:
 * - execution mode (STRICT_FILTER, SEMANTIC_SCREENING, PREFERENCE_RERANK, etc.)
 * - hard filters for simple conditions
 * - semantic filters with evidence rules for capability conditions
 * - preferences for subjective qualities
 * - exclusions for negations
 *
 * Falls back to rule-based parsing when LLM is unavailable.
 */
@Component
public class LlmSemanticPlanner {

    private static final Logger log = LoggerFactory.getLogger(LlmSemanticPlanner.class);

    private final NlpModelService nlpModelService;
    private final SemanticPlannerModelService semanticPlannerModelService;
    private final RuleBasedNlpParser ruleBasedParser;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public LlmSemanticPlanner(NlpModelService nlpModelService,
                              SemanticPlannerModelService semanticPlannerModelService,
                              RuleBasedNlpParser ruleBasedParser,
                              PromptLoader promptLoader,
                              ObjectMapper objectMapper) {
        this.nlpModelService = nlpModelService;
        this.semanticPlannerModelService = semanticPlannerModelService;
        this.ruleBasedParser = ruleBasedParser;
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate a SemanticActionPlan from user input.
     *
     * @param userInput   The user's natural language input
     * @param context     Session context (category, attributes)
     * @param poolSummary Summary of current product pool (for context)
     * @return A validated SemanticActionPlan
     */
    public SemanticActionPlan plan(String userInput, SessionContextService.SessionContext context,
                                   ProductPoolSummary poolSummary) {
        return plan(userInput, context, poolSummary, "");
    }

    public SemanticActionPlan plan(String userInput, SessionContextService.SessionContext context,
                                   ProductPoolSummary poolSummary, String historyText) {
        log.info("LlmSemanticPlanner: planning for input='{}', category='{}'", userInput,
                context.normalizedCategory());

        // 1. Try LLM first
        try {
            SemanticActionPlan llmPlan = planWithLlm(userInput, context, poolSummary, historyText);
            if (llmPlan != null) {
                log.info("LLM plan: mode={}, semanticFilters={}, hardFilters={}",
                        llmPlan.executionMode(),
                        llmPlan.semanticFilters() != null ? llmPlan.semanticFilters().size() : 0,
                        llmPlan.hardFilters() != null ? llmPlan.hardFilters().size() : 0);
                return llmPlan;
            }
        } catch (Exception e) {
            log.warn("LLM planning failed, falling back to rule-based: {}", e.getMessage());
        }

        // 2. Fallback: rule-based planning
        return planWithRules(userInput, context);
    }

    /**
     * Plan using LLM.
     * Tries direct SemanticActionPlan output first (true LLM-first),
     * falls back to legacy NlpParseResult → SemanticActionPlan conversion.
     */
    private SemanticActionPlan planWithLlm(String userInput, SessionContextService.SessionContext context,
                                           ProductPoolSummary poolSummary, String historyText) {
        // 1. Try direct SemanticActionPlan from LLM (new path)
        try {
            String sanitizedInput = PromptSanitizer.sanitize(userInput);
            if (!sanitizedInput.isBlank()) {
                SemanticActionPlan directPlan = semanticPlannerModelService.plan(
                        sanitizedInput,
                        context.normalizedCategory(),
                        context.displayCategory(),
                        historyText
                );
                if (directPlan != null) {
                    log.info("Direct semantic plan: mode={}, hard={}, semantic={}, pref={}, excl={}",
                            directPlan.executionMode(),
                            directPlan.hardFilters() != null ? directPlan.hardFilters().size() : 0,
                            directPlan.semanticFilters() != null ? directPlan.semanticFilters().size() : 0,
                            directPlan.preferences() != null ? directPlan.preferences().size() : 0,
                            directPlan.exclusions() != null ? directPlan.exclusions().size() : 0);

                    // Semantic filters from LLM only have code/userMeaning — fill in evidence rules
                    SemanticActionPlan enriched = enrichSemanticFilters(directPlan, context);
                    // Set target product from context
                    return new SemanticActionPlan(
                            enriched.intent(), enriched.executionMode(),
                            context.normalizedCategory(),
                            enriched.hardFilters(), enriched.semanticFilters(),
                            enriched.preferences(), enriched.exclusions(),
                            enriched.sort(), enriched.zeroResultPolicy(),
                            enriched.criteria(), enriched.negativeCriteria(),
                            enriched.judge(), enriched.rankingGoal(),
                            enriched.resultStrategy());
                }
            }
        } catch (Exception e) {
            log.warn("Direct semantic planning failed, falling back to legacy: {}", e.getMessage());
        }

        // 2. Fallback: legacy NlpParseResult → SemanticActionPlan conversion
        NlpParseRequest.NlpContext nlpContext = new NlpParseRequest.NlpContext(
                null, context.normalizedCategory(), List.of());
        NlpParseRequest request = new NlpParseRequest(
                null, userInput, nlpContext);

        NlpParseResult parseResult = nlpModelService.parse(request);
        if (parseResult == null) return null;

        return convertToSemanticPlan(parseResult, userInput, context);
    }

    /**
     * Enrich semantic filters from direct LLM output with domain-specific evidence rules.
     * The LLM only outputs code + userMeaning; we fill in the evidence rules here.
     */
    private SemanticActionPlan enrichSemanticFilters(SemanticActionPlan plan,
                                                     SessionContextService.SessionContext context) {
        if (plan.semanticFilters() == null || plan.semanticFilters().isEmpty()) {
            return plan;
        }

        List<SemanticActionPlan.SemanticFilter> enriched = new ArrayList<>();
        for (SemanticActionPlan.SemanticFilter sf : plan.semanticFilters()) {
            SemanticActionPlan.SemanticFilter withEvidence = buildSemanticFilter(
                    sf.code(), "", context.normalizedCategory());
            if (withEvidence != null) {
                // Preserve userMeaning from LLM output
                enriched.add(new SemanticActionPlan.SemanticFilter(
                        withEvidence.code(),
                        sf.userMeaning() != null ? sf.userMeaning() : withEvidence.userMeaning(),
                        withEvidence.matchLogic(),
                        withEvidence.positiveEvidence(),
                        withEvidence.negativeEvidence(),
                        withEvidence.unknownPolicy()));
            } else {
                // Unknown capability — keep as-is with generic filter
                enriched.add(sf);
            }
        }

        return new SemanticActionPlan(
                plan.intent(), plan.executionMode(), plan.targetProduct(),
                plan.hardFilters(), enriched,
                plan.preferences(), plan.exclusions(),
                plan.sort(), plan.zeroResultPolicy(),
                plan.criteria(), plan.negativeCriteria(),
                plan.judge(), plan.rankingGoal(),
                plan.resultStrategy());
    }

    /**
     * Convert existing NlpParseResult to SemanticActionPlan.
     * This bridges the current LLM output format to the new semantic plan format.
     */
    private SemanticActionPlan convertToSemanticPlan(NlpParseResult result,
                                                     String userInput,
                                                     SessionContextService.SessionContext context) {
        String executionMode = determineExecutionMode(result, userInput);
        String category = context.normalizedCategory();

        List<SemanticActionPlan.HardFilter> hardFilters = new ArrayList<>();
        List<SemanticActionPlan.SemanticFilter> semanticFilters = new ArrayList<>();
        List<SemanticActionPlan.PreferenceRule> preferences = new ArrayList<>();
        List<SemanticActionPlan.ExclusionRule> exclusions = new ArrayList<>();
        SemanticActionPlan.SortRule sortRule = null;

        // Convert structured filter fields to hard filters
        if (result.filter() != null) {
            var filter = result.filter();
            if (filter.priceRange() != null) {
                if (filter.priceRange().min() != null) {
                    hardFilters.add(new SemanticActionPlan.HardFilter("price", ">=", filter.priceRange().min()));
                }
                if (filter.priceRange().max() != null) {
                    hardFilters.add(new SemanticActionPlan.HardFilter("price", "<=", filter.priceRange().max()));
                }
            }
            if (filter.platforms() != null && !filter.platforms().isEmpty()) {
                hardFilters.add(new SemanticActionPlan.HardFilter("platform", "in", filter.platforms()));
            }
            if (Boolean.TRUE.equals(filter.selfOperated())) {
                hardFilters.add(new SemanticActionPlan.HardFilter("self_operated", "equals", true));
            }
            if (filter.colors() != null && !filter.colors().isEmpty()) {
                hardFilters.add(new SemanticActionPlan.HardFilter("color", "in", filter.colors()));
            }
            if (filter.brands() != null && !filter.brands().isEmpty()) {
                hardFilters.add(new SemanticActionPlan.HardFilter("brand", "in", filter.brands()));
            }
            if (filter.ratingMin() != null) {
                hardFilters.add(new SemanticActionPlan.HardFilter("rating", ">=", filter.ratingMin()));
            }
            if (filter.sortBy() != null) {
                sortRule = new SemanticActionPlan.SortRule(filter.sortBy(), filter.sortOrder() != null ? filter.sortOrder() : "desc");
            }
        }

        // Convert detected clauses to semantic filters / preferences / exclusions
        if (result.clauses() != null) {
            for (var clause : result.clauses()) {
                switch (clause.type()) {
                    case "capability" -> {
                        SemanticActionPlan.SemanticFilter sf = buildSemanticFilter(clause.field(), userInput, category);
                        if (sf != null) semanticFilters.add(sf);
                    }
                    case "preference" -> {
                        preferences.add(new SemanticActionPlan.PreferenceRule(
                                clause.field(), clause.rawText(), clause.confidence()));
                    }
                    case "exclusion" -> {
                        String field = clause.field();
                        List<String> keywords = buildExclusionKeywords(field);
                        List<String> roles = buildExclusionRoles(field);
                        exclusions.add(new SemanticActionPlan.ExclusionRule(
                                field, clause.rawText(), keywords, roles));
                    }
                }
            }
        }

        // Determine zero result policy
        String zeroResultPolicy = semanticFilters.isEmpty() ? "KEEP_PREVIOUS_RESULTS" : "KEEP_PREVIOUS_RESULTS";

        return new SemanticActionPlan(
                "filter_current_results",
                executionMode,
                category,
                hardFilters.isEmpty() ? null : hardFilters,
                semanticFilters.isEmpty() ? null : semanticFilters,
                preferences.isEmpty() ? null : preferences,
                exclusions.isEmpty() ? null : exclusions,
                sortRule,
                zeroResultPolicy
        );
    }

    /**
     * Determine execution mode from parsed result and user input.
     */
    private String determineExecutionMode(NlpParseResult result, String userInput) {
        if (result.clauses() != null) {
            boolean hasCapability = result.clauses().stream()
                    .anyMatch(c -> "capability".equals(c.type()));
            boolean hasPreference = result.clauses().stream()
                    .anyMatch(c -> "preference".equals(c.type()));
            boolean hasExclusion = result.clauses().stream()
                    .anyMatch(c -> "exclusion".equals(c.type()));

            if (hasCapability) return "SEMANTIC_SCREENING";
            if (hasExclusion) return "EXCLUSION";
            if (hasPreference) return "PREFERENCE_RERANK";
        }

        if (result.filter() != null) {
            boolean hasStructured = result.filter().priceRange() != null
                    || (result.filter().platforms() != null && !result.filter().platforms().isEmpty())
                    || (result.filter().colors() != null && !result.filter().colors().isEmpty())
                    || (result.filter().brands() != null && !result.filter().brands().isEmpty())
                    || result.filter().ratingMin() != null;
            if (hasStructured) return "STRICT_FILTER";
        }

        return "STRICT_FILTER";
    }

    /**
     * Build a SemanticFilter for a capability code with appropriate evidence rules.
     */
    private SemanticActionPlan.SemanticFilter buildSemanticFilter(String capabilityCode,
                                                                   String userInput,
                                                                   String category) {
        return switch (capabilityCode) {
            case "airplane_allowed" -> buildAirplaneAllowedFilter();
            case "waterproof" -> buildWaterproofFilter();
            case "fast_charging" -> buildFastChargingFilter();
            case "eye_protection" -> buildEyeProtectionFilter();
            case "noise_cancelling" -> buildNoiseCancellingFilter();
            case "running_suitable" -> buildRunningSuitableFilter();
            case "long_battery" -> buildLongBatteryFilter();
            case "baby_safe" -> buildBabySafeFilter();
            default -> buildGenericFilter(capabilityCode);
        };
    }

    // ==================== Capability-Specific Filters ====================

    private SemanticActionPlan.SemanticFilter buildAirplaneAllowedFilter() {
        return new SemanticActionPlan.SemanticFilter(
                "airplane_allowed",
                "筛选可以随身携带上飞机的充电宝",
                "EVIDENCE_OR_SCORE",
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "explicit_airline_claim", "商品明确标注可登机、可带上飞机、民航允许",
                                List.of("title", "tags", "all_text"), "contains_any",
                                null,
                                List.of("可登机", "可上飞机", "可带上飞机", "能上飞机", "民航允许",
                                        "航空允许", "登机款", "随身携带", "飞机上", "100Wh以下"),
                                null, 0.95, "EXPLICIT_MATCH", "accept", null),
                        // Direct Wh check: if product states Wh directly (e.g. "74Wh", "额定能量74Wh"),
                        // use the extracted energy_wh field with simple <= comparison
                        new SemanticActionPlan.EvidenceRule(
                                "explicit_wh_under_limit", "商品直接标注 Wh 且低于 100Wh 限制",
                                List.of("energy_wh"), "<=", 100, null,
                                null,
                                0.90, "EXPLICIT_MATCH", "accept", null),
                        // Formula: mAh + voltage → Wh (when both are available)
                        new SemanticActionPlan.EvidenceRule(
                                "mah_voltage_estimated_wh", "商品有 mAh 和电压，估算 Wh <= 100",
                                null, "formula_lte", 100, null,
                                "ENERGY_WH_FROM_MAH_VOLTAGE",
                                0.80, "INFERRED_MATCH", "accept", "按容量和电压估算，需查看详情确认"),
                        // Formula: mAh only → Wh with default 3.7V
                        new SemanticActionPlan.EvidenceRule(
                                "mah_default_voltage", "商品只有 mAh，按默认锂电 3.7V 估算",
                                null, "formula_lte", 100, null,
                                "ENERGY_WH_FROM_MAH_DEFAULT",
                                0.65, "INFERRED_MATCH", "accept", "按默认电压估算，需查看详情确认")
                ),
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "outdoor_power_station", "户外电源、电源站、220V、AC 输出",
                                List.of("all_text"), "contains_any",
                                null,
                                List.of("户外电源", "电源站", "220V", "AC输出", "露营电源", "大功率储能"),
                                null, 0.0, "REJECTED", "reject", null)
                ),
                "KEEP_AS_SECONDARY"
        );
    }

    private SemanticActionPlan.SemanticFilter buildWaterproofFilter() {
        return new SemanticActionPlan.SemanticFilter(
                "waterproof", "筛选防水产品", "EVIDENCE_OR_SCORE",
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "explicit_waterproof", "商品标注防水、IP67、IP68",
                                List.of("title", "tags", "all_text"), "contains_any",
                                null,
                                List.of("防水", "IP67", "IP68", "IPX7", "IPX8", "waterproof", "防泼溅"),
                                null, 0.90, "EXPLICIT_MATCH", "accept", null),
                        new SemanticActionPlan.EvidenceRule(
                                "ip_rating", "商品有 IP 防水等级标注",
                                List.of("ip_rating"), "contains",
                                "IP", null, null,
                                0.85, "EXPLICIT_MATCH", "accept", null)
                ),
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "not_waterproof", "商品标注不防水",
                                List.of("all_text"), "contains_any",
                                null,
                                List.of("不防水", "非防水", "不支持防水"),
                                null, 0.0, "REJECTED", "reject", null),
                        new SemanticActionPlan.EvidenceRule(
                                "accessory_case", "防水袋/防水壳/防水套（配件）",
                                List.of("all_text"), "contains_any",
                                null,
                                List.of("防水袋", "防水壳", "防水套", "防水包", "手机防水袋"),
                                null, 0.0, "REJECTED", "reject", null)
                ),
                "KEEP_AS_SECONDARY"
        );
    }

    private SemanticActionPlan.SemanticFilter buildFastChargingFilter() {
        return new SemanticActionPlan.SemanticFilter(
                "fast_charging", "筛选快充产品", "EVIDENCE_OR_SCORE",
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "explicit_fast_charge", "商品标注快充、闪充、超级快充",
                                List.of("title", "tags", "all_text"), "contains_any",
                                null,
                                List.of("快充", "闪充", "超级快充", "快速充电", "PD快充", "QC快充"),
                                null, 0.90, "EXPLICIT_MATCH", "accept", null),
                        new SemanticActionPlan.EvidenceRule(
                                "high_wattage", "商品标注高功率充电（65W+）",
                                List.of("wattage"), ">=", 65, null, null,
                                0.85, "EXPLICIT_MATCH", "accept", null)
                ),
                List.of(),
                "KEEP_AS_SECONDARY"
        );
    }

    private SemanticActionPlan.SemanticFilter buildEyeProtectionFilter() {
        return new SemanticActionPlan.SemanticFilter(
                "eye_protection", "筛选护眼产品", "EVIDENCE_OR_SCORE",
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "explicit_eye_protection", "商品标注护眼、防蓝光、无频闪",
                                List.of("title", "tags", "all_text"), "contains_any",
                                null,
                                List.of("护眼", "防蓝光", "无频闪", "柔光", "减蓝光", "eye protection"),
                                null, 0.90, "EXPLICIT_MATCH", "accept", null)
                ),
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "eye_patch", "护眼贴/眼罩（非灯具）",
                                List.of("all_text"), "contains_any",
                                null,
                                List.of("护眼贴", "眼罩", "眼贴", "蒸汽眼罩"),
                                null, 0.0, "REJECTED", "reject", null)
                ),
                "KEEP_AS_SECONDARY"
        );
    }

    private SemanticActionPlan.SemanticFilter buildNoiseCancellingFilter() {
        return new SemanticActionPlan.SemanticFilter(
                "noise_cancelling", "筛选降噪耳机", "EVIDENCE_OR_SCORE",
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "explicit_anc", "商品标注降噪、主动降噪、ANC",
                                List.of("title", "tags", "all_text"), "contains_any",
                                null,
                                List.of("降噪", "主动降噪", "ANC", "noise cancelling", "隔音"),
                                null, 0.90, "EXPLICIT_MATCH", "accept", null)
                ),
                List.of(),
                "KEEP_AS_SECONDARY"
        );
    }

    private SemanticActionPlan.SemanticFilter buildRunningSuitableFilter() {
        return new SemanticActionPlan.SemanticFilter(
                "running_suitable", "筛选适合跑步的鞋", "EVIDENCE_OR_SCORE",
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "explicit_running", "商品标注跑步、跑鞋、马拉松",
                                List.of("title", "tags", "all_text"), "contains_any",
                                null,
                                List.of("跑步", "跑鞋", "马拉松", "慢跑", "竞速", "running"),
                                null, 0.90, "EXPLICIT_MATCH", "accept", null),
                        new SemanticActionPlan.EvidenceRule(
                                "cushioning", "商品有缓震、透气运动特征",
                                List.of("all_text"), "contains_any",
                                null,
                                List.of("缓震", "透气", "减震", "回弹", "运动"),
                                null, 0.70, "INFERRED_MATCH", "accept", null)
                ),
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "dress_shoes", "正装鞋、皮鞋、高跟鞋",
                                List.of("all_text"), "contains_any",
                                null,
                                List.of("正装", "皮鞋", "高跟鞋", "商务鞋", "dress"),
                                null, 0.0, "REJECTED", "reject", null)
                ),
                "KEEP_AS_SECONDARY"
        );
    }

    private SemanticActionPlan.SemanticFilter buildLongBatteryFilter() {
        return new SemanticActionPlan.SemanticFilter(
                "long_battery", "筛选长续航产品", "EVIDENCE_OR_SCORE",
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "explicit_long_battery", "商品标注长续航、大电池、超长续航",
                                List.of("title", "tags", "all_text"), "contains_any",
                                null,
                                List.of("长续航", "大电池", "超长续航", "待机王", "持久续航"),
                                null, 0.90, "EXPLICIT_MATCH", "accept", null)
                ),
                List.of(),
                "KEEP_AS_SECONDARY"
        );
    }

    private SemanticActionPlan.SemanticFilter buildBabySafeFilter() {
        return new SemanticActionPlan.SemanticFilter(
                "baby_safe", "筛选婴儿安全产品", "EVIDENCE_OR_SCORE",
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "explicit_baby_safe", "商品标注婴儿、母婴、食品级、无毒",
                                List.of("title", "tags", "all_text"), "contains_any",
                                null,
                                List.of("婴儿", "母婴", "食品级", "无毒", "宝宝", "儿童安全"),
                                null, 0.90, "EXPLICIT_MATCH", "accept", null)
                ),
                List.of(),
                "KEEP_AS_SECONDARY"
        );
    }

    private SemanticActionPlan.SemanticFilter buildGenericFilter(String capabilityCode) {
        return new SemanticActionPlan.SemanticFilter(
                capabilityCode, "筛选" + capabilityCode + "产品", "EVIDENCE_OR_SCORE",
                List.of(
                        new SemanticActionPlan.EvidenceRule(
                                "generic_keyword", "商品包含相关关键词",
                                List.of("all_text"), "contains",
                                capabilityCode, null, null,
                                0.70, "INFERRED_MATCH", "accept", null)
                ),
                List.of(),
                "KEEP_AS_SECONDARY"
        );
    }

    /**
     * Build exclusion keywords based on the exclusion field.
     */
    private List<String> buildExclusionKeywords(String field) {
        return switch (field) {
            case "exclude_accessory", "accessory" -> List.of(
                    "手机壳", "手机套", "保护壳", "保护套", "手机膜", "钢化膜",
                    "充电线", "数据线", "耳机套", "耳机壳", "杯套", "杯盖",
                    "支架", "底座", "收纳袋", "防水袋", "鞋垫", "护膝",
                    "替换装", "配件", "附件", "case", "cover", "film"
            );
            case "exclude_case" -> List.of("手机壳", "手机套", "保护壳", "保护套", "case", "cover");
            default -> List.of();
        };
    }

    /**
     * Build exclusion roles based on the exclusion field.
     */
    private List<String> buildExclusionRoles(String field) {
        return switch (field) {
            case "exclude_accessory", "accessory" -> List.of("accessory", "case", "part", "consumable", "storage");
            case "exclude_case" -> List.of("case");
            default -> List.of();
        };
    }

    /**
     * Rule-based fallback planning when LLM is unavailable.
     */
    private SemanticActionPlan planWithRules(String userInput, SessionContextService.SessionContext context) {
        RuleBasedNlpParser.ParsedFilter parsed = ruleBasedParser.parse(userInput);
        List<SemanticActionPlan.HardFilter> hardFilters = new ArrayList<>();
        SemanticActionPlan.SortRule sortRule = null;

        if (parsed.filter().priceRange() != null) {
            if (parsed.filter().priceRange().min() != null) {
                hardFilters.add(new SemanticActionPlan.HardFilter("price", ">=", parsed.filter().priceRange().min()));
            }
            if (parsed.filter().priceRange().max() != null) {
                hardFilters.add(new SemanticActionPlan.HardFilter("price", "<=", parsed.filter().priceRange().max()));
            }
        }
        if (parsed.filter().sortBy() != null) {
            sortRule = new SemanticActionPlan.SortRule(parsed.filter().sortBy(),
                    parsed.filter().sortOrder() != null ? parsed.filter().sortOrder() : "desc");
        }

        return new SemanticActionPlan(
                "filter_current_results", "STRICT_FILTER", context.normalizedCategory(),
                hardFilters.isEmpty() ? null : hardFilters,
                null, null, null, sortRule, "KEEP_PREVIOUS_RESULTS"
        );
    }

    /**
     * Summary of the current product pool (for LLM context).
     */
    public record ProductPoolSummary(
            int totalProducts,
            List<String> sampleTitles,
            String category
    ) {
        public static ProductPoolSummary from(List<com.visioncart.api.dto.ProductCard> products) {
            List<String> titles = products.stream()
                    .limit(5)
                    .map(p -> p.title() != null ? p.title() : "")
                    .toList();
            return new ProductPoolSummary(products.size(), titles, null);
        }
    }
}
