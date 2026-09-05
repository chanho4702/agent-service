package com.platform.agentservice;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

public final class TestAuth {
    private TestAuth() {}

    public static JwtAuthenticationToken user(long userId, String name) {
        return token(userId, name, "USER");
    }

    public static JwtAuthenticationToken admin(long userId, String name) {
        return token(userId, name, "ADMIN");
    }

    private static JwtAuthenticationToken token(long userId, String name, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("name", name)
                .claim("roles", List.of(role))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
