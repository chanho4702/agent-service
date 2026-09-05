package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.MemberResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * org-service 호출: 에이전트 페르소나를 조직 멤버로 등록하고, 리소스 권한을 부여하고
 * (둘 다 호출자(관리자)의 Authorization 헤더를 그대로 전달 — grant는 대상 리소스의 ADMIN
 * 권한을 요구하므로 페르소나 자신의 토큰으로는 절대 성공하지 않는다), 멤버 명단을 조회한다
 * ({@link #listMembers}만 인증만 있으면 되는 조회라 페르소나 토큰으로도 호출된다, S10).
 */
@Component
public class OrgClient {

    private static final ParameterizedTypeReference<List<MemberResponse>> MEMBER_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient orgRestClient;

    public OrgClient(@Qualifier("orgRestClient") RestClient orgRestClient) {
        this.orgRestClient = orgRestClient;
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

    private record AgentMemberRequest(long id, String displayName, String email) {}

    /** {@code POST /api/org/members/agents} — 이미 있으면 org 쪽이 표시 필드를 갱신한다(멱등). */
    public void registerAgentMember(long id, String name, String email, String adminBearer) {
        try {
            orgRestClient.post()
                    .uri("/api/org/members/agents")
                    .header(HttpHeaders.AUTHORIZATION, adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AgentMemberRequest(id, name, email))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw DownstreamErrors.map(e, "조직 멤버 등록");
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
