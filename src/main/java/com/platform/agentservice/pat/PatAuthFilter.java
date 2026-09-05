package com.platform.agentservice.pat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /api/agent/mcp}(그리고 그 하위 경로) 전용 체인에서만 도는 필터다 —
 * SecurityConfig의 {@code mcpFilterChain}이 이 경로만 이 필터로 라우팅하므로 이 필터가
 * 실질적인 인증 게이트다: {@code Authorization: Bearer agp_*}를 검증하지 못하면 체인을
 * 진행시키지 않고 여기서 401을 직접 써서 끝낸다(MCP 핸들러에 닿지 않게).
 *
 * <p>경로 매칭은 {@code mcpFilterChain}의 {@code securityMatcher(MCP_PATH, MCP_SUBPATHS)}와
 * 정확히 대응한다({@code /api/agent/mcp} 자체이거나 {@code /api/agent/mcp/}로 시작) — 이
 * 체인에 걸리는 요청은 이 필터가 전부 처리하므로 실질적으로 이 검사는 항상 참이지만,
 * 필터를 다른 체인에 잘못 재사용하는 실수를 방지하는 방어선으로 남겨 둔다.
 *
 * <p>일반 Spring 빈으로 등록하지 않는다 — Filter 빈은 Boot가 서블릿 컨테이너에도
 * {@code /*}로 자동 등록해 이중 실행을 유발한다. SecurityConfig가 직접
 * {@code new}로 만들어 {@code addFilterBefore}에만 끼워 넣는다.
 */
@RequiredArgsConstructor
public class PatAuthFilter extends OncePerRequestFilter {

    private static final String MCP_PATH = "/api/agent/mcp";
    private static final String MCP_PATH_WITH_TRAILING_SLASH = "/api/agent/mcp/";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PatService patService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isMcpPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String token = extractBearerToken(request);
        Optional<PatPrincipal> principal = token.isEmpty() ? Optional.empty() : patService.validate(token);

        if (principal.isEmpty()) {
            writeUnauthorized(response);
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(authenticationFor(principal.get()));
        chain.doFilter(request, response);
    }

    private boolean isMcpPath(String uri) {
        return uri.equals(MCP_PATH) || uri.startsWith(MCP_PATH_WITH_TRAILING_SLASH);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(PatPrincipal principal) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_AGENT"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return "";
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(Map.of("error", "유효하지 않은 토큰")));
    }
}
