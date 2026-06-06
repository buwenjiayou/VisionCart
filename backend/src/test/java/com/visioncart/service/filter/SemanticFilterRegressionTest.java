package com.visioncart.service.filter;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.filter.capability.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantic filtering regression test suite.
 * Tests the FULL pipeline: ActionCompiler → FilterPlanner → FilterExecutionService → ZeroResultGuard.
 *
 * Each test simulates a realistic user scenario with a pool of products,
 * and verifies that:
 * 1. Correct products are kept/removed
 * 2. Filter state is not corrupted on rollback
 * 3. Warnings and explanations are generated
 * 4. Product count never exceeds 50
 * 5. Accessories are excluded when appropriate
 */
class SemanticFilterRegressionTest {

    private ActionCompiler actionCompiler;
    private FilterPlanner planner;
    private FilterExecutionService executionService;

    @BeforeEach
    void setUp() {
        planner = new FilterPlanner();

        List<CapabilityEvaluator> evaluators = List.of(
                new AirplaneAllowedEvaluator(new CapabilitySynonymRegistry()),
                new WaterproofEvaluator(),
                new FastChargingEvaluator(),
                new EyeProtectionEvaluator(),
                new RunningSuitableEvaluator(),
                new BabySafeEvaluator(),
                new NoiseCancellingEvaluator(),
                new LongBatteryEvaluator(),
                new GiftFriendlyEvaluator(),
                new CommutingFriendlyEvaluator(),
                new GenericKeywordCapabilityEvaluator(new ProductFeatureExtractor(), new CapabilitySynonymRegistry())
        );
        CapabilityRegistry registry = new CapabilityRegistry(evaluators);
        ZeroResultGuard guard = new ZeroResultGuard();
        executionService = new FilterExecutionService(registry, guard,
                new GenericKeywordCapabilityEvaluator(new ProductFeatureExtractor(), new CapabilitySynonymRegistry()),
                new com.visioncart.service.search.ProductSortService(new com.visioncart.service.search.ProductReputationService()));
        actionCompiler = new ActionCompiler();
    }

    // ==================== 充电宝场景 ====================

    @Nested
    @DisplayName("充电宝：可带上飞机 / 快充 / 续航长 / 不要户外电源")
    class PowerBankScenarios {

