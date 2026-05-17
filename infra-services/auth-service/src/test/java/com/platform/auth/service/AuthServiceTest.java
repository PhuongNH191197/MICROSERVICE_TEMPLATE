package com.platform.auth.service;

import com.platform.auth.config.RabbitMQConfig;
import com.platform.auth.dto.LoginRequest;
import com.platform.auth.dto.RefreshTokenRequest;
import com.platform.auth.dto.RegisterRequest;
import com.platform.auth.entity.RefreshToken;
import com.platform.auth.entity.Role;
import com.platform.auth.entity.User;
import com.platform.auth.repository.RefreshTokenRepository;
import com.platform.auth.repository.RoleRepository;
import com.platform.auth.repository.UserRepository;
import com.platform.common.events.UserRegisteredEvent;
import com.platform.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "refreshTokenExpiryMs", 604800000L);
        ReflectionTestUtils.setField(authService, "maxRefreshTokensPerUser", 5);
    }

    private RegisterRequest registerRequest(String email, String password, String fullName) {
        var r = new RegisterRequest();
        r.setEmail(email);
        r.setPassword(password);
        r.setFullName(fullName);
        return r;
    }

    private LoginRequest loginRequest(String email, String password) {
        var r = new LoginRequest();
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }

    private RefreshTokenRequest refreshRequest(String token) {
        var r = new RefreshTokenRequest();
        r.setRefreshToken(token);
        return r;
    }

    @Test
    @DisplayName("register - success publishes UserRegisteredEvent")
    void register_success_publishesEvent() {
        var request = registerRequest("test@example.com", "password123", "Test User");
        var role = new Role();
        role.setName("ROLE_USER");
        var savedUser = new User();
        savedUser.setId(UUID.randomUUID());
        savedUser.setEmail("test@example.com");
        savedUser.setFullName("Test User");
        savedUser.setEnabled(true);
        savedUser.setRoles(Set.of(role));

        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(role));
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(userRepository.save(any())).thenReturn(savedUser);
        when(jwtTokenProvider.createAccessToken(any(), any(), any())).thenReturn("access-tok");
        when(refreshTokenRepository.countByUserAndRevokedFalse(any())).thenReturn(0L);
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        authService.register(request);

        var captor = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.AUTH_EXCHANGE),
                eq(RabbitMQConfig.ROUTING_KEY_USER_REGISTERED),
                captor.capture()
        );
        assertThat(captor.getValue().getEmail()).isEqualTo("test@example.com");
        assertThat(captor.getValue().getFullName()).isEqualTo("Test User");
    }

    @Test
    @DisplayName("register - duplicate email throws IllegalStateException")
    void register_duplicateEmail_throws() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest("dup@example.com", "pass", "Dup")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("login - wrong password throws IllegalArgumentException")
    void login_badPassword_throws() {
        var user = new User();
        user.setEmail("user@example.com");
        user.setPasswordHash("hashed");
        user.setEnabled(true);
        user.setRoles(Set.of());

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest("user@example.com", "wrong")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("login - success returns tokens")
    void login_success_returnsTokens() {
        var role = new Role();
        role.setName("ROLE_USER");
        var user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setPasswordHash("hashed");
        user.setEnabled(true);
        user.setRoles(Set.of(role));

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "hashed")).thenReturn(true);
        when(jwtTokenProvider.createAccessToken(any(), any(), any())).thenReturn("access-token");
        when(refreshTokenRepository.countByUserAndRevokedFalse(user)).thenReturn(0L);
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = authService.login(loginRequest("user@example.com", "pass"));

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("refresh - revoked token throws")
    void refresh_revokedToken_throws() {
        var token = new RefreshToken();
        token.setRevoked(true);
        token.setExpiresAt(Instant.now().plusSeconds(3600));

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.refresh(refreshRequest("tok")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refresh - expired token throws")
    void refresh_expiredToken_throws() {
        var token = new RefreshToken();
        token.setRevoked(false);
        token.setExpiresAt(Instant.now().minusSeconds(1));

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.refresh(refreshRequest("tok")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
