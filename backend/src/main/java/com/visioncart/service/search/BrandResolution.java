package com.visioncart.service.search;

public record BrandResolution(
        String productBrand,
        String compatibleBrand,
        BrandSource source,
        BrandRelation relation,
        boolean reliable
) {
    public BrandResolution {
        productBrand = SearchTextUtils.useful(productBrand);
        compatibleBrand = SearchTextUtils.useful(compatibleBrand);
        source = source == null ? BrandSource.NONE : source;
        relation = relation == null ? BrandRelation.AMBIGUOUS : relation;
    }

    public static BrandResolution none() {
        return new BrandResolution("", "", BrandSource.NONE, BrandRelation.AMBIGUOUS, false);
    }
}
