package com.visioncart.service.search;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class SearchTelemetry {
    private final String searchRunId;
    private final Map<String, Object> fields = new LinkedHashMap<>();

    public SearchTelemetry(String searchRunId) {
        this.searchRunId = searchRunId;
        put("searchRunId", searchRunId);
    }

    public SearchTelemetry put(String key, Object value) {
        if (key != null && value != null) {
            fields.put(key, value);
        }
        return this;
    }

    public SearchTelemetry recordLatency(Duration latency) {
        if (latency != null) {
            fields.put("latencyMs", latency.toMillis());
        }
        return this;
    }

    public String searchRunId() {
        return searchRunId;
    }

    public Map<String, Object> fields() {
        return Map.copyOf(fields);
    }
}
