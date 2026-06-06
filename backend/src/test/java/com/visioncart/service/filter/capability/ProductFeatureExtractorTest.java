package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductFeatureExtractorTest {

    private final ProductFeatureExtractor extractor = new ProductFeatureExtractor();

    @Test
    void extractsCommonPowerBankCapacityFormats() {
        assertThat(ProductFeatureExtractor.extractmAhFromText("20,000mAh")).hasValue(20000);
        assertThat(ProductFeatureExtractor.extractmAhFromText("20 000mAh")).hasValue(20000);
        assertThat(ProductFeatureExtractor.extractmAhFromText("2万毫安")).hasValue(20000);
        assertThat(ProductFeatureExtractor.extractmAhFromText("2.0万毫安")).hasValue(20000);
        assertThat(ProductFeatureExtractor.extractmAhFromText("20000毫安时")).hasValue(20000);
    }

    @Test
    void extractsWhAndBatteryVoltage() {
        ProductFeatureExtractor.ProductFeatures features =
                extractor.extract(product("充电宝 额定能量74Wh 3.85V"));

        assertThat(features.wh()).hasValue(74.0);
        assertThat(features.volt()).hasValue(3.85);
    }

    @Test
    void marksOutdoorPowerStationRiskWords() {
        ProductFeatureExtractor.ProductFeatures features =
                extractor.extract(product("户外电源 电源站 220V AC输出"));

        assertThat(features.riskWords()).contains("户外电源", "电源站", "220v", "ac输出");
    }

    private ProductCard product(String title) {
        return new ProductCard(
                "p-" + title.hashCode(),
                title,
                "https://example.com/img.jpg",
                BigDecimal.valueOf(99),
                null,
                "淘宝",
                false,
                "旗舰店",
                4.8,
                1000,
                0.9,
                List.of(),
                "https://example.com/detail",
                "品牌",
                "none",
                null,
                "充电宝",
                "main");
    }
}
