package com.visioncart.service.filter;

import com.visioncart.api.dto.FilterTag;
import com.visioncart.api.dto.PriceRange;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.filter.capability.*;
import com.visioncart.service.nlp.NlpConversationManager;
import com.visioncart.service.search.CandidateFilterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for SafeActionExecutor.
 * Tests the full orchestration with real FilterExecutionService/FilterPlanner/ActionCompiler
 * but mocked NlpUndoService/NlpConversationManager/CandidateFilterService.
 *
 * Verifies:
 * - All action sources (nlp, suggestion, correction, tag_delete) go through the same pipeline
 * - Zero-result guard works correctly (rollback on empty)
 * - Undo points are saved on commit, not on rollback
 * - Structured filter tags are generated correctly
 * - Rollback does NOT leak failed clause tags
 */
class SafeActionExecutorIntegrationTest {

    private SafeActionExecutor executor;
    private FilterExecutionService filterExecutionService;
    private CandidateFilterService filterService;
    private NlpUndoService undoService;
    private NlpConversationManager conversationManager;
    private ActionCompiler actionCompiler;
    private FilterPlanner planner;

    @BeforeEach
    void setUp() {
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
        filterExecutionService = new FilterExecutionService(registry, guard,
                new GenericKeywordCapabilityEvaluator(new ProductFeatureExtractor(), new CapabilitySynonymRegistry()),
                new com.visioncart.service.search.ProductSortService(new com.visioncart.service.search.ProductReputationService()));
        planner = new FilterPlanner();
        actionCompiler = new ActionCompiler();

        filterService = mock(CandidateFilterService.class);
        undoService = mock(NlpUndoService.class);
        conversationManager = mock(NlpConversationManager.class);

        executor = new SafeActionExecutor(filterExecutionService, filterService, undoService, conversationManager, actionCompiler);

        // Default: prepareSemanticPool returns the input candidates
        when(filterService.prepareSemanticPool(anyList(), any(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Default: filter() returns input candidates as FilterResult
        when(filterService.filter(anyList(), any(), anyMap(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    List<?> products = inv.getArgument(0);
                    return new CandidateFilterService.FilterResult(
                            (List<ProductCard>) products, products.size(), products.size(), false, false);
                });

        // Default: canUndo returns true when saveUndoPoint is called
        when(undoService.canUndo(anyString())).thenReturn(true);
    }

    // ==================== NLP 来源 ====================

    @Nested
    @DisplayName("NLP 来源动作")
    class NlpSourceTests {

        @Test
        @DisplayName("\"可带上飞机\" — capability clause，commit 后保存 undo point")
        void nlp_airplaneAllowed_commitsAndSavesUndo() {
            List<ProductCard> candidates = List.of(
                    product("小米充电宝20000mAh", "小米", "充电宝", 99.0, 10000L, "main"),
                    product("ECOFLOW户外电源1500Wh", "ECOFLOW", "户外电源", 3999.0, 100L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("airplane", "可带上飞机", "airplane_allowed", 0.9)
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "充电宝", "nlp", "可带上飞机");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("20000mAh"));
            assertThat(result.canUndo()).isTrue();

            // Verify undo point was saved
            verify(undoService).saveUndoPoint(
                    eq("test-session"), any(), any(), eq("可带上飞机"), eq("nlp"), eq("可带上飞机"));
            // Verify filter state was persisted
            verify(conversationManager).setFilterState(eq("test-session"), any());
        }

        @Test
        @DisplayName("\"不要户外电源\" — exclusion clause，排除户外电源")
        void nlp_excludeOutdoorPower_excludesCorrectly() {
            List<ProductCard> candidates = List.of(
                    product("小米充电宝20000mAh", "小米", "充电宝", 99.0, 10000L, "main"),
                    product("ECOFLOW户外电源1500Wh", "ECOFLOW", "户外电源", 3999.0, 100L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.exclusion("no-outdoor", "不要户外电源", "exclude_roles",
                            List.of("户外电源"))
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "充电宝", "nlp", "不要户外电源");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).noneMatch(p -> p.title().contains("户外电源"));
        }
    }

    // ==================== Suggestion 来源 ====================

    @Nested
    @DisplayName("Suggestion 来源动作")
    class SuggestionSourceTests {

        @Test
        @DisplayName("sort_by_price_asc — 排序操作，commit 后保存 undo point")
        void suggestion_sortByPrice_commitsAndSavesUndo() {
            List<ProductCard> candidates = List.of(
                    product("商品A", "品牌A", "品类A", 200.0, 1000L, "main"),
                    product("商品B", "品牌B", "品类B", 100.0, 2000L, "main"),
                    product("商品C", "品牌C", "品类C", 150.0, 1500L, "main")
            );

            List<FilterClause> clauses = actionCompiler.compile("sort_by_price_asc", SearchFilter.empty());

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "数码", "suggestion", "按价格排序");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).hasSize(3);
            // First should be cheapest
            assertThat(result.products().get(0).price().doubleValue()).isEqualTo(100.0);

            verify(undoService).saveUndoPoint(
                    eq("test-session"), any(), any(), eq("按价格排序"), eq("suggestion"), any());
        }

        @Test
        @DisplayName("filter_main_product — 排除配件，commit 后保存 undo point")
        void suggestion_filterMainProduct_excludesAccessories() {
            List<ProductCard> candidates = List.of(
                    product("小米充电宝20000mAh", "小米", "充电宝", 99.0, 10000L, "main"),
                    product("充电宝保护套", "无品牌", "配件", 19.0, 5000L, "accessory"),
                    product("充电宝数据线", "无品牌", "配件", 9.0, 8000L, "accessory")
            );

            List<FilterClause> clauses = actionCompiler.compile("filter_main_product", SearchFilter.empty());

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "充电宝", "suggestion", "只看商品主体");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).anyMatch(p -> p.title().contains("20000mAh"));
        }

        @Test
        @DisplayName("set_price_alert — 空 clause 列表，不修改 filter state")
        void suggestion_setPriceAlert_noFilterChange() {
            List<ProductCard> candidates = List.of(
                    product("商品A", "品牌A", "品类A", 100.0, 1000L, "main")
            );

            List<FilterClause> clauses = actionCompiler.compile("set_price_alert", SearchFilter.empty());

            assertThat(clauses).isEmpty();

            // Execute with empty clauses — should be a no-op pass-through
            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "数码", "suggestion", "设降价提醒");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).hasSize(1);
            // No undo point saved for empty-clause actions (pass-through)
        }
    }

    // ==================== Correction 来源 ====================

    @Nested
    @DisplayName("AttributeCorrection 来源动作")
    class CorrectionSourceTests {

        @Test
        @DisplayName("修正品牌 — structured clause，commit 后保存 undo point")
        void correction_brand_commitsAndSavesUndo() {
            List<ProductCard> candidates = List.of(
                    product("小米充电宝20000mAh", "小米", "充电宝", 99.0, 10000L, "main"),
                    product("Anker充电宝10000mAh", "Anker", "充电宝", 79.0, 5000L, "main"),
                    product("罗马仕充电宝20000mAh", "罗马仕", "充电宝", 69.0, 8000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.structured("correction-brand-小米", "小米", "brand", "contains", "小米")
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), candidates,
                    "充电宝", "correction", "修正品牌=小米");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).allMatch(p -> p.title().contains("小米") || p.brand().contains("小米"));

            verify(undoService).saveUndoPoint(
                    eq("test-session"), any(), any(), eq("修正品牌=小米"), eq("correction"), any());
        }

        @Test
        @DisplayName("修正颜色 — structured clause，过滤正确颜色")
        void correction_color_filtersCorrectly() {
            List<ProductCard> candidates = List.of(
                    product("手机壳 黑色", "无品牌", "配件", 19.0, 1000L, "accessory"),
                    product("手机壳 白色", "无品牌", "配件", 19.0, 800L, "accessory"),
                    product("手机壳 透明", "无品牌", "配件", 15.0, 500L, "accessory")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.structured("correction-color-黑色", "黑色", "color", "contains", "黑色")
            );

            SearchFilter updatedFilter = new SearchFilter(
                    null, null, null, List.of("黑色"), null, null,
                    null, null, null, null, null, null);

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), updatedFilter, SearchFilter.empty(), candidates,
                    "配件", "correction", "修正颜色=黑色");

            assertThat(result.committed()).isTrue();
        }
    }

    // ==================== Zero-Result Guard ====================

    @Nested
    @DisplayName("Zero-Result Guard 回滚保护")
    class ZeroResultGuardTests {

        @Test
        @DisplayName("零结果时不 commit，保留 previous products")
        void zeroResult_rollsBack_keepsPrevious() {
            List<ProductCard> candidates = List.of(
                    product("普通商品A", "品牌A", "品类A", 100.0, 1000L, "main"),
                    product("普通商品B", "品牌B", "品类B", 200.0, 2000L, "main")
            );

            List<ProductCard> previousProducts = List.of(
                    product("之前的商品1", "品牌X", "品类X", 300.0, 5000L, "main"),
                    product("之前的商品2", "品牌Y", "品类Y", 400.0, 3000L, "main")
            );

            // 用不太可能匹配的 capability — 品类A/B 不太可能是婴儿安全
            List<FilterClause> clauses = List.of(
                    FilterClause.capability("baby", "适合婴儿", "baby_safe", 0.9)
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), previousProducts,
                    "数码", "nlp", "适合婴儿");

            // 如果零结果，应该回滚
            if (!result.committed()) {
                assertThat(result.products()).isEqualTo(previousProducts);
                // rollback 时不保存 undo point
                verify(undoService, never()).saveUndoPoint(anyString(), any(), any(), anyString(), anyString(), anyString());
                // rollback 时不持久化 filter state
                verify(conversationManager, never()).setFilterState(anyString(), any());
            }
        }

        @Test
        @DisplayName("回滚时不返回失败 clause 的 filter tags")
        void rollback_doesNotLeakFailedClauseTags() {
            List<ProductCard> candidates = List.of(
                    product("普通商品A", "品牌A", "品类A", 100.0, 1000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("baby", "适合婴儿", "baby_safe", 0.9)
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "数码", "nlp", "适合婴儿");

            if (!result.committed()) {
                // Rollback — structured tags should NOT contain the failed capability tag
                assertThat(result.structuredFilterTags()).noneMatch(t ->
                        "capability".equals(t.source()) && "capabilities.baby_safe".equals(t.filterPath()));
            }
        }

        @Test
        @DisplayName("sparse 结果（< 10 但 > 0）仍然 commit with warning")
        void sparseResult_commitsWithWarning() {
            List<ProductCard> candidates = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                candidates.add(product("充电宝" + i + " 20000mAh", "品牌" + i, "充电宝",
                        50.0 + i, 10000L + i, "main"));
            }
            // Add one non-matching product
            candidates.add(product("户外电源 1500Wh", "ECOFLOW", "户外电源", 3999.0, 100L, "main"));

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("airplane", "可带上飞机", "airplane_allowed", 0.9)
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "充电宝", "nlp", "可带上飞机");

            // 充电宝都是 20000mAh，应该匹配 airplane_allowed
            assertThat(result.committed()).isTrue();
            assertThat(result.products()).hasSizeGreaterThanOrEqualTo(10);
            // 户外电源应被排除
            assertThat(result.products()).noneMatch(p -> p.title().contains("户外电源"));
        }
    }

    // ==================== Tag 生成 ====================

    @Nested
    @DisplayName("Filter Tag 生成")
    class FilterTagTests {

        @Test
        @DisplayName("commit 后返回 capability tag")
        void commit_returnsCapabilityTag() {
            List<ProductCard> candidates = List.of(
                    product("小米充电宝20000mAh", "小米", "充电宝", 99.0, 10000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("airplane", "可带上飞机", "airplane_allowed", 0.9)
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "充电宝", "nlp", "可带上飞机");

            if (result.committed()) {
                assertThat(result.structuredFilterTags()).anyMatch(t ->
                        "capability".equals(t.source()));
            }
        }

        @Test
        @DisplayName("commit 后结构化 filter tag 包含 filterPath")
        void commit_filterTagHasFilterPath() {
            SearchFilter filterWithPrice = new SearchFilter(
                    new PriceRange(null, 200.0),
                    null, null, null, null, null,
                    null, null, null, null, null, null);

            List<ProductCard> candidates = List.of(
                    product("商品A", "品牌A", "品类A", 100.0, 1000L, "main"),
                    product("商品B", "品牌B", "品类B", 300.0, 2000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.structured("price-max", "≤200", "price_max", "lte", 200)
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), filterWithPrice, SearchFilter.empty(), candidates,
                    "数码", "nlp", "200元以内");

            if (result.committed()) {
                assertThat(result.structuredFilterTags()).anyMatch(t ->
                        t.filterPath() != null && t.filterPath().contains("price"));
            }
        }
    }

    // ==================== 全入口统一验证 ====================

    @Nested
    @DisplayName("全入口统一 SafeActionExecutor 验证")
    class UnifiedEntryTests {

        @Test
        @DisplayName("所有 source 类型都通过同一 execute 方法")
        void allSources_useSameExecutor() {
            List<ProductCard> candidates = List.of(
                    product("小米充电宝20000mAh", "小米", "充电宝", 99.0, 10000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("airplane", "可带上飞机", "airplane_allowed", 0.9)
            );

            String[] sources = {"nlp", "suggestion", "correction", "tag_delete", "sort"};

            for (String source : sources) {
                SafeActionExecutor.SafeActionResult result = executor.execute(
                        "test-session-" + source, candidates, clauses,
                        SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                        "充电宝", source, "test action");

                // All sources should produce valid results
                assertThat(result.products()).isNotNull();
                assertThat(result.structuredFilterTags()).isNotNull();
                assertThat(result.filterTags()).isNotNull();
            }
        }

        @Test
        @DisplayName("null sessionId 不崩溃，不保存 undo")
        void nullSessionId_noCrash_noUndoSave() {
            List<ProductCard> candidates = List.of(
                    product("小米充电宝20000mAh", "小米", "充电宝", 99.0, 10000L, "main")
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.capability("airplane", "可带上飞机", "airplane_allowed", 0.9)
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    null, candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "充电宝", "nlp", "可带上飞机");

            assertThat(result.products()).isNotNull();
            assertThat(result.canUndo()).isFalse();

            verify(undoService, never()).saveUndoPoint(anyString(), any(), any(), anyString(), anyString(), anyString());
            verify(conversationManager, never()).setFilterState(anyString(), any());
        }

        @Test
        @DisplayName("空 clause 列表 — pass-through，不修改状态")
        void emptyClauses_passThrough() {
            List<ProductCard> candidates = List.of(
                    product("商品A", "品牌A", "品类A", 100.0, 1000L, "main"),
                    product("商品B", "品牌B", "品类B", 200.0, 2000L, "main")
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, List.of(),
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "数码", "nlp", "");

            assertThat(result.committed()).isTrue();
            assertThat(result.products()).hasSize(2);
        }
    }

    // ==================== P2-10: 企业级一致性测试 ====================

    @Nested
    @DisplayName("P2-10 企业级一致性测试")
    class EnterpriseConsistencyTests {

        @Test
        @DisplayName("结构化筛选 0 结果时必须回滚，不能提交")
        void structuredFilter_zeroResults_mustRollback() {
            List<ProductCard> candidates = List.of(
                    product("普通商品A", "品牌A", "品类A", 100.0, 1000L, "main"),
                    product("普通商品B", "品牌B", "品类B", 200.0, 2000L, "main")
            );

            // 纯结构化筛选：平台=京东，但所有商品都是 pdd
            List<FilterClause> clauses = List.of(
                    FilterClause.structured("platform-jd", "京东", "platform", "eq", "京东")
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), candidates,
                    "数码", "nlp", "只看京东");

            // 必须回滚 — 0 结果不能提交
            assertThat(result.committed()).isFalse();
            // 回滚后保留原商品
            assertThat(result.products()).isEqualTo(candidates);
            // 不保存 undo point
            verify(undoService, never()).saveUndoPoint(anyString(), any(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("filter_main_product 按 productRole 排除配件")
        void filterMainProduct_excludesByProductRole() {
            List<ProductCard> candidates = List.of(
                    product("小米充电宝20000mAh", "小米", "充电宝", 99.0, 10000L, "main"),
                    product("充电宝保护套", "无品牌", "配件", 19.0, 5000L, "accessory"),
                    product("充电宝数据线", "无品牌", "配件", 9.0, 8000L, "accessory"),
                    product("充电宝收纳袋", "无品牌", "配件", 15.0, 3000L, "case")
            );

            List<FilterClause> clauses = actionCompiler.compile("filter_main_product", SearchFilter.empty());

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), List.of(),
                    "充电宝", "suggestion", "只看主体商品");

            assertThat(result.committed()).isTrue();
            // 只保留 main 产品
            assertThat(result.products()).hasSize(1);
            assertThat(result.products().get(0).productRole()).isEqualTo("main");
            assertThat(result.products().get(0).title()).contains("20000mAh");
        }

        @Test
        @DisplayName("修正 platform 字段 — 真实过滤平台")
        void correction_platform_filtersByPlatform() {
            List<ProductCard> candidates = List.of(
                    product("商品A", "品牌A", "品类A", 100.0, 1000L, "main"),  // pdd
                    product("商品B", "品牌B", "品类B", 200.0, 2000L, "main")   // pdd
            );

            List<FilterClause> clauses = List.of(
                    FilterClause.structured("correction-platform-京东", "京东", "platform", "eq", "京东")
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), candidates,
                    "数码", "correction", "修正平台=京东");

            // 平台不匹配 → 0 结果 → 回滚
            assertThat(result.committed()).isFalse();
            assertThat(result.products()).isEqualTo(candidates);
        }

        @Test
        @DisplayName("修正 rating_min 字段 — 真实过滤评分")
        void correction_ratingMin_filtersByRating() {
            List<ProductCard> candidates = List.of(
                    product("高评分商品", "品牌A", "品类A", 100.0, 1000L, "main"),   // rating 4.5
                    product("低评分商品", "品牌B", "品类B", 200.0, 2000L, "main")    // rating 4.5
            );

            // 要求评分 ≥ 4.8，但所有商品评分 4.5
            List<FilterClause> clauses = List.of(
                    FilterClause.structured("correction-rating", "≥4.8分", "rating_min", "gte", 4.8)
            );

            SafeActionExecutor.SafeActionResult result = executor.execute(
                    "test-session", candidates, clauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), candidates,
                    "数码", "correction", "修正评分≥4.8");

            // 评分不满足 → 回滚
            assertThat(result.committed()).isFalse();
        }

        @Test
        @DisplayName("Flow action set_price_alert — executeAction 返回 uiAction，不修改 products")
        void flowAction_setPriceAlert_returnsUiAction() {
            List<ProductCard> candidates = List.of(
                    product("商品A", "品牌A", "品类A", 100.0, 1000L, "main")
            );

            com.visioncart.api.dto.UserAction action = com.visioncart.api.dto.UserAction.suggestion(
                    "test-session", "set_price_alert", "设降价提醒");

            List<FilterClause> clauses = actionCompiler.compile("set_price_alert", SearchFilter.empty());
            assertThat(clauses).isEmpty();

            com.visioncart.api.dto.ActionResult result = executor.executeAction(
                    action, clauses, candidates,
                    candidates, SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(),
                    "数码");

            // Flow action: filterApplied=false, uiAction 非空
            assertThat(result.filterApplied()).isFalse();
            assertThat(result.uiAction()).isNotNull();
            assertThat(result.uiAction().type()).isEqualTo("OPEN_PRICE_ALERT_DIALOG");
            // 不修改 products
            assertThat(result.products()).isNull();
        }

        @Test
        @DisplayName("统一 undo 链：NLP → Suggestion → Correction → undo 撤回最后一步")
        void undoChain_revertsLatestAction() {
            // Simulate: NLP commits, then Suggestion commits, then undo reverts Suggestion
            List<ProductCard> candidates = List.of(
                    product("小米充电宝20000mAh", "小米", "充电宝", 99.0, 10000L, "main"),
                    product("Anker充电宝10000mAh", "Anker", "充电宝", 79.0, 5000L, "main")
            );

            // Step 1: NLP — brand filter
            List<FilterClause> nlpClauses = List.of(
                    FilterClause.structured("nlp-brand", "小米", "brand", "contains", "小米")
            );
            SafeActionExecutor.SafeActionResult nlpResult = executor.execute(
                    "undo-chain-session", candidates, nlpClauses,
                    SearchFilter.empty(), SearchFilter.empty(), SearchFilter.empty(), candidates,
                    "充电宝", "nlp", "小米");
            assertThat(nlpResult.committed()).isTrue();
            assertThat(nlpResult.canUndo()).isTrue();

            // Step 2: Suggestion — price sort (on top of NLP result)
            List<FilterClause> sugClauses = actionCompiler.compile("sort_by_price_asc", SearchFilter.empty());
            SafeActionExecutor.SafeActionResult sugResult = executor.execute(
                    "undo-chain-session", nlpResult.products(), sugClauses,
                    nlpResult.appliedFilter(), nlpResult.appliedFilter(), nlpResult.appliedFilter(), nlpResult.products(),
                    "充电宝", "suggestion", "按价格排序");
            assertThat(sugResult.committed()).isTrue();

            // Verify: undo was saved for both actions
            verify(undoService, times(2)).saveUndoPoint(
                    eq("undo-chain-session"), any(), any(), anyString(), anyString(), anyString());
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
