package com.platform.auth.service;

import com.platform.auth.config.RabbitMQConfig;
import com.platform.auth.dto.*;
import com.platform.auth.entity.RefreshToken;
import com.platform.auth.entity.Role;
import com.platform.auth.entity.User;
import com.platform.auth.repository.RefreshTokenRepository;
import com.platform.auth.repository.RoleRepository;
import com.platform.auth.repository.UserRepository;
import com.platform.common.events.PasswordResetEvent;
import com.platform.common.events.UserRegisteredEvent;
import com.platform.common.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RoleRepository roleRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final RabbitTemplate rabbitTemplate;

    @Value("${jwt.refresh-token-expiry-ms:604800000}")
    private long refreshTokenExpiryMs;

    @Value("${app.max-refresh-tokens-per-user:5}")
    private int maxRefreshTokensPerUser;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalStateException("Email already registered");
        }
        Role userRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new IllegalStateException("Default role not found"));
        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .build();
        user.getRoles().add(userRole);
        user = userRepository.save(user);

        rabbitTemplate.convertAndSend(RabbitMQConfig.AUTH_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY_USER_REGISTERED,
                UserRegisteredEvent.builder()
                        .userId(user.getId().toString())
                        .email(user.getEmail())
                        .fullName(user.getFullName())
                        .build());
        log.info("User registered: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        if (!user.isEnabled()) {
            throw new IllegalStateException("Account disabled");
        }
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String tokenHash = hashToken(request.getRefreshToken());
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));
        if (storedToken.isRevoked() || storedToken.isExpired()) {
            throw new IllegalArgumentException("Refresh token expired or revoked");
        }
        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);
        return buildAuthResponse(storedToken.getUser());
    }

    @Transactional
    public void logout(String refreshTokenRaw) {
        String hash = hashToken(refreshTokenRaw);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(t -> {
            t.setRevoked(true);
            refreshTokenRepository.save(t);
        });
    }

    @Transactional(readOnly = true)
    public ValidateTokenResponse validate(ValidateTokenRequest request) {
        if (!jwtTokenProvider.validateToken(request.getToken())) {
            return ValidateTokenResponse.builder().valid(false).build();
        }
        return ValidateTokenResponse.builder()
                .valid(true)
                .userId(jwtTokenProvider.extractUserId(request.getToken()))
                .email(jwtTokenProvider.extractEmail(request.getToken()))
                .roles(jwtTokenProvider.extractRoles(request.getToken()))
                .build();
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
            String resetToken = UUID.randomUUID().toString();
            rabbitTemplate.convertAndSend(RabbitMQConfig.AUTH_EXCHANGE,
                    RabbitMQConfig.ROUTING_KEY_PASSWORD_RESET,
                    PasswordResetEvent.builder()
                            .userId(user.getId().toString())
                            .email(user.getEmail())
                            .resetToken(resetToken)
                            .build());
            log.info("Password reset requested for: {}", user.getEmail());
        });
    }

    private AuthResponse buildAuthResponse(User user) {
        List<String> roles = user.getRoles().stream().map(Role::getName).collect(Collectors.toList());
        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId().toString(), user.getEmail(), roles);
        String rawRefreshToken = UUID.randomUUID().toString();
        enforceMaxRefreshTokens(user);
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(rawRefreshToken))
                .expiresAt(Instant.now().plusMillis(refreshTokenExpiryMs))
                .build();
        refreshTokenRepository.save(refreshToken);
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn(refreshTokenExpiryMs / 1000)
                .userId(user.getId().toString())
                .email(user.getEmail())
                .build();
    }

    private void enforceMaxRefreshTokens(User user) {
        long active = refreshTokenRepository.countByUserAndRevokedFalse(user);
        if (active >= maxRefreshTokensPerUser) {
            List<RefreshToken> oldest = refreshTokenRepository
                    .findByUserAndRevokedFalseOrderByCreatedAtAsc(user);
            int toRevoke = (int) (active - maxRefreshTokensPerUser + 1);
            oldest.subList(0, toRevoke).forEach(t -> t.setRevoked(true));
            refreshTokenRepository.saveAll(oldest.subList(0, toRevoke));
        }
    }

    private String hashToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
