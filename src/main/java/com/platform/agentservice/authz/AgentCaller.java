package com.platform.agentservice.authz;

import com.platform.common.error.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 관리 행위의 호출자. {@code globalAdmin}은 JWT realm 역할 ADMIN(common-starter가 roles→ROLE_ADMIN으로 변환) —
 * D-P3f-1이 이 판정을 그대로 유지하므로 org 장애 중에도 전역 관리자는 막히지 않는다.
 */
public record AgentCaller(long userId, boolean globalAdmin) {

    public static AgentCaller from(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ForbiddenException("관리자만 할 수 있습니다");
        }
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new ForbiddenException("관리자만 할 수 있습니다");
        }
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return new AgentCaller(userId, admin);
    }
}
