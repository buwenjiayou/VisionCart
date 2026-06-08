package com.visioncart.service.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrandRelationResolverTest {

    private final BrandRelationResolver resolver = new BrandRelationResolver();

    @Test
    void reliableBrandRequiresFlagAndValue() {
        ProductIntent raw = new ProductIntent(
                "s", "\u624b\u673a", "phone", ProductIntent.ProductRole.MAIN_PRODUCT,
                "Apple", "", "", false,
                Map.of(), Map.of(), List.of(), List.of(), List.of(), 0.7, "test");
        ProductIntent verified = new ProductIntent(
                "s", "\u624b\u673a", "phone", ProductIntent.ProductRole.MAIN_PRODUCT,
                "Apple", "", "", true,
                Map.of(), Map.of(), List.of(), List.of(), List.of(), 1.0, "test");

        assertThat(raw.hasReliableBrand()).isFalse();
        assertThat(verified.hasReliableBrand()).isTrue();
    }

    @Test
    void baseusIqooCaseSplitsProductAndCompatibleBrand() {
        BrandResolution resolution = resolver.resolve(
                "\u500d\u601d",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "\u624b\u673a\u58f3",
                List.of("iQOO", "\u624b\u673a\u58f3"),
                Map.of(SearchTextUtils.ATTR_BRAND_RELIABLE, "true"));

        assertThat(resolution.productBrand()).isEqualTo("\u500d\u601d");
        assertThat(resolution.compatibleBrand()).isEqualTo("iQOO");
        assertThat(resolution.reliable()).isTrue();
        assertThat(resolution.relation()).isEqualTo(BrandRelation.PRODUCT_BRAND);
    }

    @Test
    void pitakaAppleCaseKeepsAccessoryMakerAsProductBrand() {
        BrandResolution resolution = resolver.resolve(
                "PITAKA",
                ProductIntent.ProductRole.ACCESSORY_MAIN,
                "\u624b\u673a\u58f3",
                List.of("\u82f9\u679c", "\u624b\u673a\u58f3"),
                Map.of(SearchTextUtils.ATTR_BRAND_RELIABLE, "true"));

        assertThat(resolution.productBrand()).isEqualTo("PITAKA");
        assertThat(resolution.compatibleBrand()).isEqualTo("Apple");
    }

    @Test
    void applePhoneMainProductUsesProductBrand() {
        BrandResolution resolution = resolver.resolve(
                "Apple",
                ProductIntent.ProductRole.MAIN_PRODUCT,
                "\u624b\u673a",
                List.of("iPhone"),
                Map.of(SearchTextUtils.ATTR_BRAND_RELIABLE, "true"));

        assertThat(resolution.productBrand()).isEqualTo("Apple");
        assertThat(resolution.compatibleBrand()).isBlank();
        assertThat(resolution.relation()).isEqualTo(BrandRelation.PRODUCT_BRAND);
    }
}
