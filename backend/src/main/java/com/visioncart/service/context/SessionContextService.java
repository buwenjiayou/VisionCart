package com.visioncart.service.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.AttributeValue;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.search.CandidateSessionCache;
import com.visioncart.service.search.CategoryNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unified session context provider for all action entry points.
 * Server-side history is preferred, candidate cache is the fallback, and
 * client-supplied category is only used when the server cannot resolve context.
 */
@Service
public class SessionContextService {

    private static final Logger log = LoggerFactory.getLogger(SessionContextService.class);

    /** Map of main_category_code → normalized category for capability evaluators. */
    private static final Map<String, String> CATEGORY_MAP = Map.ofEntries(
            Map.entry("power_bank", "power_bank"),
            Map.entry("充电宝", "power_bank"),
            Map.entry("phone", "phone"),
            Map.entry("手机", "phone"),
            Map.entry("laptop", "laptop"),
            Map.entry("笔记本", "laptop"),
            Map.entry("tablet", "tablet"),
            Map.entry("平板", "tablet"),
            Map.entry("earphone", "earphone"),
            Map.entry("耳机", "earphone"),
            Map.entry("headphone", "earphone"),
            Map.entry("charger", "charger"),
            Map.entry("充电器", "charger"),
            Map.entry("lamp", "lamp"),
            Map.entry("台灯", "lamp"),
            Map.entry("monitor", "monitor"),
            Map.entry("显示器", "monitor"),
            Map.entry("screen_protector", "screen_protector"),
            Map.entry("贴膜", "screen_protector"),
            Map.entry("shoe", "shoe"),
            Map.entry("鞋", "shoe"),
            Map.entry("bag", "bag"),
            Map.entry("包", "bag"),
            Map.entry("baby_product", "baby_product"),
            Map.entry("母婴", "baby_product"),
            Map.entry("watch", "watch"),
            Map.entry("手表", "watch"),
            Map.entry("camera", "camera"),
            Map.entry("相机", "camera"),
            Map.entry("speaker", "speaker"),
            Map.entry("音箱", "speaker"),
            Map.entry("keyboard", "keyboard"),
            Map.entry("键盘", "keyboard"),
            Map.entry("mouse", "mouse"),
            Map.entry("鼠标", "mouse"),
            Map.entry("cable", "cable"),
            Map.entry("数据线", "cable"),
            Map.entry("storage", "storage"),
            Map.entry("收纳", "storage")
    );

    private final RecognitionHistoryRepository historyRepository;
    private final CandidateSessionCache sessionCache;
    private final ObjectMapper objectMapper;

    public SessionContextService(RecognitionHistoryRepository historyRepository,
                                 CandidateSessionCache sessionCache,
                                 ObjectMapper objectMapper) {
        this.historyRepository = historyRepository;
        this.sessionCache = sessionCache;
        this.objectMapper = objectMapper;
    }

    /**
     * Build a stable session context from all available sources.
     *
     * @param sessionId       The session ID
     * @param clientCategory  Category from client request (may be null)
     * @param clientRoles     Allowed product roles from client (may be null)
     * @return Normalized context
     */
    public SessionContext resolve(String sessionId, String clientCategory, List<String> clientRoles) {
        SessionContext serverContext = resolve(sessionId);
        if (serverContext.resolved()) {
            return withClientRoles(serverContext, clientRoles);
        }
        return withClientFallback(serverContext, clientCategory, clientRoles);
    }

