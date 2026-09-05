package com.platform.agentservice.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * org-service 호출 두 가지: 에이전트 페르소나를 조직 멤버로 등록하고, 리소스 권한을
 * 부여한다. 둘 다 호출자(관리자)의 Authorization 헤더를 그대로 전달한다 — grant는
 * 대상 리소스의 ADMIN 권한을 요구하므로 페르소나 자신의 토큰으로는 절대 성공하지 않는다.
 */
@Component
public class OrgClient {

    private final RestClient orgRestClient;

    public OrgClient(@Qualifier("orgRestClient") RestClient orgRestClient) {
        this.orgRestClient = orgRestClient;
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
