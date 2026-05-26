package com.visioncart.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Component
public class JwtUtil {

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final SecretKey key;
    private final long expirationMs;
    private final StringRedisTemplate redisTemplate;

    public JwtUtil(
            @Value("${visioncart.jwt.secret}") String secret,
            @Value("${visioncart.jwt.expiration:86400000}") long expirationMs,
            StringRedisTemplate redisTemplate
    ) {
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 characters, got " + secret.length());
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.redisTemplate = redisTemplate;
    }

    public String generateToken(Long userId, String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    public String getEmail(String token) {
        return parseToken(token).get("email", String.class);
    }

    public boolean validateToken(String token) {
        try {
            if (isBlacklisted(token)) return false;
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void invalidateToken(String token) {
        try {
            Claims claims = parseToken(token);
            long ttlMs = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (ttlMs > 0) {
                redisTemplate.opsForValue().set(BLACKLIST_PREFIX + token, "1", ttlMs, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            // Token already expired or invalid, no need to blacklist
        }
    }

    private boolean isBlacklisted(String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
        } catch (Exception e) {
            return false; // Redis unavailable, fail open
        }
    }
}
