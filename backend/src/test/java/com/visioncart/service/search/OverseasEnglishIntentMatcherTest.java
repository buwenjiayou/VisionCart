package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OverseasEnglishIntentMatcherTest {

    private final OverseasEnglishIntentMatcher matcher = new OverseasEnglishIntentMatcher();

    @Test
    void mouseIntentKeepsMouseAndRejectsAccessories() {
        ProductIntent intent = intent("\u9f20\u6807", "mouse");

        assertThat(matcher.classify(intent, product("Logitech wireless mouse")))
                .isEqualTo(IntentGate.IntentTier.EXACT_MAIN);
        assertThat(matcher.classify(intent, product("Large gaming mouse pad desk mat")))
                .isEqualTo(IntentGate.IntentTier.REJECT);
        assertThat(matcher.classify(intent, product("Mouse skates feet for Logitech")))
                .isEqualTo(IntentGate.IntentTier.REJECT);
        assertThat(matcher.classify(intent, product("Universal grip tape for mouse")))
                .isEqualTo(IntentGate.IntentTier.REJECT);
    }

    @Test
    void powerBankIntentKeepsFinishedProductsAndRejectsComponents() {
        ProductIntent intent = intent("\u5145\u7535\u5b9d", "power_bank");

        assertThat(matcher.classify(intent, product("20000mAh power bank USB-C PD fast charger")))
                .isEqualTo(IntentGate.IntentTier.EXACT_MAIN);
        assertThat(matcher.classify(intent, product("20000mAh power bank with built-in USB-C charging cable")))
                .isEqualTo(IntentGate.IntentTier.EXACT_MAIN);
        assertThat(matcher.classify(intent, product("Portable charger battery pack for phone")))
                .isNotEqualTo(IntentGate.IntentTier.REJECT);
        assertThat(matcher.classify(intent, product("USB-C charging cable for power bank")))
                .isEqualTo(IntentGate.IntentTier.REJECT);
        assertThat(matcher.classify(intent, product("18650 battery cell rechargeable lithium")))
                .isEqualTo(IntentGate.IntentTier.REJECT);
        assertThat(matcher.classify(intent, product("PCB module boost board for power bank")))
                .isEqualTo(IntentGate.IntentTier.REJECT);
        assertThat(matcher.classify(intent, product("Power bank case shell DIY kit")))
                .isEqualTo(IntentGate.IntentTier.REJECT);
    }

    @Test
    void phoneCaseIntentKeepsCasesButRejectsPhones() {
        ProductIntent intent = intent("\u624b\u673a\u58f3", "phone_case");

        assertThat(matcher.classify(intent, product("Clear MagSafe iPhone 15 phone case")))
                .isEqualTo(IntentGate.IntentTier.EXACT_MAIN);
        assertThat(matcher.classify(intent, product("Apple iPhone 15 unlocked smartphone 128GB")))
                .isEqualTo(IntentGate.IntentTier.REJECT);
    }

    @Test
    void overseasSortPrefersEnglishFeatureMatchesInsideTier() {
        ProductIntent intent = new ProductIntent(
                "s", "\u5145\u7535\u5b9d", "power_bank",
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "", "", "",
                false,
                Map.of("\u89c4\u683c", "20000mAh"),
                Map.of("\u6b3e\u5f0f", "transparent shell LED indicator"),
                List.of("PD fast charge"),
                List.of(),
                List.of(),
                1.0,
                "test"
        );

        ProductCard generic = product("Generic portable power bank");
        ProductCard close = product("Transparent 20000mAh power bank USB-C PD LED display");

        assertThat(matcher.sortWithinTier(intent, List.of(generic, close)))
                .containsExactly(close, generic);
    }

    private ProductIntent intent(String canonicalProduct, String family) {
        return new ProductIntent(
                "s", canonicalProduct, family,
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "", "", "",
                false,
                Map.of(), Map.of(),
                List.of(), List.of(),
                List.of(),
                1.0,
                "test"
        );
    }

    private ProductCard product(String title) {
        return new ProductCard(
                title.toLowerCase().replaceAll("[^a-z0-9]+", "-"),
                title,
                "",
                BigDecimal.valueOf(19),
                null,
                "eBay",
                false,
                "seller",
                4.6,
                100,
                0.8,
                List.of(),
                "https://example.com",
                null,
                "none",
                null
        );
    }
}
