package com.platform.agentservice.config;

import com.platform.agentservice.pat.PatAuthFilter;
import com.platform.agentservice.pat.PatService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT 디코더(JWKS + issuer/audience 검증)와 roles→ROLE_ 변환기는 common-starter가 준다(S-02).
 * 여기에는 이 서비스만의 것 — 어떤 경로를 열지 — 만 남긴다.
 *
 * <p>{@code /api/agent/mcp/**}는 permitAll이지만 실질적으로는 열려있지 않다 — 게이트웨이도
 * 이 경로를 permitAll로 통과시키므로(Task 11), {@link PatAuthFilter}가 Bearer PAT을 직접
 * 검증해 실패하면 401을 써서 체인을 끝낸다. 나머지 경로는 기존과 동일하게 JWT 인증이다.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter, PatService patService)
            throws Exception {
        PatAuthFilter patAuthFilter = new PatAuthFilter(patService);
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/agent/mcp/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .addFilterBefore(patAuthFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
