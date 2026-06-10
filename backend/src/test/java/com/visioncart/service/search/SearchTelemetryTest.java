package com.visioncart.service.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTelemetryTest {

    @Test
    void fieldsIgnoreNullSearchRunId() {
        SearchTelemetry telemetry = new SearchTelemetry(null)
                .put("result", "ok")
                .put("ignored", null);

        assertThat(telemetry.fields())
                .containsEntry("result", "ok")
                .doesNotContainKeys("searchRunId", "ignored");
    }
}
