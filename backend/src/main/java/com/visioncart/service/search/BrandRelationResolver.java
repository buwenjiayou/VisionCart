package com.visioncart.service.search;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class BrandRelationResolver {

    private static final List<BrandAlias> ACCESSORY_MAKERS = List.of(
            new BrandAlias("Baseus", List.of("Baseus", "\u500d\u601d")),
            new BrandAlias("PITAKA", List.of("PITAKA")),
            new BrandAlias("\u95ea\u9b54", List.of("\u95ea\u9b54", "SmartDevil")),
            new BrandAlias("Anker", List.of("Anker")),
            new BrandAlias("Belkin", List.of("Belkin")),
            new BrandAlias("UGREEN", List.of("UGREEN", "\u7eff\u8054")),
            new BrandAlias("Momax", List.of("Momax", "\u6469\u7c73\u58eb"))
    );

    private static final List<BrandAlias> DEVICE_BRANDS = List.of(
            new BrandAlias("Apple", List.of("Apple", "\u82f9\u679c", "iPhone", "iPad")),
            new BrandAlias("iQOO", List.of("iQOO")),
            new BrandAlias("Huawei", List.of("Huawei", "\u534e\u4e3a", "Mate", "Pura")),
            new BrandAlias("Xiaomi", List.of("Xiaomi", "\u5c0f\u7c73", "Redmi")),
            new BrandAlias("OPPO", List.of("OPPO")),
            new BrandAlias("vivo", List.of("vivo")),
            new BrandAlias("Samsung", List.of("Samsung", "\u4e09\u661f")),
            new BrandAlias("Honor", List.of("Honor", "\u8363\u8000")),
            new BrandAlias("OnePlus", List.of("OnePlus", "\u4e00\u52a0")),
            new BrandAlias("Google", List.of("Google", "Pixel"))
    );

    public BrandResolution resolve(String rawBrand,
                                   ProductIntent.ProductRole productRole,
                                   String canonicalProduct,
                                   List<String> keywords,
                                   Map<String, String> attributes) {
        Map<String, String> attrs = attributes == null ? Map.of() : attributes;
        String brand = SearchTextUtils.useful(rawBrand);
        boolean reliable = "true".equalsIgnoreCase(attrs.get(SearchTextUtils.ATTR_BRAND_RELIABLE));
        BrandSource source = source(attrs, brand, reliable);
        String text = searchableText(brand, canonicalProduct, keywords, attrs);

        if (brand.isBlank()) {
            String inferred = firstAlias(ACCESSORY_MAKERS, text);
            if (inferred.isBlank()) {
                inferred = BrandMatcher.inferBrand(text, "");
            }
            if (inferred.isBlank()) {
                return BrandResolution.none();
            }
            if (productRole == ProductIntent.ProductRole.ACCESSORY_MAIN && isAccessoryMaker(inferred)) {
                return new BrandResolution(inferred, firstAlias(DEVICE_BRANDS, text),
                        BrandSource.TITLE_INFERRED, BrandRelation.PRODUCT_BRAND, false);
            }
            return new BrandResolution(inferred, "", BrandSource.TITLE_INFERRED,
                    BrandRelation.PRODUCT_BRAND, false);
        }

        if (productRole == ProductIntent.ProductRole.ACCESSORY_MAIN) {
            String accessoryMaker = firstAlias(ACCESSORY_MAKERS, text);
            String compatibleDevice = firstAliasExcluding(DEVICE_BRANDS, text, brand);

            if (isAccessoryMaker(brand)) {
                return new BrandResolution(brand, compatibleDevice, source,
                        BrandRelation.PRODUCT_BRAND, reliable);
            }

            if (!accessoryMaker.isBlank() && !BrandMatcher.sameBrand(accessoryMaker, brand)) {
                return new BrandResolution(accessoryMaker, brand, source,
                        BrandRelation.COMPATIBLE_BRAND, reliable);
            }

            String compatible = StringUtils.defaultIfBlank(compatibleDevice, brand);
            return new BrandResolution("", compatible, source,
                    BrandRelation.COMPATIBLE_BRAND, reliable);
        }

        return new BrandResolution(brand, "", source, BrandRelation.PRODUCT_BRAND, reliable);
    }

    private BrandSource source(Map<String, String> attrs, String brand, boolean reliable) {
        if (brand.isBlank()) {
            return BrandSource.NONE;
        }
        if ("true".equalsIgnoreCase(attrs.get(SearchTextUtils.ATTR_STRICT_INTENT))) {
            return BrandSource.USER_CORRECTION;
        }
        return reliable ? BrandSource.VERIFIED_VISION : BrandSource.RAW_VISION;
    }

    private String searchableText(String brand, String canonicalProduct, List<String> keywords, Map<String, String> attrs) {
        return Stream.concat(
                        Stream.of(brand, canonicalProduct,
                                attrs.get(SearchTextUtils.ATTR_CATEGORY),
                                attrs.get(SearchTextUtils.ATTR_KEYWORD),
                                attrs.get(SearchTextUtils.ATTR_KEYWORDS)),
                        keywords == null ? Stream.empty() : keywords.stream())
                .filter(Objects::nonNull)
                .map(SearchTextUtils::useful)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(" "));
    }

    private boolean isAccessoryMaker(String brand) {
        return ACCESSORY_MAKERS.stream().anyMatch(alias -> alias.matches(brand));
    }

    private String firstAlias(List<BrandAlias> aliases, String text) {
        return aliases.stream()
                .filter(alias -> alias.matchesIn(text))
                .map(BrandAlias::canonical)
                .findFirst()
                .orElse("");
    }

    private String firstAliasExcluding(List<BrandAlias> aliases, String text, String excluded) {
        return aliases.stream()
                .filter(alias -> !alias.matches(excluded))
                .filter(alias -> alias.matchesIn(text))
                .map(BrandAlias::canonical)
                .findFirst()
                .orElse("");
    }

    private record BrandAlias(String canonical, List<String> aliases) {
        boolean matches(String value) {
            return aliases.stream().anyMatch(alias -> BrandMatcher.sameBrand(alias, value)
                    || SearchTextUtils.containsNormalized(value, alias));
        }

        boolean matchesIn(String text) {
            return aliases.stream().anyMatch(alias -> SearchTextUtils.containsNormalized(text, alias));
        }
    }
}
