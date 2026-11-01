package com.zonepilot.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI zonePilotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ZonePilot API")
                        .description("Urban Fleet Zone Compliance & Monitoring Engine for Bengaluru")
                        .version("0.0.1-SNAPSHOT")
                        .contact(new Contact()
                                .name("ZonePilot Team")
                                .url("https://github.com/zonepilot"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                // JWT access tokens (Authorization: Bearer <token>); the
                // Authorize button in Swagger UI applies it to every call.
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Login via POST /api/auth/login, paste the accessToken here")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    @Bean
    public GroupedOpenApi apiV1() {
        return GroupedOpenApi.builder()
                .group("zonepilot")
                .pathsToMatch("/api/**")
                .build();
    }
}
