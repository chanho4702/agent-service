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
 * {@code /api/agent/mcp} (그리고 그 하위 경로) 요청만 처리한다. SecurityConfig에서 이
 * 경로는 permitAll이라 이 필터가 실질적인 인증 게이트다 — {@code Authorization: Bearer
 * agp_*}를 검증하지 못하면 체인을 진행시키지 않고 여기서 401을 직접 써서 끝낸다
 * (MCP 핸들러에 닿지 않게). 다른 경로는 그대로 통과시킨다 — JWT 인증은
 * BearerTokenAuthenticationFilter가 이어서 처리한다.
 *
 * <p>일반 Spring 빈으로 등록하지 않는다 — Filter 빈은 Boot가 서블릿 컨테이너에도
 * {@code /*}로 자동 등록해 이중 실행을 유발한다. SecurityConfig가 직접
 * {@code new}로 만들어 {@code addFilterBefore}에만 끼워 넣는다.
 */
@RequiredArgsConstructor
public class PatAuthFilter extends OncePerRequestFilter {

    private static final String MCP_PATH_PREFIX = "/api/agent/mcp";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PatService patService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(MCP_PATH_PREFIX)) {
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
