package com.platform.agentservice.client;

import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * auth-server 호출 두 가지: 에이전트 페르소나 사용자 등록(관리자 권한 필요)과 페르소나용
 * 서비스 토큰 발급(클러스터 내부 시크릿). 인증 방식이 완전히 다르다 — 절대 섞지 않는다.
 */
@Slf4j
@Component
public class AuthTokenClient {

    private final RestClient authRestClient;
    private final String internalSecret;

    public AuthTokenClient(@Qualifier("authRestClient") RestClient authRestClient,
                            @Value("${platform.agent.internal-secret:}") String internalSecret) {
        this.authRestClient = authRestClient;
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
    }

    /** {@code POST /api/auth/agents} 응답 — {@code {userId, slug, created}}. */
    public record AgentRegistration(long userId, String slug, boolean created) {}

    private record CreateAgentRequest(String slug, String name, String email) {}

    /**
     * 호출자(관리자)의 Authorization 헤더를 그대로 전달한다 — 절대 페르소나 토큰을
     * 대신 넣지 않는다({@code /api/auth/agents}는 ROLE_ADMIN을 요구한다).
     */
    public AgentRegistration registerAgent(String slug, String name, String email, String adminBearer) {
        try {
            return authRestClient.post()
                    .uri("/api/auth/agents")
                    .header(HttpHeaders.AUTHORIZATION, adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateAgentRequest(slug, name, email))
                    .retrieve()
                    .body(AgentRegistration.class);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "에이전트 사용자 등록");
        }
    }

    private record MintRequest(Long userId) {}

    private static String describe(RestClientException e) {
        return e instanceof HttpStatusCodeException status
                ? "HTTP " + status.getStatusCode().value()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /**
     * {@code POST /internal/service-tokens} — 클러스터 내부 전용, {@code X-Internal-Secret}
     * 으로만 인증한다. 시크릿이 비어 있으면(미설정) 호출 자체를 시도하지 않고 503으로
     * 막는다(fail-closed, S10).
     */
    public TokenResponse mint(long memberId) {
        if (internalSecret.isBlank()) {
            log.warn("서비스 토큰 발급 불가 — 내부 시크릿(AGENT_INTERNAL_SECRET) 미설정: memberId={}", memberId);
            throw new ServiceUnavailableException("에이전트 토큰 발급이 설정되지 않았습니다");
        }
        try {
            return authRestClient.post()
                    .uri("/internal/service-tokens")
                    .header("X-Internal-Secret", internalSecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new MintRequest(memberId))
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException e) {
            // AGP-23: 발급 실패는 모든 도구·run을 막는데 예전엔 로그가 한 줄도 없었다. 시크릿·토큰은 싣지 않는다.
            log.warn("서비스 토큰 발급 실패: memberId={} 원인={}", memberId, describe(e));
            RuntimeException mapped = DownstreamErrors.map(e, "에이전트 토큰 발급");
            if (mapped instanceof NotFoundException) {
                // 내부 발급 404는 호출자가 찾던 대상(이슈 등)이 없다는 뜻이 아니다 — 404로 내보내면 "이슈 없음"으로 오독된다.
                throw new ServiceUnavailableException("에이전트 토큰 발급 실패: 발급 대상을 찾지 못했습니다(404) — 페르소나 계정·auth-server 배포를 확인하세요");
            }
            throw mapped;
        }
    }
}
