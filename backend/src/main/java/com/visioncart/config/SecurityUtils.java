package com.visioncart.config;

import com.visioncart.config.JwtAuthenticationFilter.AuthPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.getUserId();
        }
        throw new SecurityException("未登录");
    }

    public static void requireAdmin(VisionCartProperties properties, Long userId) {
        if (!properties.getSecurity().isAdmin(userId)) {
            throw new AccessDeniedException("admin_required");
        }
    }
}
