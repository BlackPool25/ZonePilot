package com.zonepilot.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${cors.allowed-origins:}") String[] allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.length == 0
                || (allowedOrigins.length == 1 && allowedOrigins[0].isBlank())) {
            throw new IllegalStateException(
                    "cors.allowed-origins is blank — set CORS_ALLOWED_ORIGINS explicitly");
        }
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
