package com.visioncart.service.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CategoryNormalizerTest {

    // ====== normalize: 品类归一化 ======

    @Test
    void cupKeywordsNormalizeToCode() {
        assertEquals("cup", CategoryNormalizer.normalize("杯子", List.of()));
        assertEquals("cup", CategoryNormalizer.normalize("水杯", List.of()));
        assertEquals("cup", CategoryNormalizer.normalize("保温杯", List.of()));
        assertEquals("cup", CategoryNormalizer.normalize("马克杯", List.of()));
        assertEquals("cup", CategoryNormalizer.normalize("玻璃杯", List.of()));
        assertEquals("cup", CategoryNormalizer.normalize("茶杯", List.of()));
        assertEquals("cup", CategoryNormalizer.normalize("咖啡杯", List.of()));
    }

    @Test
    void broadCategoryWithCupKeywordResolvesViaKeywords() {
        // VLM 返回宽泛类目"餐具水具"，但关键词含"杯子" → 归一到 cup
        assertEquals("cup", CategoryNormalizer.normalize("餐具水具", List.of("杯子")));
        // 宽泛类目+关键词含"水杯"
        assertEquals("cup", CategoryNormalizer.normalize("餐具", List.of("水杯")));
    }

    @Test
    void broadCategoryWithoutSpecificKeywordUsesHint() {
        // "餐具水具" 的 hint 是 "cup"
        assertEquals("cup", CategoryNormalizer.normalize("餐具水具", List.of()));
        // "鞋靴" 的 hint 是 "shoe"
        assertEquals("shoe", CategoryNormalizer.normalize("鞋靴", List.of()));
    }

    @Test
    void phoneCategoryNormalizes() {
        assertEquals("phone", CategoryNormalizer.normalize("手机", List.of()));
        assertEquals("phone", CategoryNormalizer.normalize("手机", List.of("iPhone")));
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertEquals("", CategoryNormalizer.normalize("", List.of()));
        assertEquals("", CategoryNormalizer.normalize("未知", List.of()));
        assertEquals("", CategoryNormalizer.normalize("", List.of("好看")));
    }

    // ====== inferRole: 商品角色推断 ======

    @Test
    void cupAccessoryDetected() {
        assertEquals("accessory", CategoryNormalizer.inferRole("杯套", "cup"));
        assertEquals("accessory", CategoryNormalizer.inferRole("杯垫", "cup"));
        assertEquals("accessory", CategoryNormalizer.inferRole("杯盖", "cup"));
        assertEquals("accessory", CategoryNormalizer.inferRole("杯刷", "cup"));
        assertEquals("accessory", CategoryNormalizer.inferRole("杯架", "cup"));
        assertEquals("accessory", CategoryNormalizer.inferRole("水杯吸管", "cup"));
        assertEquals("accessory", CategoryNormalizer.inferRole("保温杯杯盖", "cup"));
        assertEquals("accessory", CategoryNormalizer.inferRole("马克杯隔热套", "cup"));
    }

    @Test
    void cupMainProductDetected() {
        assertEquals("main", CategoryNormalizer.inferRole("马克杯", "cup"));
        assertEquals("main", CategoryNormalizer.inferRole("玻璃杯", "cup"));
        assertEquals("main", CategoryNormalizer.inferRole("保温杯", "cup"));
        assertEquals("main", CategoryNormalizer.inferRole("陶瓷杯", "cup"));
        assertEquals("main", CategoryNormalizer.inferRole("不锈钢水杯", "cup"));
    }

    @Test
    void phoneAccessoryDetected() {
        assertEquals("accessory", CategoryNormalizer.inferRole("手机壳", "phone"));
        assertEquals("accessory", CategoryNormalizer.inferRole("手机膜", "phone"));
        assertEquals("accessory", CategoryNormalizer.inferRole("手机支架", "phone"));
        assertEquals("accessory", CategoryNormalizer.inferRole("手机套", "phone"));
    }

    @Test
    void phoneMainProductDetected() {
        assertEquals("main", CategoryNormalizer.inferRole("iPhone 15", "phone"));
        assertEquals("main", CategoryNormalizer.inferRole("小米手机", "phone"));
    }

    @Test
    void emptyTitleReturnsUnknown() {
        assertEquals("unknown", CategoryNormalizer.inferRole("", "cup"));
        assertEquals("unknown", CategoryNormalizer.inferRole(null, "cup"));
        assertEquals("unknown", CategoryNormalizer.inferRole("杯子", ""));
    }

    // ====== intentRole: 搜索意图角色 ======

    @Test
    void cupSearchIntentIsMain() {
        assertEquals("main", CategoryNormalizer.intentRole("杯子", List.of("杯子")));
        assertEquals("main", CategoryNormalizer.intentRole("水杯", List.of()));
    }

    @Test
    void cupAccessorySearchIntentIsAccessory() {
        assertEquals("accessory", CategoryNormalizer.intentRole("", List.of("杯套")));
        assertEquals("accessory", CategoryNormalizer.intentRole("", List.of("手机壳")));
        assertEquals("accessory", CategoryNormalizer.intentRole("", List.of("耳机套")));
    }

    @Test
    void broadCategoryWithAccessoryKeywordIsAccessory() {
        // 宽泛类目但关键词是配件
        assertEquals("accessory", CategoryNormalizer.intentRole("餐具水具", List.of("杯套")));
    }
}
