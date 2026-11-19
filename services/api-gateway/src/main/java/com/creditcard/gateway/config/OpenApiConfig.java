package com.creditcard.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and description for the Swagger UI and /v3/api-docs. No endpoint annotations. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("API Gateway")
                .description("Single entry point: routing, JWT authentication, and rate limiting for the platform APIs."));
    }
}
