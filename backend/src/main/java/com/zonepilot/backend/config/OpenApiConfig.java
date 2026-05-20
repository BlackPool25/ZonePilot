package com.zonepilot.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

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
                                .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
