package com.zhiguang.be.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigurationCorsTest {

    @Test
    void corsConfigurationShouldRejectWildcardOriginsWhenCredentialsAreAllowed() {
        CorsConfiguration configuration = SecurityConfiguration.buildCorsConfiguration(
                List.of("*", " ", "https://app.example.com")
        );

        assertEquals(Boolean.TRUE, configuration.getAllowCredentials());
        assertEquals(List.of("https://app.example.com"), configuration.getAllowedOrigins());
        assertFalse(configuration.getAllowedOrigins().contains("*"));
    }

    @Test
    void corsConfigurationShouldUseLocalDevelopmentOriginsWhenUnset() {
        CorsConfiguration configuration = SecurityConfiguration.buildCorsConfiguration(List.of());

        assertTrue(configuration.getAllowedOrigins().contains("http://localhost:5173"));
        assertTrue(configuration.getAllowedOrigins().contains("http://127.0.0.1:5173"));
    }
}
