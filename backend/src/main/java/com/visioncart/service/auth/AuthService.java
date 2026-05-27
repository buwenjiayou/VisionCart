package com.visioncart.service.auth;

import com.visioncart.api.dto.*;
import com.visioncart.config.JwtUtil;
import com.visioncart.domain.User;
import com.visioncart.repository.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import org.springframework.scheduling.annotation.Scheduled;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String CODE_PREFIX = "verify:code:";
    private static final int CODE_LENGTH = 6;
    private static final int CODE_EXPIRE_MINUTES = 5;
    private static final int MAX_MEMORY_VERIFICATION_KEYS = 10_000;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final JavaMailSender mailSender;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, ExpiringValue> memoryVerificationStore = new ConcurrentHashMap<>();

    @Value("${spring.mail.username:noreply@example.com}")
    private String fromEmail;

    public AuthService(UserRepository userRepository,
                       JwtUtil jwtUtil,
                       StringRedisTemplate redisTemplate,
                       JavaMailSender mailSender) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.mailSender = mailSender;
    }

    /**
     * Send verification code to email.
     */
    public void sendCode(String email) {
        // Validate email format
        if (email == null || !email.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }

        // Rate limit: 1 code per minute
        String rateKey = CODE_PREFIX + "rate:" + email;
        if (hasKey(rateKey)) {
            throw new IllegalArgumentException("验证码发送过于频繁，请稍后再试");
        }

        String code = generateCode();

        // Store code in Redis with TTL
        String codeKey = CODE_PREFIX + email;
        setValue(codeKey, code, CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);

        // Rate limit key (60 seconds)
        setValue(rateKey, "1", 60, TimeUnit.SECONDS);

        sendEmail(email, code);
    }

    /**
     * Verify code and login/register.
     * If user exists -> login. If not -> auto register then login.
     */
    public LoginResponse loginWithCode(String email, String code) {
        String codeKey = CODE_PREFIX + email;
        String storedCode = getValue(codeKey);
        if (storedCode == null) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        if (!storedCode.equals(code)) {
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

        // Generate JWT token
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());

        return new LoginResponse(token, user.getId(), user.getEmail());
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

    private void sendEmail(String to, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject("VisionCart 验证码");
            helper.setText(
                    "<div style='font-family: sans-serif; max-width: 400px; margin: 0 auto;'>" +
                    "<h2 style='color: #0A7C66;'>VisionCart</h2>" +
                    "<p>您的验证码是：</p>" +
                    "<div style='font-size: 32px; font-weight: bold; color: #0A7C66; letter-spacing: 8px; padding: 16px 0;'>" +
                    code +
                    "</div>" +
                    "<p style='color: #999;'>验证码 " + CODE_EXPIRE_MINUTES + " 分钟内有效，请勿泄露给他人。</p>" +
                    "<p style='color: #ccc; font-size: 12px;'>如非本人操作，请忽略此邮件。</p>" +
                    "</div>",
                    true
            );
            mailSender.send(message);
        } catch (MessagingException e) {
            log.error("发送验证码邮件失败: to={}", to, e);
            throw new RuntimeException("验证码发送失败，请稍后再试");
        }
    }
}
