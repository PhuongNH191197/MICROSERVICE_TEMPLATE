package com.platform.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        String secret = "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtbG9uZy1lbm91Z2gtZm9yLUhTNTEy";
        provider = new JwtTokenProvider(secret, 900000L);
    }

    @Test
    @DisplayName("createAccessToken produces valid token extractable back")
    void createAndExtract() {
        String userId = "user-123";
        String email = "test@example.com";
        List<String> roles = List.of("ROLE_USER");

        String token = provider.createAccessToken(userId, email, roles);

        assertThat(token).isNotBlank();
        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.extractUserId(token)).isEqualTo(userId);
        assertThat(provider.extractEmail(token)).isEqualTo(email);
        assertThat(provider.extractRoles(token)).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("validateToken returns false for tampered token")
    void validateToken_tampered_returnsFalse() {
        String token = provider.createAccessToken("u1", "e@e.com", List.of());
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("validateToken returns false for garbage input")
    void validateToken_garbage_returnsFalse() {
        assertThat(provider.validateToken("not.a.token")).isFalse();
    }
}
