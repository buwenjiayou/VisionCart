package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;

/**
 * Evaluates whether a product satisfies a specific capability.
 * Each capability (airplane_allowed, waterproof, etc.) has its own evaluator implementation.
 */
public interface CapabilityEvaluator {

    /**
     * The capability code this evaluator handles (e.g., "airplane_allowed", "waterproof").
     */
    String capabilityCode();

    /**
     * Whether this evaluator supports the given product category.
     * E.g., AirplaneAllowedEvaluator supports "power_bank" but not "shoes".
     */
    boolean supports(String categoryCode);

    /**
     * Evaluate whether the product satisfies this capability.
     */
    CapabilityResult evaluate(ProductCard product, String category);
}
