package com.visioncart.service.auth;

import com.visioncart.api.dto.LoginResponse;
import com.visioncart.api.dto.UserProfile;
import com.visioncart.config.JwtUtil;
import com.visioncart.domain.User;
import com.visioncart.repository.RefreshTokenRepository;
import com.visioncart.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.mail.internet.MimeMessage;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private UserRepository userRepository;
    private JwtUtil jwtUtil;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MailService mailService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        jwtUtil = mock(JwtUtil.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        mailService = mock(MailService.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        authService = new AuthService(userRepository, mock(RefreshTokenRepository.class), jwtUtil, redisTemplate, mailService);
    }

    @Test
    void sendCodeStoresCodeInRedis() {
        // Rate limit setIfAbsent returns true (no existing rate limit)
        when(valueOps.setIfAbsent(eq("verify:code:rate:test@example.com"), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        authService.sendCode("test@example.com");

        // Verify code is stored
        verify(valueOps).set(
                eq("verify:code:test@example.com"),
                argThat(code -> code.matches("\\d{6}")),
                eq(5L),
                eq(TimeUnit.MINUTES));
    }

    @Test
    void sendCodeRejectsInvalidEmail() {
        assertThatThrownBy(() -> authService.sendCode("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("邮箱格式不正确");
    }

    @Test
    void sendCodeRejectsNullEmail() {
        assertThatThrownBy(() -> authService.sendCode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("邮箱格式不正确");
    }

    @Test
    void sendCodeRateLimits() {
        // Rate limit setIfAbsent returns false (key already exists)
        when(valueOps.setIfAbsent(eq("verify:code:rate:test@example.com"), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertThatThrownBy(() -> authService.sendCode("test@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证码发送过于频繁");
    }

    @Test
    void loginWithCodeReturnsTokenForExistingUser() {
        User user = createUser(1L, "test@example.com");

        when(valueOps.get("verify:code:test@example.com")).thenReturn("123456");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(1L, "test@example.com")).thenReturn("jwt-token");

        LoginResponse response = authService.loginWithCode("test@example.com", "123456");

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getEmail()).isEqualTo("test@example.com");
        verify(redisTemplate).delete("verify:code:test@example.com");
    }

    @Test
    void loginWithCodeAutoRegistersNewUser() {
        when(valueOps.get("verify:code:new@example.com")).thenReturn("654321");
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            try {
                Field idField = User.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(saved, 42L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return saved;
        });
        when(jwtUtil.generateToken(42L, "new@example.com")).thenReturn("new-token");

        LoginResponse response = authService.loginWithCode("new@example.com", "654321");

        assertThat(response.getToken()).isEqualTo("new-token");
        assertThat(response.getUserId()).isEqualTo(42L);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void loginWithCodeRejectsExpiredCode() {
        when(valueOps.get("verify:code:test@example.com")).thenReturn(null);

        assertThatThrownBy(() -> authService.loginWithCode("test@example.com", "123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证码已过期");
    }

    @Test
    void loginWithCodeRejectsWrongCode() {
        when(valueOps.get("verify:code:test@example.com")).thenReturn("123456");

        assertThatThrownBy(() -> authService.loginWithCode("test@example.com", "999999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证码错误");
    }

    @Test
    void getUserProfileReturnsProfile() {
        User user = createUser(1L, "test@example.com");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserProfile profile = authService.getUserProfile(1L);

        assertThat(profile.getId()).isEqualTo(1L);
        assertThat(profile.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void getUserProfileThrowsWhenNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getUserProfile(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户不存在");
    }

    @Test
    void logoutInvalidatesToken() {
        authService.logout("some-token");

        verify(jwtUtil).invalidateToken("some-token");
    }

    @Test
    void generateCodeIsSixDigits() {
        // Rate limit setIfAbsent returns true for all calls
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        for (int i = 0; i < 10; i++) {
            authService.sendCode("test" + i + "@example.com");
        }

        // Verify all codes stored are 6-digit numeric
        verify(valueOps, atLeast(10)).set(
                anyString(),
                argThat(code -> code.matches("\\d{6}")),
                anyLong(),
                any(TimeUnit.class));
    }

    private User createUser(Long id, String email) {
        User user = new User();
        try {
            Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        user.setEmail(email);
        return user;
    }
}
