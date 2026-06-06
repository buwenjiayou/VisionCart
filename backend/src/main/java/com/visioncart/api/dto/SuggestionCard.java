package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record SuggestionCard(
        String id,
        String title,
        String subtitle,
        String icon,
        String action,
        int priority,
        String badge,
        String reason,
        String metric,
        String actionLabel,
        String tone,
        /** FILTER_ACTION = safe filter execution; FLOW_ACTION = opens a UI flow (e.g. price alert dialog) */
        @JsonInclude(JsonInclude.Include.NON_NULL) String actionType
) {
    public SuggestionCard(String id, String title, String subtitle, String icon, String action, int priority) {
        this(id, title, subtitle, icon, action, priority, null, null, null, null, null, null);
    }

    public SuggestionCard(String id, String title, String subtitle, String icon, String action, int priority,
                          String badge, String reason, String metric, String actionLabel, String tone) {
        this(id, title, subtitle, icon, action, priority, badge, reason, metric, actionLabel, tone, null);
    }

    /** Whether this action opens a UI flow instead of executing a filter. */
    @JsonIgnore
    public boolean isFlowAction() {
        return "FLOW_ACTION".equals(actionType);
    }
}
