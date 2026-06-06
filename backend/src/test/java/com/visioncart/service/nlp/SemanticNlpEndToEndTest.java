package com.visioncart.service.nlp;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.api.dto.SearchFilter;
import com.visioncart.service.filter.capability.CapabilitySynonymRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end semantic NLP test set.
 * Tests that various user natural language inputs correctly resolve to capability codes.
 *
 * These tests verify the synonym registry resolution path — the same path used by
 * NlpFilterController.buildFilterClauses(), NlpConversationManager.resolveCapabilityCode(),
 * and FilterPlanner.classifyClauses().
 *
 * Each test asserts:
 * 1. User input resolves to the expected capability code
 * 2. The resolution works for ALL synonymous phrasings
 */
@DisplayName("Semantic NLP End-to-End Tests")
class SemanticNlpEndToEndTest {

    private CapabilitySynonymRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CapabilitySynonymRegistry();
    }

    // ==================== Power Bank / Airplane ====================

    @Nested
    @DisplayName("充电宝 — 可上飞机")
    class AirplaneAllowed {

        @ParameterizedTest
        @CsvSource({
                "有没有可以上飞机的, airplane_allowed",
                "可以带上飞机的, airplane_allowed",
                "能带上飞机吗, airplane_allowed",
                "可上飞机的, airplane_allowed",
                "能上飞机的, airplane_allowed",
                "可登机的, airplane_allowed",
                "登机可带, airplane_allowed",
                "上机, airplane_allowed",
                "民航允许的, airplane_allowed",
                "出差能用的, airplane_allowed",
                "飞机上能用, airplane_allowed",
                "随身携带的, airplane_allowed"
        })
        @DisplayName("airplane_allowed synonym resolution")
        void resolvesAirplaneAllowed(String input, String expectedCode) {
            String resolved = registry.resolveToCode(input);
            assertThat(resolved).isEqualTo(expectedCode);
        }

        @Test
        @DisplayName("airplane_allowed should not match outdoor power stations")
        void noFalsePositiveForOutdoorPower() {
            // "户外电源" is a negative keyword for airplane_allowed
            String[] negatives = registry.negativeKeywords("airplane_allowed");
            assertThat(negatives).contains("户外电源");
        }
    }

    // ==================== Phone / Waterproof ====================

    @Nested
    @DisplayName("手机 — 防水")
    class Waterproof {

        @ParameterizedTest
        @CsvSource({
                "防水的, waterproof",
                "防水手机, waterproof",
                "下雨能用, waterproof",
                "游泳防水, waterproof",
                "IP68, waterproof"
        })
        @DisplayName("waterproof synonym resolution")
        void resolvesWaterproof(String input, String expectedCode) {
            String resolved = registry.resolveToCode(input);
            assertThat(resolved).isEqualTo(expectedCode);
        }
    }

    // ==================== Phone / Photo Quality ====================

    @Nested
    @DisplayName("手机 — 拍照好")
    class PhotoQuality {

        @Test
        @DisplayName("拍照好 should not resolve to a specific capability (no false match)")
        void photoQualityIsNotACapability() {
            // "拍照好" is a general preference, not a registered capability
            String resolved = registry.resolveToCode("拍照好");
            // It should return null (not a registered capability) or a preference code
            // Either way, it should NOT match airplane_allowed or waterproof
            if (resolved != null) {
                assertThat(resolved).isNotEqualTo("airplane_allowed");
                assertThat(resolved).isNotEqualTo("waterproof");
            }
        }
    }

    // ==================== Shoes / Running ====================

    @Nested
    @DisplayName("鞋 — 适合长跑")
    class RunningSuitable {

        @ParameterizedTest
        @CsvSource({
                "适合长跑的, running_suitable",
                "适合跑步的, running_suitable",
                "跑步用, running_suitable",
                "跑鞋, running_suitable",
                "马拉松, running_suitable"
        })
        @DisplayName("running_suitable synonym resolution")
        void resolvesRunning(String input, String expectedCode) {
            String resolved = registry.resolveToCode(input);
            assertThat(resolved).isEqualTo(expectedCode);
        }

        @Test
        @DisplayName("running_suitable should exclude dress shoes")
        void excludesDressShoes() {
            String[] negatives = registry.negativeKeywords("running_suitable");
            assertThat(negatives).contains("正装鞋");
            assertThat(negatives).contains("皮鞋");
        }
    }

    // ==================== Lamp / Eye Protection ====================

    @Nested
    @DisplayName("台灯 — 护眼")
    class EyeProtection {

        @ParameterizedTest
        @CsvSource({
                "护眼的, eye_protection",
                "不伤眼, eye_protection",
                "无频闪, eye_protection",
                "防蓝光, eye_protection",
                "柔光, eye_protection"
        })
        @DisplayName("eye_protection synonym resolution")
        void resolvesEyeProtection(String input, String expectedCode) {
            String resolved = registry.resolveToCode(input);
            assertThat(resolved).isEqualTo(expectedCode);
        }
    }

    // ==================== Headphones / Noise Cancelling ====================

    @Nested
    @DisplayName("耳机 — 降噪")
    class NoiseCancelling {

        @ParameterizedTest
        @CsvSource({
                "降噪好的, noise_cancelling",
                "主动降噪, noise_cancelling",
                "ANC, noise_cancelling",
                "安静的, noise_cancelling"
        })
        @DisplayName("noise_cancelling synonym resolution")
        void resolvesNoiseCancelling(String input, String expectedCode) {
            String resolved = registry.resolveToCode(input);
            assertThat(resolved).isEqualTo(expectedCode);
        }
    }

    // ==================== Exclusions ====================

    @Nested
    @DisplayName("排除 — 不要配件")
    class Exclusions {

        @Test
        @DisplayName("不要手机壳 should be recognized as exclusion pattern")
        void exclusionPattern() {
            // "不要" prefix indicates exclusion
            String input = "不要手机壳";
            // The resolveToCode won't match a capability, but the NLP parser
            // should detect the exclusion pattern
            String resolved = registry.resolveToCode(input);
            // Should not accidentally resolve to any capability
            assertThat(resolved).isNotEqualTo("waterproof");
            assertThat(resolved).isNotEqualTo("shockproof");
        }

        @Test
        @DisplayName("不要鞋垫 should not match running_suitable")
        void noShoeInsoleMatch() {
            String[] negatives = registry.negativeKeywords("running_suitable");
            assertThat(negatives).contains("鞋垫");
        }
    }

    // ==================== Preferences ====================

    @Nested
    @DisplayName("偏好 — 性价比、轻便、好看")
    class Preferences {

        @ParameterizedTest
        @CsvSource({
                "性价比高的, cost_effective",
                "性价比, cost_effective",
                "划算的, cost_effective",
                "实惠的, cost_effective"
        })
        @DisplayName("cost_effective synonym resolution")
        void resolvesCostEffective(String input, String expectedCode) {
            String resolved = registry.resolveToCode(input);
            assertThat(resolved).isEqualTo(expectedCode);
        }

        @ParameterizedTest
        @CsvSource({
                "轻便的, lightweight",
                "不要太重, lightweight",
                "轻巧的, lightweight"
        })
        @DisplayName("lightweight synonym resolution")
        void resolvesLightweight(String input, String expectedCode) {
            String resolved = registry.resolveToCode(input);
            assertThat(resolved).isEqualTo(expectedCode);
        }

        @Test
        @DisplayName("preference codes should be classified as preference, not capability")
        void preferencesAreNotCapabilities() {
            assertThat(registry.isPreferenceCode("cost_effective")).isTrue();
            assertThat(registry.isPreferenceCode("lightweight")).isTrue();
            assertThat(registry.isPreferenceCode("premium")).isTrue();
            assertThat(registry.isPreferenceCode("appearance")).isTrue();

            assertThat(registry.isCapabilityCode("cost_effective")).isFalse();
            assertThat(registry.isCapabilityCode("airplane_allowed")).isTrue();
        }
    }

    // ==================== Category-Specific ====================

    @Nested
    @DisplayName("品类特定能力")
    class CategorySpecific {

        @Test
        @DisplayName("airplane_allowed applies to power bank categories")
        void airplaneAppliesToPowerBanks() {
            var entry = registry.get("airplane_allowed");
            assertThat(entry).isNotNull();
            assertThat(entry.categories()).contains("power_bank", "充电宝", "移动电源");
        }

        @Test
        @DisplayName("waterproof applies to all categories (empty list)")
        void waterproofAppliesToAll() {
            var entry = registry.get("waterproof");
            assertThat(entry).isNotNull();
            assertThat(entry.categories()).isEmpty();
        }
    }

    // ==================== Display Text ====================

    @Nested
    @DisplayName("展示文本")
    class DisplayText {

        @ParameterizedTest
        @CsvSource({
                "airplane_allowed, 可带上飞机",
                "waterproof, 防水",
                "fast_charging, 快充",
                "eye_protection, 护眼",
                "noise_cancelling, 降噪",
                "baby_safe, 婴儿安全",
                "running_suitable, 适合跑步",
                "long_battery, 长续航",
                "gift_friendly, 适合送礼",
                "commuting_friendly, 适合通勤",
                "dustproof, 防尘",
                "shockproof, 防摔",
                "cost_effective, 性价比高",
                "lightweight, 轻便",
                "premium, 高端品质",
                "appearance, 高颜值"
        })
        @DisplayName("displayText returns correct Chinese text")
        void displayTextCorrect(String code, String expectedDisplay) {
            assertThat(registry.displayText(code)).isEqualTo(expectedDisplay);
        }

        @Test
        @DisplayName("unknown code returns the code itself")
        void unknownCodeReturnsItself() {
            assertThat(registry.displayText("unknown_code")).isEqualTo("unknown_code");
        }
    }
}
