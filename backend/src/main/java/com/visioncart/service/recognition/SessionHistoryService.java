package com.visioncart.service.recognition;

import com.visioncart.api.dto.ProductCard;
import com.visioncart.domain.RecognitionHistoryProduct;
import com.visioncart.repository.RecognitionHistoryProductRepository;
import com.visioncart.service.search.CandidateSessionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 会话历史服务：在新会话创建时，将上一个会话的最终商品列表存入 MySQL 作为识别历史。
 * 商品来源：Redis sessionCache（包含 NLP 筛选后的最终结果）。
 */
@Service
public class SessionHistoryService {
    private static final Logger log = LoggerFactory.getLogger(SessionHistoryService.class);

    private final CandidateSessionCache sessionCache;
    private final RecognitionHistoryProductRepository productRepository;

    public SessionHistoryService(CandidateSessionCache sessionCache,
                                 RecognitionHistoryProductRepository productRepository) {
        this.sessionCache = sessionCache;
        this.productRepository = productRepository;
    }

    /**
     * 归档展示给用户的商品列表到 MySQL。
     * 每次搜索/NLP筛选返回结果后调用，覆盖写入（以最新为准）。
     *
     * @param sessionId 会话 ID
     * @param products  展示给用户的商品列表（已筛选+分页）
     */
    @Transactional
    public void archiveDisplayedProducts(String sessionId, List<ProductCard> products) {
        if (sessionId == null || sessionId.isBlank() || products == null || products.isEmpty()) return;

        // 覆盖写入：先删旧的，再存新的
        productRepository.deleteByHistoryId(sessionId);

        Instant now = Instant.now();
        List<RecognitionHistoryProduct> entities = new java.util.ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            ProductCard p = products.get(i);
            if (p.id() == null || p.id().isBlank()) continue;

            RecognitionHistoryProduct entity = new RecognitionHistoryProduct();
            entity.setHistoryId(sessionId);
            entity.setProductId(p.id());
            entity.setSortNo(i);
            entity.setTitle(truncate(p.title(), 500));
            entity.setCoverImage(truncate(p.imageUrl(), 1000));
            entity.setPrice(p.price());
            entity.setOriginalPrice(p.originalPrice());
            entity.setPlatform(truncate(p.platform(), 32));
            entity.setBrand(truncate(p.brand(), 100));
            entity.setRating(p.rating() > 0 ? BigDecimal.valueOf(p.rating()) : null);
            entity.setSales(p.sales() > 0 ? (int) Math.min(p.sales(), Integer.MAX_VALUE) : null);
            entity.setDetailUrl(truncate(p.detailUrl(), 1000));
            entity.setSimilarityScore(p.similarity() > 0 ? BigDecimal.valueOf(p.similarity()) : null);
            entity.setMainCategoryCode(truncate(p.mainCategoryCode(), 64));
            entity.setProductRole(truncate(p.productRole(), 32));
            entity.setCreatedAt(now);
            entities.add(entity);
        }

        if (!entities.isEmpty()) {
            productRepository.saveAll(entities);
            log.debug("Archived {} displayed products for session {}", entities.size(), sessionId);
        }
    }

    /**
     * 从 Redis 缓存归档会话商品（会话结束时的兜底）。
     * 仅在 archiveDisplayedProducts 未被调用过时使用。
     */
    @Transactional
    public void archiveSessionProducts(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;

        // 如果已经归档过，不再重复
        if (!productRepository.findByHistoryIdOrderBySortNoAsc(sessionId).isEmpty()) {
            return;
        }

        // session cache 可能是 Top300/Top1000 候选池，历史只需保存展示量
        List<ProductCard> products = sessionCache.getCandidates(sessionId).stream()
                .limit(50)
                .toList();
        if (products.isEmpty()) {
            return;
        }

        archiveDisplayedProducts(sessionId, products);
        log.info("Fallback archived {} products from cache for session {}", products.size(), sessionId);
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }
}
