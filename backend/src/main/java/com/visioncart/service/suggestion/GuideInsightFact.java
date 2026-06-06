package com.visioncart.service.suggestion;

import java.util.Map;

/**
 * 结构化导购洞察事实，由规则/统计生成，AI 只负责改写文案。
 */
public record GuideInsightFact(
        String id,
        String type,
        String title,
        String evidence,
        String action,
        String actionLabel,
        Map<String, Object> metrics
) {}
