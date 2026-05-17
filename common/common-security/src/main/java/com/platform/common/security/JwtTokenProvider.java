package com.platform.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long accessTokenExpiryMs;

    public JwtTokenProvider(String secret, long accessTokenExpiryMs) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMs = accessTokenExpiryMs;
    }

    public String createAccessToken(String userId, String email, List<String> roles) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .claims(Map.of("email", email, "roles", roles))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenExpiryMs))
                .signWith(secretKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public String extractUserId(String token) { return getClaims(token).getSubject(); }
    public String extractEmail(String token) { return getClaims(token).get("email", String.class); }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) { return getClaims(token).get("roles", List.class); }

    private Claims getClaims(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }
}
