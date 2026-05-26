package com.visioncart.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CorrectionStat(
        @JsonProperty("attribute_name") String attributeName,
        @JsonProperty("vlm_output") String vlmOutput,
        @JsonProperty("user_correction") String userCorrection,
        long count
) {
}
