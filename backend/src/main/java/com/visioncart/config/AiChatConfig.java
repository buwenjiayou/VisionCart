package com.visioncart.config;

import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 覆盖 Spring AI 默认的 OpenAiApi Bean。
 * <p>
 * Spring AI 默认 completionsPath = /v1/chat/completions
 * ARK 需要 completionsPath = /chat/completions (baseUrl 已包含 /api/v3)
 */
@Configuration
public class AiChatConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.openai", name = "api-key")
    public OpenAiApi openAiApi(
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl) {
        return OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .completionsPath("/chat/completions")
                .build();
    }
}
