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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link OrgClient#listMembers}만 다룬다(Task 10) — {@code registerAgentMember}/{@code grant}는 기존 범위 밖. */
class OrgClientTest {

    private static final String BEARER = "Bearer persona-token";

    private RestClient.Builder builder() {
        return RestClient.builder().baseUrl("http://org-service");
    }

    @Test
    void listMembers_sends_correct_path_and_header_and_parses_body() {
        RestClient.Builder builder = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OrgClient client = new OrgClient(builder.build());

        server.expect(requestTo("http://org-service/api/org/members"))
                .andExpect(method(HttpMethod.GET))
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
