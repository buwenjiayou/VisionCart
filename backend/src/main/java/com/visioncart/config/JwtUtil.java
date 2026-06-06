package com.visioncart.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    private static final String BLACKLIST_PREFIX = "visioncart:jwt:blacklist:";

    private final SecretKey key;
    private final long expirationMs;
    private final StringRedisTemplate redisTemplate;

    private static final int REDIS_FAIL_THRESHOLD = 5;
    private static final long REDIS_COOLDOWN_MS = 30_000;
    private final java.util.concurrent.atomic.AtomicInteger redisFailCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong redisFailStartedAt = new java.util.concurrent.atomic.AtomicLong(0);

    // Local blacklist fallback when Redis is unavailable (Bug #12)
    private final ConcurrentHashMap<String, Long> localBlacklist = new ConcurrentHashMap<>();

    public JwtUtil(
            @Value("${visioncart.jwt.secret}") String secret,
            @Value("${visioncart.jwt.expiration:86400000}") long expirationMs,
            StringRedisTemplate redisTemplate,
            org.springframework.core.env.Environment env
    ) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes, got " + secretBytes.length);
        }
        String lower = secret.toLowerCase();
        if (lower.contains("changeme") || lower.contains("example") || lower.contains("replace")) {
            boolean isProd = java.util.Arrays.stream(env.getActiveProfiles()).anyMatch("prod"::equals);
            if (isProd) {
                throw new IllegalStateException("JWT secret is a placeholder value — aborting in production");
            }
            log.warn("JWT secret appears to be a placeholder value — this is INSECURE for production use");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
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
        Claims claims;
        try {
            claims = parseToken(token);
        } catch (Exception e) {
            return;
        }
        String hash = hashToken(token);
        long ttlMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (ttlMs > 0) {
            // Always write to local blacklist as fallback (Bug #12)
            localBlacklist.put(hash, System.currentTimeMillis() + ttlMs);
            try {
                redisTemplate.opsForValue().set(BLACKLIST_PREFIX + hash, "1", ttlMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("Failed to write token blacklist to Redis, local fallback active: {}", e.getMessage());
            }
        }
        // Cleanup expired local entries periodically
        if (localBlacklist.size() > 1000) {
            long now = System.currentTimeMillis();
            localBlacklist.entrySet().removeIf(entry -> entry.getValue() < now);
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000) // Every 5 minutes
    public void cleanupLocalBlacklist() {
        long now = System.currentTimeMillis();
        int before = localBlacklist.size();
        localBlacklist.entrySet().removeIf(entry -> entry.getValue() < now);
        int removed = before - localBlacklist.size();
        if (removed > 0) {
            log.debug("Cleaned up {} expired entries from local JWT blacklist", removed);
        }
    }

    private boolean isBlacklisted(String token) {
        String hash = hashToken(token);
        // Always check local blacklist first (works even when Redis is down)
        Long localExpiry = localBlacklist.get(hash);
        if (localExpiry != null) {
            if (System.currentTimeMillis() < localExpiry) {
                return true;
            }
            localBlacklist.remove(hash); // Expired
        }

        // Local blacklist already checked above — if Redis is down, fail open
        // (local blacklist covers locally-invalidated tokens; shared invalidation is best-effort)
        long failStarted = redisFailStartedAt.get();
        if (redisFailCount.get() >= REDIS_FAIL_THRESHOLD && (System.currentTimeMillis() - failStarted) < REDIS_COOLDOWN_MS) {
            log.debug("Redis unavailable, local blacklist check completed (cooldown active)");
            return false;
        }
        try {
            boolean result = Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + hash));
            redisFailCount.set(0);
            return result;
        } catch (Exception e) {
            int failures = redisFailCount.incrementAndGet();
            if (failures == 1) {
                redisFailStartedAt.set(System.currentTimeMillis());
            }
            if (failures <= REDIS_FAIL_THRESHOLD) {
                log.warn("Redis unavailable for blacklist check (failure {}/{}), failing open", failures, REDIS_FAIL_THRESHOLD, e);
            } else {
                log.warn("Redis unavailable (failure {}), entering cooldown — skipping blacklist check for {}s", failures, REDIS_COOLDOWN_MS / 1000);
            }
            return false;
        }
    }

    private static String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
