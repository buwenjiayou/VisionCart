package com.visioncart.service.recognition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeOptionCatalogTest {

    @Test
    void razorBrandOptionsStayInPersonalCareVertical() {
        List<String> options = AttributeOptionCatalog.optionsFor("个护 / 剃须刀 / 电动剃须刀", "品牌");

        assertThat(options).contains("Philips", "飞利浦", "Braun", "博朗", "飞科");
        assertThat(options).doesNotContain("Apple", "苹果", "Nike", "Adidas");
    }

    @Test
    void phoneBrandOptionsCanIncludeApple() {
        List<String> options = AttributeOptionCatalog.optionsFor("数码 / 手机 / 智能手机", "品牌");

        assertThat(options).contains("Apple", "苹果", "Huawei", "华为");
    }

    @Test
    void unknownBrandCategoryReturnsNoOptions() {
        List<String> options = AttributeOptionCatalog.optionsFor("未知类目", "品牌");

        assertThat(options).isEmpty();
    }

    @Test
    void colorKeepsGenericOptions() {
        List<String> options = AttributeOptionCatalog.optionsFor("任意类目", "颜色");

        assertThat(options).contains("黑色", "白色", "蓝色", "透明");
    }

    @Test
    void hardAttributesDefaultToManualInputOnly() {
        assertThat(AttributeOptionCatalog.optionsFor("电动剃须刀", "型号")).isEmpty();
        assertThat(AttributeOptionCatalog.optionsFor("电动剃须刀", "规格")).isEmpty();
        assertThat(AttributeOptionCatalog.optionsFor("电动剃须刀", "材质")).isEmpty();
        assertThat(AttributeOptionCatalog.optionsFor("电动剃须刀", "款式")).isEmpty();
    }
}
