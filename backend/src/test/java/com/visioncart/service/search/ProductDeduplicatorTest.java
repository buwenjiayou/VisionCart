package com.visioncart.service.search;

import com.visioncart.api.dto.ProductCard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductDeduplicatorTest {

    @Test
    void removesDuplicateProductsByNormalizedTitleAndShop() {
        ProductDeduplicator deduplicator = new ProductDeduplicator();

        List<ProductCard> products = List.of(
                product("1", "Nike Pegasus 41", "淘宝"),
                product("2", "Nike Pegasus 41", "淘宝"),
                product("3", "Nike Pegasus 41", "拼多多")
        );

        // Products with same title + shop ("官方旗舰店") are deduplicated regardless of platform
        assertThat(deduplicator.deduplicate(products))
                .extracting(ProductCard::id)
                .containsExactly("1");
    }

    @Test
    void keepsDifferentModelTokensSeparate() {
        ProductDeduplicator deduplicator = new ProductDeduplicator();

        List<ProductCard> products = List.of(
                product("15", "Premium clear protective shell for Apple iPhone 15 Pro Max", "taobao"),
                product("16", "Premium clear protective shell for Apple iPhone 16 Pro Max", "taobao"),
                product("4060", "Gaming graphics card NVIDIA RTX 4060 OC", "taobao"),
                product("4070", "Gaming graphics card NVIDIA RTX 4070 OC", "taobao")
        );

        assertThat(deduplicator.deduplicate(products))
                .extracting(ProductCard::id)
                .contains("15", "16", "4060", "4070");
    }

    private ProductCard product(String id, String title, String platform) {
        return new ProductCard(
                id,
                title,
                "",
                BigDecimal.valueOf(399),
                null,
                platform,
                false,
                "官方旗舰店",
                4.8,
                100,
                0.9,
                List.of(platform),
                "https://example.com/products/" + id
        );
    }
}
