package com.platform.agentservice.config;

import com.platform.agentservice.pat.PatAuthFilter;
import com.platform.agentservice.pat.PatService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * JWT 디코더(JWKS + issuer/audience 검증)와 roles→ROLE_ 변환기는 common-starter가 준다(S-02).
 * 여기에는 이 서비스만의 것 — 어떤 경로를 열지 — 만 남긴다.
 *
 * <p>{@code /api/agent/mcp/**}(및 그 자체 경로)는 별도의 {@link SecurityFilterChain}으로
 * 완전히 분리한다 — 같은 체인에 {@code oauth2ResourceServer(jwt)}를 두면
 * {@code BearerTokenAuthenticationFilter}가 경로 무관하게 모든 {@code Authorization: Bearer}
 * 값을 JWT로 디코드 시도하다 PAT(agp_*)에 대해 실패해 SecurityContext를 지우고 401로
 * 체인을 끊어버린다(실측 확인 — {@code PatMcpSecurityChainTest}가 이 상호작용을 재현했다).
 * 그래서 이 경로는 JWT 리소스서버 설정이 아예 없는 별도 체인으로 두고, {@link PatAuthFilter}
 * 하나만 게이트로 둔다. 나머지 경로는 기존과 동일하게 JWT 인증이다.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String MCP_PATH = "/api/agent/mcp";
    private static final String MCP_SUBPATHS = "/api/agent/mcp/**";
    private static final String[] ACTUATOR_PUBLIC = {"/actuator/health", "/actuator/info"};

    /**
     * 내부망 전용 헬스/빌드정보 — 게이트웨이 관리자 대시보드가 인증 없이 프로브한다.
     * MCP 체인과 같은 이유로 JWT 리소스서버가 없는 별도 체인이다: 같은 체인에
     * {@code oauth2ResourceServer(jwt)}가 있으면 프로브가 어떤 {@code Authorization} 헤더를
     * 달고 오든 {@code BearerTokenAuthenticationFilter}가 먼저 디코드를 시도해 401로 끊는다.
     * 노출 자체가 health·info뿐이라(management.endpoints.web.exposure.include) 나머지
     * {@code /actuator/**}는 아래 JWT 체인에서 인증을 요구한다.
     */
    @Bean
    @Order(0)
    SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(ACTUATOR_PUBLIC)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** {@code /api/agent/mcp} 전용 체인 — PatAuthFilter가 유일한 인증 게이트다(permitAll + 필터가 401 직접 반환). */
    @Bean
    @Order(1)
    SecurityFilterChain mcpFilterChain(HttpSecurity http, PatService patService) throws Exception {
        PatAuthFilter patAuthFilter = new PatAuthFilter(patService);
        http
                .securityMatcher(MCP_PATH, MCP_SUBPATHS)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(patAuthFilter, AuthorizationFilter.class);
        return http.build();
    }

    /** 그 외 모든 경로 — 기존과 동일한 JWT 리소스서버 인증. */
    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
