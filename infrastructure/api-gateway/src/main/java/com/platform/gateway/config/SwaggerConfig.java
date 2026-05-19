package com.platform.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .addServersItem(new Server().url("/").description("Via Gateway"))
                .info(new Info()
                        .title("API Gateway — Microservice Platform")
                        .description("""
                                Aggregated API documentation for all microservices.
                                Use the dropdown (top-right) to switch between individual service specs.

                                **How to authenticate:**
                                1. Call POST /api/auth/login in the auth-service spec
                                2. Copy the `accessToken` from the response
                                3. Click **Authorize** → paste token → Authorize
                                4. All subsequent calls will send the token automatically
                                """)
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste JWT token (without 'Bearer ' prefix)")));
    }
}
