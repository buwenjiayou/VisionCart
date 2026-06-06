package com.visioncart.service.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 分层商品相关性过滤：规则优先，不足时调用 NLP。
 *
 * @deprecated 已被 {@link RelevanceRanker}（结构化角色判断 + 标题规则）和
 * {@link CandidateFilterService}（session 候选池过滤）取代。
 * 主流程不再注入此类，保留供未来 NLP 二次过滤参考。
 * 请勿在新代码中使用——两套过滤逻辑并存会导致结果不一致。
 */
@Deprecated(since = "2026-06", forRemoval = true)
public class ProductRelevanceFilter {

    private static final Logger log = LoggerFactory.getLogger(ProductRelevanceFilter.class);
    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ObjectMapper objectMapper;

    public ProductRelevanceFilter(ObjectProvider<ChatClient.Builder> chatClientBuilder,
                                  ObjectMapper objectMapper) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * 从候选商品中筛选出与目标属性相关的商品。
     * 先用规则过滤，不够再调 NLP。
     *
     * @param candidates 候选商品列表（已去重）
     * @param attributes 目标属性（类目、品牌、关键词等）
     * @param targetCount 目标数量
     * @return 过滤后的商品列表
     */
    public List<ProductCard> filter(List<ProductCard> candidates,
                                    Map<String, String> attributes,
                                    int targetCount) {
        if (candidates.isEmpty() || candidates.size() <= targetCount) {
            return candidates;
        }

        String category = SearchTextUtils.useful(attributes.get(SearchTextUtils.ATTR_CATEGORY));
        String keyword = SearchTextUtils.useful(attributes.get(SearchTextUtils.ATTR_KEYWORD));
        String brand = SearchTextUtils.useful(attributes.get(SearchTextUtils.ATTR_BRAND));

        // 第一层：宽松规则过滤
        List<ProductCard> ruleFiltered = filterByRules(candidates, category, keyword, brand);
        log.info("Rule filter: {} -> {} products (target={})", candidates.size(), ruleFiltered.size(), targetCount);

        if (ruleFiltered.size() >= targetCount) {
            return ruleFiltered;
        }

        // 第二层：NLP 过滤（只处理规则过滤后的候选）
        List<ProductCard> nlpFiltered = filterByNlp(ruleFiltered, attributes, targetCount);
        log.info("NLP filter: {} -> {} products", ruleFiltered.size(), nlpFiltered.size());

        return nlpFiltered.isEmpty() ? ruleFiltered : nlpFiltered;
    }

    /**
     * 宽松规则过滤：标题包含类目、关键词、品牌或同义词。
     */
    private List<ProductCard> filterByRules(List<ProductCard> candidates,
                                            String category,
                                            String keyword,
                                            String brand) {
        String categoryLower = category.toLowerCase(Locale.ROOT);
        String keywordLower = keyword.toLowerCase(Locale.ROOT);
        String brandLower = brand.toLowerCase(Locale.ROOT);

        // 获取同义词
        List<String> siblings = SearchTextUtils.siblingProductTerms(categoryLower);

        return candidates.stream()
                .filter(product -> {
                    String title = Objects.toString(product.title(), "").toLowerCase(Locale.ROOT);
                    if (title.isBlank()) return false;

                    // 包含类目
                    if (!categoryLower.isBlank() && title.contains(categoryLower)) return true;

                    // 包含同义词
                    if (siblings.stream().anyMatch(title::contains)) return true;

                    // 包含关键词
                    if (!keywordLower.isBlank() && title.contains(keywordLower)) return true;

                    // 包含品牌
                    if (!brandLower.isBlank() && !brandLower.equals("未知") && title.contains(brandLower)) return true;

                    // 包含核心产品词
                    String core = SearchTextUtils.coreProductToken(category);
                    if (!core.isBlank() && core.length() >= 2 && title.contains(core.toLowerCase(Locale.ROOT))) {
                        return true;
                    }

                    return false;
                })
                .toList();
    }

    /**
     * NLP 过滤：调用 LLM 判断商品相关性。
     */
    private List<ProductCard> filterByNlp(List<ProductCard> candidates,
                                          Map<String, String> attributes,
                                          int targetCount) {
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            log.warn("ChatClient not available, skipping NLP filter");
            return List.of();
        }

        String category = SearchTextUtils.useful(attributes.get(SearchTextUtils.ATTR_CATEGORY));
        String brand = SearchTextUtils.useful(attributes.get(SearchTextUtils.ATTR_BRAND));
        String keyword = SearchTextUtils.useful(attributes.get(SearchTextUtils.ATTR_KEYWORD));

        // 构建商品列表（最多 50 个，避免 token 过多）
        List<ProductCard> toJudge = candidates.stream().limit(50).toList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toJudge.size(); i++) {
            sb.append(i + 1).append(". ").append(toJudge.get(i).title()).append("\n");
        }

        String prompt = String.format("""
                你是电商商品相关性判断专家。

                目标商品信息：
                - 类目：%s
                - 品牌：%s
                - 关键词：%s

                请判断以下商品是否与目标商品同类或高度相似。
                返回 JSON 数组，每个元素包含：
                - "index": 商品序号（从1开始）
                - "relevant": true/false
                - "score": 相关性分数 0.0-1.0

                只返回 JSON，不要解释。

                商品列表：
                %s
                """, category, brand, keyword, sb);

        try {
            String response = builder.build()
                    .prompt(prompt)
                    .call()
                    .content();

            // 解析 JSON 响应
            List<Map<String, Object>> judgments = objectMapper.readValue(
                    response.replaceAll("```json\\s*", "").replaceAll("```", "").trim(),
                    new TypeReference<>() {}
            );

            // 收集相关商品的索引
            Set<Integer> relevantIndices = new HashSet<>();
            for (Map<String, Object> judgment : judgments) {
                Boolean relevant = (Boolean) judgment.get("relevant");
                Number index = (Number) judgment.get("index");
                if (Boolean.TRUE.equals(relevant) && index != null) {
                    relevantIndices.add(index.intValue() - 1); // 转为 0-based
                }
            }

            // 返回相关商品
            List<ProductCard> result = new ArrayList<>();
            for (int i = 0; i < toJudge.size(); i++) {
                if (relevantIndices.contains(i)) {
                    result.add(toJudge.get(i));
                    if (result.size() >= targetCount) break;
                }
            }

            return result;
        } catch (Exception e) {
            log.warn("NLP relevance filter failed: {}", e.getMessage());
            return List.of();
        }
    }
}
