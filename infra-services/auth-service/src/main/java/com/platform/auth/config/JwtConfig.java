package com.platform.auth.config;

import com.platform.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {
    @Value("${jwt.secret}") private String secret;
    @Value("${jwt.access-token-expiry-ms:900000}") private long accessTokenExpiryMs;

    @Bean
    public JwtTokenProvider jwtTokenProvider() {
        return new JwtTokenProvider(secret, accessTokenExpiryMs);
    }
}
