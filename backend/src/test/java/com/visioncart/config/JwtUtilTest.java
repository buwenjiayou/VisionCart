package com.visioncart.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtUtilTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void validateTokenFailsClosedWhenRedisBlacklistCheckFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        JwtUtil jwtUtil = new JwtUtil(SECRET, 86_400_000L, redisTemplate, mock(Environment.class));

        String token = jwtUtil.generateToken(1L, "user@example.com");

        assertThat(jwtUtil.validateToken(token)).isFalse();
    }

    @Test
    void validateTokenAcceptsTokenWhenRedisBlacklistCheckSucceeds() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        JwtUtil jwtUtil = new JwtUtil(SECRET, 86_400_000L, redisTemplate, mock(Environment.class));

        String token = jwtUtil.generateToken(1L, "user@example.com");

        assertThat(jwtUtil.validateToken(token)).isTrue();
    }

    @Test
    void invalidateTokenUsesLocalBlacklistWhenRedisWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class));
        JwtUtil jwtUtil = new JwtUtil(SECRET, 86_400_000L, redisTemplate, mock(Environment.class));
        String token = jwtUtil.generateToken(1L, "user@example.com");

        jwtUtil.invalidateToken(token);

        assertThat(jwtUtil.validateToken(token)).isFalse();
    }
}
