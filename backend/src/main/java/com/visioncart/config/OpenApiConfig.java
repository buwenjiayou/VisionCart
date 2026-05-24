package com.visioncart.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI visionCartOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("VisionCart API")
                        .version("0.1.0")
                        .description("AI 拍照识物与智能比价购物助手 API")
                        .license(new License().name("Proprietary")));
    }
}
