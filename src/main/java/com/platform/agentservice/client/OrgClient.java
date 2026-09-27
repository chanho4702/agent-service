package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.MemberResponse;
import com.platform.common.error.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * org-service 호출: 에이전트 페르소나를 조직 멤버로 등록하고(P3f부터 내부 경로 {@code X-Internal-Token} — 관리 권한
 * 판정은 agent-service가 한다), 리소스 권한을 부여하고(호출자의 Authorization 헤더를 그대로 전달 — org가 대상 리소스
 * ADMIN을 스스로 다시 판정하는 이중 방어다. 페르소나 자신의 토큰으로는 절대 성공하지 않는다), 멤버 명단을 조회한다
 * ({@link #listMembers}만 인증만 있으면 되는 조회라 페르소나 토큰으로도 호출된다, S10).
 */
@Slf4j
@Component
public class OrgClient {

    private static final ParameterizedTypeReference<List<MemberResponse>> MEMBER_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient orgRestClient;
    /** org {@code /internal/org/**} 공유 비밀 — 알림 메일과 같은 ORG_INTERNAL_TOKEN. 비면 내부 등록은 fail-closed. */
    private final String internalToken;

    public OrgClient(RestClient orgRestClient) {
        this(orgRestClient, "");
    }

    @Autowired
    public OrgClient(@Qualifier("orgRestClient") RestClient orgRestClient,
                     @Value("${platform.agent.alerts.org-internal-token:}") String internalToken) {
        this.orgRestClient = orgRestClient;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    /**
     * {@code GET /api/org/members?kind=ALL} — {@code get_project_context}의 담당자 후보
     * 명단은 에이전트 페르소나도 보여야 하므로 기본 {@code kind=HUMAN} 필터를 명시적으로
     * 해제한다(리뷰 반영, S10). {@code status}는 기본값(ACTIVE)을 그대로 둔다 — 비활성
     * 멤버는 사람이든 페르소나든 담당자 후보가 아니다. 조회 전용이라 관리자 토큰이 아니라
     * 호출자의(보통 페르소나) 토큰을 그대로 실어도 된다.
     */
    public List<MemberResponse> listMembers(String bearer) {
        try {
            return orgRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/org/members")
                            .queryParam("kind", "ALL")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .retrieve()
                    .body(MEMBER_LIST);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "조직 멤버 목록 조회");
        }
    }

    private record AgentMemberRequest(long id, String displayName, String email, long actorId) {}

    /**
     * {@code POST /internal/org/members/agents} — 이미 있으면 org 쪽이 표시 필드를 갱신한다(멱등). 내부 경로엔 JWT가 없어
     * 재활성 이력의 행위자를 {@code actorId}(요청한 사람의 JWT sub)로 싣는다. 토큰이 비면 호출하지 않고 503, 내부 필터의
     * 403(토큰 불일치)은 서비스 간 인증 결함이라 {@link DownstreamAuthException}(503)으로 바꾼다.
     */
    public void registerAgentMember(long id, String name, String email, long actorId) {
        if (internalToken.isBlank()) {
            log.warn("조직 멤버 등록 불가 — 내부 토큰(ORG_INTERNAL_TOKEN) 미설정: memberId={}", id);
            throw new ServiceUnavailableException("에이전트 조직 멤버 등록이 설정되지 않았습니다");
        }
        try {
            orgRestClient.post()
                    .uri("/internal/org/members/agents")
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AgentMemberRequest(id, name, email, actorId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            if (e instanceof HttpStatusCodeException status && status.getStatusCode().value() == 403) {
                log.warn("조직 멤버 등록 실패 — org가 내부 토큰을 거부: memberId={}", id);
                throw new DownstreamAuthException("조직 멤버 등록 실패: org-service가 내부 토큰을 거부했습니다 — ORG_INTERNAL_TOKEN을 확인하세요");
            }
            throw DownstreamErrors.map(e, "조직 멤버 등록");
        }
    }

    private record InternalMailRequest(List<String> to, String subject, String text, String html, String source) {}

    /** org 허브의 202 응답 — {@code disabled}는 관리 화면에서 메일을 꺼 둔 상태(정상)라 실패와 구분한다. */
    public record InternalMailResponse(boolean accepted, boolean disabled) {}

    /**
     * {@code POST /internal/org/mail}(P3d) — 사용자 JWT가 아니라 공유 비밀 {@code X-Internal-Token}으로 부르는 서비스 간
     * 경로다(wiki·alm {@code OrgMailClient}와 같은 계약). 202는 큐 적재일 뿐 배달 보장이 아니다.
     */
    public InternalMailResponse enqueueInternalMail(List<String> to, String subject, String text, String source,
                                                    String internalToken) {
        try {
            InternalMailResponse response = orgRestClient.post()
                    .uri("/internal/org/mail")
                    .header("X-Internal-Token", internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new InternalMailRequest(to, subject, text, null, source))
                    .retrieve()
                    .body(InternalMailResponse.class);
            return response != null ? response : new InternalMailResponse(true, false);
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "알림 메일 발송 요청");
        }
    }

    private record GrantRequest(String subjectType, long subjectId, String resourceType, String resourceId, String role) {}

    /** {@code POST /api/org/grants} — subjectType은 항상 "USER"(에이전트 페르소나도 org 멤버라 USER). */
    public void grant(long subjectId, String resourceType, String resourceId, String role, String adminBearer) {
        try {
            orgRestClient.post()
                    .uri("/api/org/grants")
                    .header(HttpHeaders.AUTHORIZATION, adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new GrantRequest("USER", subjectId, resourceType, resourceId, role))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "권한 부여");
        }
    }
}
