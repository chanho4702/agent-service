package com.platform.agentservice.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 러너 프로토콜 체인의 유일한 인증 게이트(P4a) — {@code Authorization: Bearer agr_*}만 받는다. 사람용 PAT({@code agp_})·run 토큰은
 * 해시 조회 전에 접두로 거부된다(러너 API는 직원 토큰으로 부를 수 없다, D-P4-3b). 반대 방향(agr_로 MCP)은 {@code PatAuthFilter}가 막는다.
 *
 * <p>{@code PatAuthFilter}와 같은 이유로 빈으로 등록하지 않는다(서블릿 컨테이너 이중 등록 방지) — {@code SecurityConfig}가 직접 만든다.
 */
@RequiredArgsConstructor
public class RunnerAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunnerService runnerService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!RunnerPaths.isProtocolPath(request.getRequestURI())) {
            // 매처(디코드된 경로)와 원본 URI가 갈라졌다 — permitAll 체인에서 통과시키면 인증 없이 뚫린다(PatAuthFilter와 같은 방어).
            writeUnauthorized(response);
            return;
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith(BEARER_PREFIX) ? header.substring(BEARER_PREFIX.length()).trim() : "";
        Optional<RunnerPrincipal> principal = token.startsWith(RunnerService.TOKEN_PREFIX)
                ? runnerService.authenticate(token) : Optional.empty();
        if (principal.isEmpty()) {
            writeUnauthorized(response);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal.get(), null, List.of(new SimpleGrantedAuthority("ROLE_RUNNER"))));
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(Map.of("error", "유효하지 않은 러너 토큰")));
    }
}
