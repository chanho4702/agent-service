package com.platform.agentservice.tools;

import com.platform.agentservice.pat.PatPrincipal;
import com.platform.common.error.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * MCP 도구 진입점에서 호출자(페르소나)를 꺼내는 단일 통로. {@code /api/agent/mcp} 체인은
 * {@code PatAuthFilter}만 게이트로 두므로, 여기까지 도달했다면 정상 흐름에서는 항상
 * {@link PatPrincipal}이 SecurityContext에 있어야 한다 — 그래도 방어적으로 검사한다
 * (다른 체인으로 잘못 라우팅되는 실수 등).
 */
public final class ToolActor {

    private ToolActor() {}

    /** PatPrincipal이 없으면 {@link ForbiddenException}("MCP 인증 필요"). */
    public static PatPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PatPrincipal principal) {
            return principal;
        }
        throw new ForbiddenException("MCP 인증 필요");
    }
}
