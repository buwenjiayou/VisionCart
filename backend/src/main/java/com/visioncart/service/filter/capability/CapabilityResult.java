package com.visioncart.service.filter.capability;

import java.util.List;

/**
 * Result of evaluating a capability against a product.
 */
public record CapabilityResult(
    boolean matched,
    double confidence,
    List<String> evidence,
    String warning
) {
    public static CapabilityResult match(double confidence, List<String> evidence) {
        return new CapabilityResult(true, confidence, evidence, null);
    }

    public static CapabilityResult match(double confidence, List<String> evidence, String warning) {
        return new CapabilityResult(true, confidence, evidence, warning);
    }

    public static CapabilityResult noMatch(List<String> evidence) {
        return new CapabilityResult(false, 0.0, evidence, null);
    }

    public static CapabilityResult uncertain(double confidence, List<String> evidence, String warning) {
        return new CapabilityResult(confidence >= 0.5, confidence, evidence, warning);
    }
}
