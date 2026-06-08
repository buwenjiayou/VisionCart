package com.visioncart.service.search.strategy;

public record MixPolicy(
        int topWindowSize,
        int maxRelatedInTopWindow,
        int maxRelatedTotal
) {
    public static MixPolicy defaults() {
        return new MixPolicy(20, 2, 5);
    }

    public static MixPolicy strictAccessory() {
        return new MixPolicy(20, 2, 5);
    }
}
