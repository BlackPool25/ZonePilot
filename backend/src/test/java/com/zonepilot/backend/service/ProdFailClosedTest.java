package com.zonepilot.backend.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave A fixes 2+3 (TDD RED): prod must fail fast, not fail open.
 * - Prod JWT: no default secret (missing APP_JWT_SECRET must not boot with a known key).
 * - Prod CORS: no localhost fallback (missing CORS_ALLOWED_ORIGINS must not boot open).
 * - No hardcoded dev defaults in JwtService / WebConfig.
 */
class ProdFailClosedTest {

    private static final Path RES =
            Path.of("src/main/resources");
    private static final Path JAVA =
            Path.of("src/main/java/com/zonepilot/backend");

    private static String read(Path p) throws Exception {
        return Files.readString(p);
    }

    private static String prodYml() throws Exception {
        return read(RES.resolve("application-prod.yml"));
    }

    @Test
    void prod_requiresExplicitJwtSecret() throws Exception {
        String prod = prodYml();
        assertThat(prod).contains("APP_JWT_SECRET");
        // The prod entry must reference the env var with NO ":default" fallback.
        String line = prod.lines()
                .filter(l -> l.contains("APP_JWT_SECRET"))
                .findFirst().orElseThrow();
        assertThat(line.replaceAll("\\s", ""))
                .doesNotContain(":}").doesNotContain(":-");
        assertThat(line).doesNotContain("localhost").doesNotContain("zonepilot-dev-secret");
    }

    @Test
    void prod_requiresExplicitCorsOrigins() throws Exception {
        String prod = prodYml();
        String line = prod.lines()
                .filter(l -> l.contains("allowed-origins"))
                .findFirst().orElseThrow();
        assertThat(line).contains("${CORS_ALLOWED_ORIGINS}");
        assertThat(line).doesNotContain("localhost:3000");
    }

    @Test
    void jwtService_hasNoHardcodedDefaultSecret() throws Exception {
        String src = read(JAVA.resolve("auth/JwtService.java"));
        assertThat(src).doesNotContain("zonepilot-dev-secret");
    }

    @Test
    void webConfig_hasNoLocalhostFallback() throws Exception {
        String src = read(JAVA.resolve("config/WebConfig.java"));
        assertThat(src).doesNotContain("localhost:3000");
    }
}