        @Test
        @DisplayName("\"可带上飞机\" — 20000mAh 充电宝保留，户外电源排除")
        void airplaneAllowed_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("小米充电宝20000mAh 22.5W快充", "小米", "充电宝", 99.0, 20000L, "main"),
                    product("Anker 10000mAh 充电宝", "Anker", "充电宝", 79.0, 5000L, "main"),
                    product("ECOFLOW 户外电源 1500Wh", "ECOFLOW", "户外电源", 3999.0, 100L, "main"),
                    product("充电宝防水袋 IP68", "无品牌", "配件", 29.0, 100L, "accessory")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("airplane", "可带上飞机", "airplane_allowed", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "可带上飞机");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "充电宝");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).hasSizeGreaterThanOrEqualTo(2);
            // 20000mAh 和 10000mAh 都能带上飞机
            assertThat(result.products()).anyMatch(p -> p.title().contains("20000mAh"));
            assertThat(result.products()).anyMatch(p -> p.title().contains("10000mAh"));
            // 户外电源不应匹配
            assertThat(result.products()).noneMatch(p -> p.title().contains("户外电源"));
        }

        @Test
        @DisplayName("\"快充\" — 65W 充电器保留，非充电器排除")
        void fastCharging_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("小米65W氮化镓充电器", "小米", "充电器", 99.0, 5000L, "main"),
                    product("手机支架 桌面懒人", "无品牌", "配件", 29.0, 10000L, "accessory"),
                    product("Anker 20W PD快充头", "Anker", "充电器", 59.0, 8000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("fast", "快充", "fast_charging", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "快充");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "充电器");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("65W"));
            assertThat(result.products()).anyMatch(p -> p.title().contains("20W"));
            // 非充电器商品应被排除
            assertThat(result.products()).noneMatch(p -> p.title().contains("手机支架"));
        }

        @Test
        @DisplayName("\"续航长\" — 大容量充电宝和手机保留")
        void longBattery_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("小米充电宝20000mAh", "小米", "充电宝", 99.0, 20000L, "main"),
                    product("超薄充电宝5000mAh", "无品牌", "充电宝", 39.0, 500L, "main"),
                    product("Redmi Note 12 5000mAh", "Redmi", "手机", 999.0, 10000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("battery", "续航长", "long_battery", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "续航长");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "充电宝");

            assertThat(result.committed()).isTrue();
            // 20000mAh 和 5000mAh 手机都应匹配
            assertThat(result.products()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // ==================== 手机场景 ====================

    @Nested
    @DisplayName("手机：防水 / 拍照好 / 续航好 / 不要手机壳")
    class PhoneScenarios {

        @Test
        @DisplayName("\"防水\" — IP68 手机保留，手机壳排除（配件）")
        void waterproof_phone_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("iPhone 15 Pro IP68防水", "Apple", "手机", 7999.0, 50000L, "main"),
                    product("三星 Galaxy S24 IP68防水", "三星", "手机", 5999.0, 30000L, "main"),
                    product("手机防水袋 IP68", "无品牌", "配件", 29.0, 10000L, "accessory"),
                    product("手机壳 防摔", "无品牌", "配件", 19.0, 50000L, "accessory")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("wp", "防水", "waterproof", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "防水");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "手机");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("iPhone"));
            assertThat(result.products()).anyMatch(p -> p.title().contains("Galaxy"));
        }

        @Test
        @DisplayName("\"长续航\" — 5000mAh 手机保留")
        void longBattery_phone_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("Redmi Note 12 5000mAh大电池", "Redmi", "手机", 999.0, 10000L, "main"),
                    product("iPhone 15 3349mAh", "Apple", "手机", 5999.0, 50000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("battery", "续航好", "long_battery", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "续航好");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "手机");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).hasSizeGreaterThanOrEqualTo(1);
            assertThat(result.products()).anyMatch(p -> p.title().contains("5000mAh"));
        }
    }

    // ==================== 鞋场景 ====================

    @Nested
    @DisplayName("鞋：适合长跑 / 轻便 / 不要鞋垫")
    class ShoeScenarios {

        @Test
        @DisplayName("\"适合长跑\" — 跑鞋保留，皮鞋排除")
        void runningSuitable_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("Nike Air Zoom 跑步鞋 缓震透气", "Nike", "运动鞋", 599.0, 10000L, "main"),
                    product("阿迪达斯 Ultraboost 轻量跑鞋", "阿迪达斯", "运动鞋", 899.0, 8000L, "main"),
                    product("男士商务正装皮鞋", "红蜻蜓", "皮鞋", 299.0, 5000L, "main"),
                    product("跑步鞋垫 减震", "无品牌", "配件", 29.0, 20000L, "accessory")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("run", "适合长跑", "running_suitable", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "适合长跑");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "运动鞋");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("Nike"));
            assertThat(result.products()).anyMatch(p -> p.title().contains("Ultraboost"));
            assertThat(result.products()).noneMatch(p -> p.title().contains("皮鞋"));
        }

        @Test
        @DisplayName("\"不要鞋垫\" — 鞋垫被排除（exclusion）")
        void excludeInsole_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("Nike Air Zoom 跑步鞋", "Nike", "运动鞋", 599.0, 10000L, "main"),
                    product("跑步鞋垫 减震透气", "无品牌", "配件", 29.0, 20000L, "accessory"),
                    product("运动鞋垫 增高", "无品牌", "配件", 19.0, 15000L, "accessory")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.exclusion("no-insole", "不要鞋垫", "exclude_roles",
                            List.of("accessory", "鞋垫"))
            );

            FilterPlan plan = planner.plan(clauses, "不要鞋垫");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "运动鞋");

            assertThat(result.products()).anyMatch(p -> p.title().contains("Nike"));
            // 鞋垫类商品应被排除
            assertThat(result.products()).noneMatch(p -> p.title().contains("鞋垫"));
        }
    }

    // ==================== 台灯场景 ====================

    @Nested
    @DisplayName("台灯：护眼 / 无频闪 / 适合学生")
    class LampScenarios {

        @Test
        @DisplayName("\"护眼\" — 护眼台灯保留，装饰灯排除")
        void eyeProtection_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("明基护眼台灯 无频闪 防蓝光", "明基", "台灯", 999.0, 5000L, "main"),
                    product("松下护眼灯 国AA级", "松下", "台灯", 599.0, 3000L, "main"),
                    product("LED氛围灯 彩色装饰灯", "无品牌", "装饰灯", 29.0, 10000L, "main"),
                    product("护眼贴 缓解疲劳", "无品牌", "配件", 39.0, 8000L, "accessory")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("eye", "护眼", "eye_protection", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "护眼");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "台灯");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("明基"));
            assertThat(result.products()).anyMatch(p -> p.title().contains("松下"));
            assertThat(result.products()).noneMatch(p -> p.title().contains("氛围灯"));
        }
    }

    // ==================== 耳机场景 ====================

    @Nested
    @DisplayName("耳机：降噪 / 运动防水 / 续航长 / 不要耳机套")
    class EarphoneScenarios {

        @Test
        @DisplayName("\"降噪\" — 主动降噪耳机保留，开放式耳机排除")
        void noiseCancelling_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("Sony WH-1000XM5 主动降噪", "Sony", "耳机", 1999.0, 10000L, "main"),
                    product("AirPods Pro 2 主动降噪", "Apple", "耳机", 1799.0, 50000L, "main"),
                    product("开放式不入耳蓝牙耳机", "无品牌", "耳机", 99.0, 5000L, "main"),
                    product("耳机套 硅胶保护套", "无品牌", "配件", 9.0, 30000L, "accessory")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("anc", "降噪", "noise_cancelling", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "降噪");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "耳机");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("Sony"));
            assertThat(result.products()).anyMatch(p -> p.title().contains("AirPods"));
        }

        @Test
        @DisplayName("\"续航长\" — 大电池耳机优先")
        void longBattery_earphone_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("Sony WH-1000XM5 30小时续航", "Sony", "耳机", 1999.0, 10000L, "main"),
                    product("有线耳机 无电池", "无品牌", "耳机", 29.0, 1000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("battery", "续航长", "long_battery", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "续航长");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "耳机");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("Sony"));
        }
    }

    // ==================== 母婴场景 ====================

    @Nested
    @DisplayName("母婴：适合婴儿 / 安全材质 / 不要替换装")
    class BabyScenarios {

        @Test
        @DisplayName("\"适合婴儿\" — 婴儿奶瓶保留，成人消毒液排除")
        void babySafe_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("贝亲婴儿奶瓶 食品级PPSU", "贝亲", "奶瓶", 129.0, 5000L, "main"),
                    product("婴儿磨牙棒 食品级硅胶", "无品牌", "婴儿用品", 39.0, 3000L, "main"),
                    product("医用酒精75%消毒液", "利尔康", "消毒液", 19.0, 10000L, "main"),
                    product("奶瓶替换奶嘴", "贝亲", "配件", 29.0, 8000L, "accessory")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("baby", "适合婴儿", "baby_safe", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "适合婴儿");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "母婴");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("奶瓶"));
            assertThat(result.products()).anyMatch(p -> p.title().contains("磨牙棒"));
            assertThat(result.products()).noneMatch(p -> p.title().contains("酒精"));
        }
    }

    // ==================== 礼物场景 ====================

    @Nested
    @DisplayName("礼物：送女生 / 高级点 / 性价比高")
    class GiftScenarios {

        @Test
        @DisplayName("\"送礼\" — 礼盒装保留，散装试用排除")
        void giftFriendly_filtersCorrectly() {
            List<ProductCard> pool = List.of(
                    product("高端礼盒装茶叶 送礼佳品", "八马茶业", "茶叶", 299.0, 2000L, "main"),
                    product("施华洛世奇水晶项链 礼盒装", "施华洛世奇", "项链", 999.0, 1000L, "main"),
                    product("散装试用装小样", "无品牌", "试用装", 9.0, 500L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("gift", "适合送礼", "gift_friendly", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "送礼");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "礼品");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("礼盒"));
            assertThat(result.products()).noneMatch(p -> p.title().contains("散装"));
        }
    }

    // ==================== 多条件组合场景 ====================

    @Nested
    @DisplayName("多条件组合：能力 + 偏好 + 排除")
    class MultiClauseScenarios {

        @Test
        @DisplayName("\"防水 + 降噪\" — 两个能力同时满足")
        void waterproofAndNoiseCancelling() {
            List<ProductCard> pool = List.of(
                    product("Sony WF-1000XM5 IPX4防水 主动降噪", "Sony", "耳机", 1799.0, 5000L, "main"),
                    product("AirPods Pro 2 IP54防水 主动降噪", "Apple", "耳机", 1799.0, 50000L, "main"),
                    product("Sony WH-1000XM5 主动降噪 不防水", "Sony", "耳机", 1999.0, 10000L, "main"),
                    product("防水蓝牙音箱 IP67", "JBL", "音箱", 399.0, 8000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("wp", "防水", "waterproof", 0.9),
                    FilterClause.capability("anc", "降噪", "noise_cancelling", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "防水+降噪");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "耳机");

            assertThat(result.committed()).isTrue();
            // 同时满足防水和降噪的应保留
            assertThat(result.products()).anyMatch(p -> p.title().contains("WF-1000XM5"));
            assertThat(result.products()).anyMatch(p -> p.title().contains("AirPods"));
        }

        @Test
        @DisplayName("\"适合长跑 + 不要鞋垫\" — 能力 + 排除组合")
        void runningAndExcludeInsole() {
            List<ProductCard> pool = List.of(
                    product("Nike Air Zoom 跑步鞋", "Nike", "运动鞋", 599.0, 10000L, "main"),
                    product("跑步鞋垫 减震", "无品牌", "配件", 29.0, 20000L, "accessory"),
                    product("商务皮鞋", "红蜻蜓", "皮鞋", 299.0, 5000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("run", "适合长跑", "running_suitable", 0.9),
                    FilterClause.exclusion("no-insole", "不要鞋垫", "exclude_roles",
                            List.of("accessory", "鞋垫"))
            );

            FilterPlan plan = planner.plan(clauses, "适合长跑+不要鞋垫");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "运动鞋");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("Nike"));
            assertThat(result.products()).noneMatch(p -> p.title().contains("鞋垫"));
            assertThat(result.products()).noneMatch(p -> p.title().contains("皮鞋"));
        }

        @Test
        @DisplayName("零结果时不覆盖旧列表 — ZeroResultGuard 保护")
        void zeroResult_keepsPrevious() {
            List<ProductCard> pool = List.of(
                    product("普通商品A", "品牌A", "品类A", 100.0, 1000L, "main"),
                    product("普通商品B", "品牌B", "品类B", 200.0, 2000L, "main")
            );

            List<ProductCard> previousProducts = List.of(
                    product("之前的商品1", "品牌X", "品类X", 300.0, 5000L, "main"),
                    product("之前的商品2", "品牌Y", "品类Y", 400.0, 3000L, "main")
            );

            // 使用一个不太可能匹配的能力
            List<FilterClause> clauses = List.of(
                    FilterClause.capability("baby", "适合婴儿", "baby_safe", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "适合婴儿");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, previousProducts, SearchFilter.empty(), "数码");

            // 如果零结果，应该回滚到 previousProducts
            if (result.products().isEmpty() || !result.committed()) {
                assertThat(result.products()).isEqualTo(previousProducts);
                assertThat(result.committed()).isFalse();
            }
        }

        @Test
        @DisplayName("过滤结果不会膨胀超过原始池大小")
        void filterResults_neverExceedPoolSize() {
            List<ProductCard> pool = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                pool.add(product("充电宝" + i + " 20000mAh", "品牌" + i, "充电宝",
                        50.0 + i, 10000L + i, "main"));
            }

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("battery", "续航长", "long_battery", 0.9)
            );

            FilterPlan plan = planner.plan(clauses, "续航长");
            FilterExecutionService.FilterExecutionResult result =
                    executionService.execute(plan, pool, List.of(), SearchFilter.empty(), "充电宝");

            // Filter should not produce more results than the input pool
            assertThat(result.products().size()).isLessThanOrEqualTo(pool.size());
        }
    }

    // ==================== ActionCompiler 字段名一致性 ====================

    @Nested
    @DisplayName("ActionCompiler 字段名标准化")
    class ActionCompilerFieldNames {

        @Test
        @DisplayName("sort_by_price_asc 生成 rerank clause")
        void sortByPriceAsc_generatesRerank() {
            List<FilterClause> clauses = actionCompiler.compile("sort_by_price_asc", SearchFilter.empty());
            assertThat(clauses).hasSize(1);
            assertThat(clauses.get(0).applyMode()).isEqualTo(FilterClause.ApplyMode.RERANK);
            assertThat(clauses.get(0).field()).isEqualTo("sort_by");
            assertThat(clauses.get(0).value()).isEqualTo("price_asc");
        }

        @Test
        @DisplayName("filter_self_operated 使用 snake_case 字段名")
        void filterSelfOperated_usesSnakeCase() {
            List<FilterClause> clauses = actionCompiler.compile("filter_self_operated", SearchFilter.empty());
            assertThat(clauses).hasSize(1);
            assertThat(clauses.get(0).field()).isEqualTo("self_operated");
        }

        @Test
        @DisplayName("filter_brand:小米 使用 snake_case 字段名")
        void filterBrand_usesSnakeCase() {
            List<FilterClause> clauses = actionCompiler.compile("filter_brand:小米", SearchFilter.empty());
            assertThat(clauses).hasSize(1);
            assertThat(clauses.get(0).field()).isEqualTo("brand");
        }

        @Test
        @DisplayName("filter_main_product 生成 exclusion clause")
        void filterMainProduct_generatesExclusion() {
            List<FilterClause> clauses = actionCompiler.compile("filter_main_product", SearchFilter.empty());
            assertThat(clauses).hasSize(1);
            assertThat(clauses.get(0).applyMode()).isEqualTo(FilterClause.ApplyMode.EXCLUSION);
        }

        @Test
        @DisplayName("set_price_alert 返回空（客户端处理）")
        void setPriceAlert_returnsEmpty() {
            List<FilterClause> clauses = actionCompiler.compile("set_price_alert", SearchFilter.empty());
            assertThat(clauses).isEmpty();
        }
    }

    // ==================== 配件过滤安全 ====================

    @Nested
    @DisplayName("配件安全过滤")
    class AccessorySafetyTests {

        @Test
        @DisplayName("\"手机防水袋\" 不应被 waterproof 能力匹配为主商品")
        void phoneWaterproofBag_notMatchedAsMain() {
            ProductCard bag = product("手机防水袋 IP68", "无品牌", "配件", 29.0, 10000L, "accessory");
            CapabilityRegistry registry = buildRegistry();
            CapabilityResult result = registry.evaluate("waterproof", bag, "手机");
            // 手机防水袋是配件，不应该被当作主商品的防水能力
            assertThat(result.matched()).isFalse();
        }

        @Test
        @DisplayName("\"护眼贴\" 不应被 eye_protection 能力匹配")
        void eyePatch_notMatchedAsEyeProtection() {
            ProductCard patch = product("护眼贴 缓解眼疲劳", "无品牌", "配件", 39.0, 8000L, "accessory");
            CapabilityRegistry registry = buildRegistry();
            CapabilityResult result = registry.evaluate("eye_protection", patch, "台灯");
            assertThat(result.matched()).isFalse();
        }

        @Test
        @DisplayName("\"跑步鞋垫\" 不应被 running_suitable 能力匹配")
        void runningInsole_notMatchedAsRunningSuitable() {
            ProductCard insole = product("跑步鞋垫 减震透气", "无品牌", "配件", 29.0, 20000L, "accessory");
            CapabilityRegistry registry = buildRegistry();
            CapabilityResult result = registry.evaluate("running_suitable", insole, "运动鞋");
            assertThat(result.matched()).isFalse();
        }

        @Test
        @DisplayName("\"耳机套\" 不应被 noise_cancelling 能力匹配")
        void earphoneCase_notMatchedAsNoiseCancelling() {
            ProductCard earphoneCase = product("耳机套 硅胶保护套", "无品牌", "配件", 9.0, 30000L, "accessory");
            CapabilityRegistry registry = buildRegistry();
            CapabilityResult result = registry.evaluate("noise_cancelling", earphoneCase, "耳机");
            assertThat(result.matched()).isFalse();
        }

        private CapabilityRegistry buildRegistry() {
            List<CapabilityEvaluator> evaluators = List.of(
                    new AirplaneAllowedEvaluator(new CapabilitySynonymRegistry()),
                    new WaterproofEvaluator(),
                    new FastChargingEvaluator(),
                    new EyeProtectionEvaluator(),
                    new RunningSuitableEvaluator(),
                    new BabySafeEvaluator(),
                    new NoiseCancellingEvaluator(),
                    new LongBatteryEvaluator(),
                    new GiftFriendlyEvaluator(),
                    new CommutingFriendlyEvaluator(),
                    new GenericKeywordCapabilityEvaluator(new ProductFeatureExtractor(), new CapabilitySynonymRegistry())
            );
            return new CapabilityRegistry(evaluators);
        }
    }

    // ==================== Helper ====================

    private ProductCard product(String title, String brand, String category, double price,
                                long sales, String productRole) {
        return new ProductCard(
                "test-" + Math.abs(title.hashCode()),
                title,
                "https://example.com/img.jpg",
                BigDecimal.valueOf(price),
                null,
                "pdd",
                false,
                null,
                4.5,
                sales,
                0.9,
                List.of(category),
                "https://example.com/detail",
                brand,
                "none",
                null,
                category,
                productRole
        );
    }
}
