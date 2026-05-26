package com.visioncart.config;

import java.security.Principal;

public record StompPrincipal(Long userId, String email) implements Principal {

    @Override
    public String getName() {
        return email;
    }
}
