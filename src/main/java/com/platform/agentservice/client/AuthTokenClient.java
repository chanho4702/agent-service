package com.platform.agentservice.client;

import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * auth-server 내부 경로 호출 두 가지 — 에이전트 페르소나 사용자 등록과 페르소나용 서비스 토큰 발급. 둘 다 클러스터 내부
 * 시크릿({@code X-Internal-Secret})으로만 인증한다. 등록은 P3f부터 사용자 JWT가 아니라 내부 경로다: 관리 권한 판정은
 * agent-service({@code AgentAuthz})가 하고, 통과한 요청만 이 채널로 등록한다(프로젝트 관리자도 페르소나를 만들 수 있게).
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

    /** {@code POST /internal/agents} 응답(= {@code /api/auth/agents}와 같은 shape) — {@code {userId, slug, created}}. */
    public record AgentRegistration(long userId, String slug, boolean created) {}

    private record CreateAgentRequest(String slug, String name, String email) {}

    /**
     * {@code POST /internal/agents} — 본문·응답은 {@code POST /api/auth/agents}와 같다. 시크릿이 비면 호출하지 않고 503
     * (fail-closed, {@link #mint}와 같은 처리). 내부 필터의 403은 시크릿 불일치 = 서비스 간 인증 결함이라 사용자에게
     * "권한 없음"으로 보이지 않게 {@link DownstreamAuthException}(503)으로 바꾼다.
     */
    public AgentRegistration registerAgent(String slug, String name, String email) {
        if (internalSecret.isBlank()) {
            log.warn("에이전트 사용자 등록 불가 — 내부 시크릿(AGENT_INTERNAL_SECRET) 미설정: slug={}", slug);
            throw new ServiceUnavailableException("에이전트 사용자 등록이 설정되지 않았습니다");
        }
        try {
            return authRestClient.post()
                    .uri("/internal/agents")
                    .header("X-Internal-Secret", internalSecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateAgentRequest(slug, name, email))
                    .retrieve()
                    .body(AgentRegistration.class);
        } catch (RestClientException e) {
            log.warn("에이전트 사용자 등록 실패: slug={} 원인={}", slug, describe(e));
            if (e instanceof HttpStatusCodeException status && status.getStatusCode().value() == 403) {
                throw new DownstreamAuthException("에이전트 사용자 등록 실패: auth-server가 내부 시크릿을 거부했습니다 — AGENT_INTERNAL_SECRET을 확인하세요");
            }
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
