package com.visioncart.service.ai;

import com.visioncart.config.VisionCartProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "visioncart.ai", name = "vision-api-key")
public class AiModelHealthIndicator implements HealthIndicator {

    private final VisionCartProperties properties;

    public AiModelHealthIndicator(VisionCartProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        String model = properties.getAi().getVisionModel();
        String apiKey = properties.getAi().getVisionApiKey();

        if (StringUtils.isBlank(apiKey) || apiKey.startsWith("replace-with")) {
            return Health.down()
                    .withDetail("reason", "Vision API key not configured")
                    .build();
        }

        if (StringUtils.isBlank(model)) {
            return Health.down()
                    .withDetail("reason", "Vision model not configured")
                    .build();
        }

        return Health.up()
                .withDetail("model", model)
                .withDetail("endpoint", "configured")
                .build();
    }
}
