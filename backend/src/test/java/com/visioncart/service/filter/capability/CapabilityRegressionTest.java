package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test suite for capability evaluators.
 * Ensures that semantic filtering never produces false negatives
 * for common user queries.
 */
class CapabilityRegressionTest {

    private CapabilityRegistry registry;

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
        registry = new CapabilityRegistry(evaluators);
    }

    @Nested
    @DisplayName("可带上飞机 (airplane_allowed)")
    class AirplaneAllowedTests {

        @Test
        @DisplayName("20000mAh 充电宝应该匹配可带上飞机")
        void powerBank20000mAh_shouldMatch() {
            ProductCard product = product("小米充电宝20000mAh", "小米", "充电宝", 99.0);
            CapabilityResult result = registry.evaluate("airplane_allowed", product, "充电宝");
            assertThat(result.matched()).isTrue();
            assertThat(result.confidence()).isGreaterThan(0.7);
        }

        @Test
        @DisplayName("100Wh 以下充电宝应该匹配")
        void powerBank100Wh_shouldMatch() {
            ProductCard product = product("Anker充电宝 37Wh 10000mAh", "Anker", "充电宝", 129.0);
            CapabilityResult result = registry.evaluate("airplane_allowed", product, "充电宝");
            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("空格分隔容量 20 000mAh 应该匹配")
        void powerBankSpaceSeparatedMah_shouldMatch() {
            ProductCard product = product("Anker充电宝 20 000mAh 3.85V", "Anker", "充电宝", 169.0);
            CapabilityResult result = registry.evaluate("airplane_allowed", product, "充电宝");
            assertThat(result.matched()).isTrue();
            assertThat(result.evidence()).anyMatch(text -> text.contains("20.000mAh") || text.contains("20000mAh"));
        }

        @Test
        @DisplayName("2.0万毫安充电宝应该匹配")
        void powerBankWanMah_shouldMatch() {
            ProductCard product = product("小米充电宝 2.0万毫安 100Wh以下", "小米", "充电宝", 129.0);
            CapabilityResult result = registry.evaluate("airplane_allowed", product, "充电宝");
            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("额定能量74Wh应该匹配")
        void ratedEnergy74Wh_shouldMatch() {
            ProductCard product = product("充电宝 额定能量74Wh 可登机", "Anker", "充电宝", 159.0);
            CapabilityResult result = registry.evaluate("airplane_allowed", product, "充电宝");
            assertThat(result.matched()).isTrue();
            assertThat(result.evidence()).anyMatch(text -> text.contains("74.0Wh"));
        }

        @Test
        @DisplayName("户外电源不应该匹配")
        void outdoorPowerStation_shouldNotMatch() {
            ProductCard product = product("ECOFLOW户外电源 1500Wh", "ECOFLOW", "户外电源", 3999.0);
            CapabilityResult result = registry.evaluate("airplane_allowed", product, "户外电源");
            assertThat(result.matched()).isFalse();
        }

        @Test
        @DisplayName("220V AC输出电源站不应该匹配")
        void acOutputPowerStation_shouldNotMatch() {
            ProductCard product = product("便携电源站 220V AC输出", "ECOFLOW", "电源站", 2999.0);
            CapabilityResult result = registry.evaluate("airplane_allowed", product, "电源站");
            assertThat(result.matched()).isFalse();
        }
    }

    @Nested
    @DisplayName("防水 (waterproof)")
    class WaterproofTests {

        @Test
        @DisplayName("IP68 手机应该匹配防水")
        void ip68Phone_shouldMatch() {
            ProductCard product = product("iPhone 15 Pro IP68防水", "Apple", "手机", 7999.0);
            CapabilityResult result = registry.evaluate("waterproof", product, "手机");
            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("防水背包应该匹配")
        void waterproofBag_shouldMatch() {
            ProductCard product = product("防水背包 户外骑行双肩包", "探路者", "背包", 199.0);
            CapabilityResult result = registry.evaluate("waterproof", product, "背包");
            assertThat(result.matched()).isTrue();
        }
    }

    @Nested
    @DisplayName("快充 (fast_charging)")
    class FastChargingTests {

        @Test
        @DisplayName("65W 充电器应该匹配快充")
        void charger65W_shouldMatch() {
            ProductCard product = product("小米65W氮化镓充电器", "小米", "充电器", 99.0);
            CapabilityResult result = registry.evaluate("fast_charging", product, "充电器");
            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("5W 普通充电器不应该匹配快充")
        void charger5W_shouldNotMatch() {
            ProductCard product = product("苹果5W充电器", "Apple", "充电器", 49.0);
            CapabilityResult result = registry.evaluate("fast_charging", product, "充电器");
            assertThat(result.matched()).isFalse();
        }
    }

    @Nested
    @DisplayName("护眼 (eye_protection)")
    class EyeProtectionTests {

        @Test
        @DisplayName("护眼台灯应该匹配")
        void eyeCareLamp_shouldMatch() {
            ProductCard product = product("明基护眼台灯 无频闪 防蓝光", "明基", "台灯", 999.0);
            CapabilityResult result = registry.evaluate("eye_protection", product, "台灯");
            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("装饰灯不应该匹配护眼")
        void decorativeLamp_shouldNotMatch() {
            ProductCard product = product("LED氛围灯 彩色装饰灯", "无品牌", "装饰灯", 29.0);
            CapabilityResult result = registry.evaluate("eye_protection", product, "装饰灯");
            assertThat(result.matched()).isFalse();
        }
    }

    @Nested
    @DisplayName("适合跑步 (running_suitable)")
    class RunningSuitableTests {

        @Test
        @DisplayName("跑鞋应该匹配适合跑步")
        void runningShoe_shouldMatch() {
            ProductCard product = product("Nike Air Zoom 跑步鞋 缓震透气", "Nike", "运动鞋", 599.0);
            CapabilityResult result = registry.evaluate("running_suitable", product, "运动鞋");
            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("皮鞋不应该匹配适合跑步")
        void leatherShoe_shouldNotMatch() {
            ProductCard product = product("男士商务正装皮鞋", "红蜻蜓", "皮鞋", 299.0);
            CapabilityResult result = registry.evaluate("running_suitable", product, "皮鞋");
            assertThat(result.matched()).isFalse();
        }
    }

    @Nested
    @DisplayName("婴儿安全 (baby_safe)")
    class BabySafeTests {

        @Test
        @DisplayName("婴儿奶瓶应该匹配")
        void babyBottle_shouldMatch() {
            ProductCard product = product("贝亲婴儿奶瓶 食品级PPSU", "贝亲", "奶瓶", 129.0);
            CapabilityResult result = registry.evaluate("baby_safe", product, "奶瓶");
            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("成人酒精不应该匹配婴儿安全")
        void alcohol_shouldNotMatch() {
            ProductCard product = product("医用酒精75%消毒液", "利尔康", "消毒液", 19.0);
            CapabilityResult result = registry.evaluate("baby_safe", product, "消毒液");
            assertThat(result.matched()).isFalse();
        }
    }

    @Nested
    @DisplayName("降噪 (noise_cancelling)")
    class NoiseCancellingTests {

        @Test
        @DisplayName("主动降噪耳机应该匹配")
        void ancEarphone_shouldMatch() {
            ProductCard product = product("Sony WH-1000XM5 主动降噪耳机", "Sony", "耳机", 1999.0);
            CapabilityResult result = registry.evaluate("noise_cancelling", product, "耳机");
            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("开放式耳机不应该匹配降噪")
        void openEarphone_shouldNotMatch() {
            ProductCard product = product("开放式不入耳蓝牙耳机 通透模式", "无品牌", "耳机", 99.0);
            CapabilityResult result = registry.evaluate("noise_cancelling", product, "耳机");
            assertThat(result.matched()).isFalse();
        }
    }

    @Nested
    @DisplayName("长续航 (long_battery)")
    class LongBatteryTests {

        @Test
        @DisplayName("大容量充电宝应该匹配长续航")
        void largePowerBank_shouldMatch() {
            ProductCard product = product("小米充电宝 20000mAh", "小米", "充电宝", 99.0);
            CapabilityResult result = registry.evaluate("long_battery", product, "充电宝");
            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("5000mAh 手机应该匹配长续航")
        void phone5000mAh_shouldMatch() {
            ProductCard product = product("Redmi Note 12 5000mAh大电池", "Redmi", "手机", 999.0);
            CapabilityResult result = registry.evaluate("long_battery", product, "手机");
            assertThat(result.matched()).isTrue();
        }
    }

    @Nested
    @DisplayName("适合送礼 (gift_friendly)")
    class GiftFriendlyTests {

        @Test
        @DisplayName("礼盒装商品应该匹配送礼")
        void giftBox_shouldMatch() {
            ProductCard product = product("高端礼盒装茶叶 送礼佳品", "八马茶业", "茶叶", 299.0);
            CapabilityResult result = registry.evaluate("gift_friendly", product, "茶叶");
            assertThat(result.matched()).isTrue();
        }

        @Test
        @DisplayName("散装试用装不应该匹配送礼")
        void samplePack_shouldNotMatch() {
            ProductCard product = product("散装试用装小样", "无品牌", "试用装", 9.0);
            CapabilityResult result = registry.evaluate("gift_friendly", product, "试用装");
            assertThat(result.matched()).isFalse();
        }
    }

    @Nested
    @DisplayName("适合通勤 (commuting_friendly)")
    class CommutingFriendlyTests {

        @Test
        @DisplayName("通勤背包应该匹配")
        void commutingBag_shouldMatch() {
            ProductCard product = product("商务通勤双肩包 轻便便携", "小米", "背包", 149.0);
            CapabilityResult result = registry.evaluate("commuting_friendly", product, "背包");
            assertThat(result.matched()).isTrue();
        }
    }

    // Helper to create test products
    private ProductCard product(String title, String brand, String category, double price) {
        return new ProductCard(
                "test-" + title.hashCode(),
                title,
                "https://example.com/img.jpg",
                BigDecimal.valueOf(price),
                null,  // originalPrice
                "pdd",
                false, // selfOperated
                null,  // shopName
                4.8,   // rating
                1000L, // sales
                0.95,  // similarity
                List.of(category),
                "https://example.com/detail",
                brand,
                "none",  // ratingSource
                null,    // salesLabel
                category, // mainCategoryCode
                "main"    // productRole
        );
    }
}
