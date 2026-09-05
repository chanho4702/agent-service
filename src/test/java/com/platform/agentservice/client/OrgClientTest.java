package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.MemberResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link OrgClient#listMembers}만 다룬다(Task 10) — {@code registerAgentMember}/{@code grant}는 기존 범위 밖. */
class OrgClientTest {

    private static final String BEARER = "Bearer persona-token";

    private RestClient.Builder builder() {
        return RestClient.builder().baseUrl("http://org-service");
    }

    /**
     * {@code kind=ALL}을 명시해 기본 HUMAN 필터를 해제해야 한다(리뷰 반영, S10) —
     * {@code get_project_context}의 담당자 후보 명단에 에이전트 페르소나도 나와야 하기
     * 때문이다. {@code status}는 파라미터를 아예 보내지 않아 서버 기본값(ACTIVE)을
     * 그대로 쓴다 — 비활성 멤버까지 담당자 후보로 보이면 안 되므로 {@code status=ALL}은
     * 절대 보내지 않는다(경로에 "status" 문자열이 없음을 직접 확인).
     */
    @Test
    void listMembers_requests_kind_ALL_but_omits_status_and_parses_body() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OrgClient client = new OrgClient(builder.build());

        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://org-service/api/org/members")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("kind", "ALL"))
                .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("status"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BEARER))
                .andRespond(withSuccess("""
                        [{"id":1,"displayName":"홍길동","email":"hong@example.com","status":"ACTIVE","kind":"HUMAN"},
                         {"id":2,"displayName":"페르소나","email":null,"status":"ACTIVE","kind":"AGENT"}]
                        """, MediaType.APPLICATION_JSON));

        List<MemberResponse> members = client.listMembers(BEARER);

        assertThat(members).hasSize(2);
        assertThat(members.get(0).id()).isEqualTo(1L);
        assertThat(members.get(0).displayName()).isEqualTo("홍길동");
        assertThat(members.get(0).kind()).isEqualTo("HUMAN");
        assertThat(members.get(1).kind()).isEqualTo("AGENT");
        server.verify();
    }
}
