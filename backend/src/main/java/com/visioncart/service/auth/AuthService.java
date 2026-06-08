package com.visioncart.service.auth;

import com.visioncart.api.dto.*;
import com.visioncart.config.JwtUtil;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.domain.RefreshToken;
import com.visioncart.domain.User;
import com.visioncart.repository.RefreshTokenRepository;
import com.visioncart.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String CODE_PREFIX = "verify:code:";
    private static final int CODE_LENGTH = 6;
    private static final int CODE_EXPIRE_MINUTES = 5;
    private static final int MAX_MEMORY_VERIFICATION_KEYS = 10_000;
    private static final long REFRESH_TOKEN_EXPIRY_DAYS = 30;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final MailService mailService;
    private final VisionCartProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, ExpiringValue> memoryVerificationStore = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtUtil jwtUtil,
                       StringRedisTemplate redisTemplate,
                       MailService mailService,
                       VisionCartProperties properties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.mailService = mailService;
        this.properties = properties;
    }

    /**
     * Send verification code to email.
     */
    public void sendCode(String email) {
        // Validate email format
        if (email == null || !email.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }

        // Rate limit: 1 code per minute (atomic to prevent TOCTOU race)
        String rateKey = CODE_PREFIX + "rate:" + email;
        int cooldownSeconds = Math.max(1, properties.getRateLimit().getSendCodeCooldownSeconds());
        if (!setIfAbsent(rateKey, "1", cooldownSeconds, TimeUnit.SECONDS)) {
            throw new IllegalArgumentException("验证码发送过于频繁，请稍后再试");
        }

        String code = generateCode();

        try {
            mailService.sendVerificationCode(email, code);
        } catch (RuntimeException e) {
            deleteValue(rateKey);
            throw e;
        }

        // Store code in Redis with TTL only after the email is accepted by SMTP.
        String codeKey = CODE_PREFIX + email;
        setValue(codeKey, code, CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Verify code and login/register.
     * If user exists -> login. If not -> auto register then login.
     */
    @Transactional
    public LoginResponse loginWithCode(String email, String code) {
        String codeKey = CODE_PREFIX + email;
        String storedCode = getValue(codeKey);
        if (storedCode == null) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        if (!MessageDigest.isEqual(storedCode.getBytes(java.nio.charset.StandardCharsets.UTF_8), code.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("验证码错误");
        }
        deleteValue(codeKey);

        // Find or create user
        User user = userRepository.findByEmail(email)
                .orElseGet(() -> {
                    User newUser = new User();
                    newUser.setEmail(email);
                    return userRepository.save(newUser);
                });

        // Generate short-lived access token
        String accessToken = jwtUtil.generateToken(user.getId(), user.getEmail());

        // Generate long-lived refresh token
        String refreshToken = createRefreshToken(user.getId(), null);

        return new LoginResponse(accessToken, refreshToken, user.getId(), user.getEmail());
    }

    /**
     * Refresh access token using a valid refresh token (rotation: old one is revoked, new pair issued).
     */
    @Transactional
    public LoginResponse refreshToken(String refreshTokenValue) {
        String tokenHash = hashToken(refreshTokenValue);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("refresh token 无效"));

        if (!stored.isValid()) {
            // Possible token reuse attack — revoke all user tokens
            if (stored.isRevoked()) {
                log.warn("Attempted reuse of revoked refresh token for user {}, revoking all tokens", stored.getUserId());
                refreshTokenRepository.revokeAllByUserId(stored.getUserId(), Instant.now());
            }
            throw new IllegalArgumentException("refresh token 已过期或已失效");
        }

        Long userId = stored.getUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // Revoke the old refresh token
        stored.setRevokedAt(Instant.now());
        refreshTokenRepository.save(stored);

        // Issue new pair
        String newAccessToken = jwtUtil.generateToken(userId, user.getEmail());
        String newRefreshToken = createRefreshToken(userId, stored.getDeviceInfo());

        return new LoginResponse(newAccessToken, newRefreshToken, userId, user.getEmail());
    }

    /**
     * Logout: revoke the access token and optionally the refresh token.
     */
    public void logout(String accessToken, String refreshTokenValue) {
        jwtUtil.invalidateToken(accessToken);
        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            String tokenHash = hashToken(refreshTokenValue);
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(rt -> {
                rt.setRevokedAt(Instant.now());
                refreshTokenRepository.save(rt);
            });
        }
    }

    /**
     * Logout all sessions for a user: revoke all refresh tokens.
     */
    @Transactional
    public void logoutAll(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
    }

    private String createRefreshToken(Long userId, String deviceInfo) {
        String rawToken = generateSecureToken();
        String tokenHash = hashToken(rawToken);

        RefreshToken entity = new RefreshToken();
        entity.setUserId(userId);
        entity.setTokenHash(tokenHash);
        entity.setExpiresAt(Instant.now().plus(REFRESH_TOKEN_EXPIRY_DAYS, ChronoUnit.DAYS));
        entity.setDeviceInfo(deviceInfo);
        refreshTokenRepository.save(entity);

        return rawToken;
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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

    @Scheduled(fixedDelay = 3600_000) // every hour
    void cleanupExpiredRefreshTokens() {
        refreshTokenRepository.deleteExpired(Instant.now());
    }

    /**
     * Get user profile by userId.
     */
    public UserProfile getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        return UserProfile.fromEntity(user);
    }

    /**
     * Logout - invalidate JWT token.
     */
    public void logout(String token) {
        jwtUtil.invalidateToken(token);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }

    private boolean hasKey(String key) {
        ExpiringValue localValue = memoryVerificationStore.get(key);
        if (localValue != null && !localValue.isExpired()) {
            return true;
        }
        if (localValue != null) {
            memoryVerificationStore.remove(key);
        }

        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception error) {
            log.warn("Redis unavailable for auth key lookup, using local memory cache: {}", error.getMessage());
            return false;
        }
    }

    private void setValue(String key, String value, long timeout, TimeUnit unit) {
        trimMemoryStoreIfNeeded();
        memoryVerificationStore.put(key, new ExpiringValue(value, Instant.now().plusMillis(unit.toMillis(timeout))));
        try {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch (Exception error) {
            log.warn("Redis unavailable for auth value storage, using local memory cache: {}", error.getMessage());
        }
    }

    private String getValue(String key) {
        ExpiringValue localValue = memoryVerificationStore.get(key);
        if (localValue != null) {
            if (!localValue.isExpired()) {
                return localValue.value();
            }
            memoryVerificationStore.remove(key);
        }

        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception error) {
            log.warn("Redis unavailable for auth value lookup, using local memory cache: {}", error.getMessage());
            return null;
        }
    }

    private void deleteValue(String key) {
        memoryVerificationStore.remove(key);
        try {
            redisTemplate.delete(key);
        } catch (Exception error) {
            log.warn("Redis unavailable for auth value deletion: {}", error.getMessage());
        }
    }

    /**
     * Atomic set-if-absent for rate limiting (prevents TOCTOU race).
     */
    private boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        trimMemoryStoreIfNeeded();
        // Check local memory first
        ExpiringValue localValue = memoryVerificationStore.get(key);
        if (localValue != null && !localValue.isExpired()) {
            return false;
        }
        if (localValue != null) {
            memoryVerificationStore.remove(key);
        }

        try {
            Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit);
            if (Boolean.TRUE.equals(result)) {
                memoryVerificationStore.put(key, new ExpiringValue(value, Instant.now().plusMillis(unit.toMillis(timeout))));
                return true;
            }
            return false;
        } catch (Exception error) {
            log.warn("Redis unavailable for rate limit, allowing request: {}", error.getMessage());
            // Fallback: use local memory as rate limit
            memoryVerificationStore.put(key, new ExpiringValue(value, Instant.now().plusMillis(unit.toMillis(timeout))));
            return true;
        }
    }

    @Scheduled(fixedRate = 300_000) // every 5 minutes
    void cleanupExpiredCodes() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, ExpiringValue>> it = memoryVerificationStore.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt().isBefore(now)) {
                it.remove();
            }
        }
        trimMemoryStoreIfNeeded();
    }

    private void trimMemoryStoreIfNeeded() {
        if (memoryVerificationStore.size() <= MAX_MEMORY_VERIFICATION_KEYS) {
            return;
        }
        Instant now = Instant.now();
        memoryVerificationStore.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (memoryVerificationStore.size() <= MAX_MEMORY_VERIFICATION_KEYS) {
            return;
        }
        memoryVerificationStore.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(java.util.Comparator.comparing(ExpiringValue::expiresAt)))
                .limit(memoryVerificationStore.size() - MAX_MEMORY_VERIFICATION_KEYS)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(memoryVerificationStore::remove);
    }

    private record ExpiringValue(String value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

}