    public SessionContext resolve(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionContext.empty(sessionId);
        }
        if (userId != null) {
            Optional<RecognitionHistory> history = historyRepository.findBySessionIdAndUserId(sessionId, userId);
            if (history.isPresent()) {
                return fromHistory(sessionId, history.get());
            }
        }
        SessionContext fromCandidates = fromCandidates(sessionId, sessionCache.getCandidates(sessionId));
        if (fromCandidates.resolved()) {
            return fromCandidates;
        }
        log.debug("SessionContext[{}]: unresolved", sessionId);
        return SessionContext.empty(sessionId);
    }

    public SessionContext resolve(String sessionId, Long userId, String clientCategory) {
        SessionContext serverContext = resolve(sessionId, userId);
        if (serverContext.resolved()) {
            return serverContext;
        }
        return withClientFallback(serverContext, clientCategory, null);
    }

    /**
     * Quick resolve with no client hints.
     */
    public SessionContext resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionContext.empty(sessionId);
        }
        SessionContext fromCandidates = fromCandidates(sessionId, sessionCache.getCandidates(sessionId));
        return fromCandidates.resolved() ? fromCandidates : SessionContext.empty(sessionId);
    }

    /**
     * Normalize a category string to a canonical form.
     */
    public String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim().toLowerCase();
        // Direct match
        String mapped = CATEGORY_MAP.get(trimmed);
        if (mapped != null) return mapped;
        // Partial match
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            if (trimmed.contains(entry.getKey()) || entry.getKey().contains(trimmed)) {
                return entry.getValue();
            }
        }
        return trimmed; // Return as-is if no mapping found
    }

    private SessionContext withClientFallback(SessionContext base, String clientCategory, List<String> clientRoles) {
        String normalized = normalizeCategory(clientCategory);
        Set<String> roles = (clientRoles != null && !clientRoles.isEmpty())
                ? Set.copyOf(clientRoles)
                : base.allowedRoles();
        return new SessionContext(
                base.sessionId(),
                normalized != null ? normalized : base.normalizedCategory(),
                clientCategory != null && !clientCategory.isBlank() ? clientCategory : base.displayCategory(),
                base.mainProduct(),
                base.mainProductRole(),
                base.attributes(),
                roles,
                normalized != null || base.resolved(),
                base.locale()
        );
    }

    private SessionContext withClientRoles(SessionContext base, List<String> clientRoles) {
        if (clientRoles == null || clientRoles.isEmpty()) {
            return base;
        }
        return new SessionContext(base.sessionId(), base.normalizedCategory(), base.displayCategory(),
                base.mainProduct(), base.mainProductRole(), base.attributes(), Set.copyOf(clientRoles),
                base.resolved(), base.locale());
    }

    private SessionContext fromHistory(String sessionId, RecognitionHistory history) {
        String categoryFromJson = "";
        if (history.getCategoryJson() != null && !history.getCategoryJson().isBlank()) {
            try {
                var catNode = objectMapper.readTree(history.getCategoryJson());
                String level3 = catNode.has("level3") ? catNode.get("level3").asText("") : "";
                String level2 = catNode.has("level2") ? catNode.get("level2").asText("") : "";
                String level1 = catNode.has("level1") ? catNode.get("level1").asText("") : "";
                categoryFromJson = !level3.isBlank() ? level3 : !level2.isBlank() ? level2 : level1;
            } catch (Exception e) {
                log.debug("SessionContext[{}]: failed to parse categoryJson: {}", sessionId, e.getMessage());
            }
        }

        String keywords = history.getKeywords();
        String normalized = normalizeCategory(categoryFromJson);
        if (normalized == null && keywords != null && !keywords.isBlank()) {
            String fromText = CategoryNormalizer.normalizeFromText(keywords.toLowerCase());
            normalized = fromText.isBlank() ? null : fromText;
        }

        Map<String, String> attrs = parseAttributes(sessionId, history.getAttributesJson());
        String mainProduct = keywords != null && !keywords.isBlank() ? keywords.split(",")[0].trim() : "";
        String displayCategory = !categoryFromJson.isBlank()
                ? categoryFromJson
                : (normalized != null ? normalized : mainProduct);

        return new SessionContext(
                sessionId,
                normalized,
                displayCategory,
                mainProduct,
                "main",
                attrs,
                Set.of("main", "unknown"),
                true,
                "zh-CN"
        );
    }

    private Map<String, String> parseAttributes(String sessionId, String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, AttributeValue> parsed = objectMapper.readValue(
                    attributesJson, new TypeReference<Map<String, AttributeValue>>() {});
            Map<String, String> attrs = new LinkedHashMap<>();
            parsed.forEach((key, value) -> {
                if (value != null && value.value() != null
                        && !value.value().isBlank() && !"未知".equals(value.value())) {
                    attrs.put(key, value.value());
                }
            });
            return attrs;
        } catch (Exception e) {
            log.debug("SessionContext[{}]: failed to parse attributesJson: {}", sessionId, e.getMessage());
            return Map.of();
        }
    }

    private SessionContext fromCandidates(String sessionId, List<ProductCard> products) {
        if (products == null || products.isEmpty()) {
            return SessionContext.empty(sessionId);
        }

        List<ProductCard> mainProducts = products.stream()
                .filter(p -> !"accessory".equalsIgnoreCase(p.productRole())
                        && !"case".equalsIgnoreCase(p.productRole())
                        && !"part".equalsIgnoreCase(p.productRole()))
                .toList();
        if (mainProducts.isEmpty()) {
            mainProducts = products;
        }

        String normalizedCategory = null;
        for (ProductCard p : products) {
            if (p.mainCategoryCode() != null && !p.mainCategoryCode().isBlank()) {
                String normalized = normalizeCategory(p.mainCategoryCode());
                if (normalized != null) {
                    normalizedCategory = normalized;
                    break;
                }
            }
        }
        ProductCard sample = mainProducts.get(0);
        if (normalizedCategory == null && sample.title() != null) {
            String fromTitle = CategoryNormalizer.normalizeFromText(sample.title().toLowerCase());
            normalizedCategory = fromTitle.isBlank() ? null : fromTitle;
        }

        Map<String, String> attrs = new LinkedHashMap<>();
        String dominantBrand = mainProducts.stream()
                .map(ProductCard::brand)
                .filter(brand -> brand != null && !brand.isBlank())
                .collect(Collectors.groupingBy(brand -> brand, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        if (!dominantBrand.isBlank()) {
            attrs.put("brand", dominantBrand);
        }

        Set<String> roles = products.stream()
                .map(ProductCard::productRole)
                .filter(role -> role != null && !role.isBlank())
                .collect(Collectors.toSet());
        if (roles.isEmpty()) {
            roles = Set.of("main", "unknown");
        }

        return new SessionContext(
                sessionId,
                normalizedCategory,
                normalizedCategory != null ? normalizedCategory : "商品",
                sample.title() != null ? sample.title() : "商品",
                sample.productRole() != null ? sample.productRole() : "main",
                attrs,
                roles,
                normalizedCategory != null || !attrs.isEmpty(),
                "zh-CN"
        );
    }

    /**
     * Immutable session context.
     */
    public record SessionContext(
            String sessionId,
            String normalizedCategory,
            String displayCategory,
            String mainProduct,
            String mainProductRole,
            Map<String, String> attributes,
            Set<String> allowedRoles,
            boolean resolved,
            String locale
    ) {
        public static SessionContext empty(String sessionId) {
            return new SessionContext(sessionId, null, "", "", "main", Map.of(),
                    Set.of("main", "unknown"), false, "zh-CN");
        }

        public static SessionContext empty() {
            return empty(null);
        }

        /** Convenience: get category or fallback. */
        public String categoryOrDefault() {
            return normalizedCategory != null ? normalizedCategory : "unknown";
        }
    }
}
