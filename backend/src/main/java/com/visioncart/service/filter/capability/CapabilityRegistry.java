package com.visioncart.service.filter.capability;

import com.visioncart.api.dto.ProductCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Registry of all capability evaluators.
 * Provides lookup by capability code and category.
 */
@Component
public class CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);

    private final Map<String, List<CapabilityEvaluator>> evaluatorsByCapability = new HashMap<>();
    private final Map<String, List<CapabilityEvaluator>> evaluatorsByCategory = new HashMap<>();

    public CapabilityRegistry(List<CapabilityEvaluator> evaluatorList) {
        for (CapabilityEvaluator evaluator : evaluatorList) {
            evaluatorsByCapability
                    .computeIfAbsent(evaluator.capabilityCode(), k -> new ArrayList<>())
                    .add(evaluator);
        }
        log.info("CapabilityRegistry: registered {} evaluators for capabilities: {}",
                evaluatorList.size(), evaluatorsByCapability.keySet());
    }

    /**
     * Find evaluators for a given capability code.
     */
    public List<CapabilityEvaluator> findByCapability(String capabilityCode) {
        return evaluatorsByCapability.getOrDefault(capabilityCode, List.of());
    }

    /**
     * Evaluate a product against a capability. Returns the best matching evaluator's result.
     */
    public CapabilityResult evaluate(String capabilityCode, ProductCard product, String category) {
        List<CapabilityEvaluator> evaluators = findByCapability(capabilityCode);
        if (evaluators.isEmpty()) {
            log.debug("No evaluator found for capability: {}", capabilityCode);
            return CapabilityResult.uncertain(0.3, List.of("无专用评估器"), "该能力暂无精确评估");
        }

        // Find the first evaluator that supports this category
        for (CapabilityEvaluator evaluator : evaluators) {
            if (evaluator.supports(category)) {
                return evaluator.evaluate(product, category);
            }
        }

        // If no evaluator supports this category, try the first one as a generic fallback
        CapabilityEvaluator fallback = evaluators.get(0);
        log.debug("No category-specific evaluator for {}:{}, using generic fallback", capabilityCode, category);
        return fallback.evaluate(product, category);
    }

    /**
     * Get all registered capability codes.
     */
    public Set<String> getRegisteredCapabilities() {
        return evaluatorsByCapability.keySet();
    }
}
