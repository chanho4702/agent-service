package com.platform.agentservice.persona;

import com.platform.agentservice.client.AuthTokenClient;
import com.platform.agentservice.client.OrgClient;
import com.platform.agentservice.persona.dto.PersonaCreateRequest;
import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest
@ActiveProfiles("test")
class PersonaServiceTest {

    @Autowired PersonaRepository personaRepository;

    private RestClient.Builder authBuilder;
    private MockRestServiceServer authServer;
    private RestClient.Builder orgBuilder;
    private MockRestServiceServer orgServer;
    private PersonaService personaService;

    private static final String ADMIN_BEARER = "Bearer admin-token";

    @BeforeEach
    void setUp() {
        personaRepository.deleteAllInBatch();

        authBuilder = RestClient.builder().baseUrl("http://auth-server");
        authServer = MockRestServiceServer.bindTo(authBuilder).build();
        AuthTokenClient authTokenClient = new AuthTokenClient(authBuilder.build(), "internal-secret");

        orgBuilder = RestClient.builder().baseUrl("http://org-service");
        orgServer = MockRestServiceServer.bindTo(orgBuilder).build();
        OrgClient orgClient = new OrgClient(orgBuilder.build());

        personaService = new PersonaService(authTokenClient, orgClient, personaRepository);
    }

    @Test
    // MockRestServiceServer는 서버 인스턴스별로 기대를 검증하므로(auth/org가 서로 다른
    // 인스턴스) 이 테스트가 직접 증명하는 것은 (1) org-service 안에서 멤버 등록이 grant보다
    // 먼저 나간다는 것과 (2) 그 grant의 subjectId가 auth 응답의 userId(9001)와 정확히
    // 일치한다는 것이다. auth 호출이 org 호출보다 먼저 일어난다는 것 자체는 이 두
    // MockRestServiceServer 사이의 교차 시퀀싱으로 검증되는 게 아니라, 구조적으로 강제된다
    // — org 요청 본문(id=9001)을 만들려면 auth 응답의 userId가 이미 있어야 하고, 그 값은
    // 스텁이 아니라 PersonaService.bootstrap이 실제로 auth 응답을 역참조해서 넣은 것이다
    // (하드코딩했다면 org 쪽 jsonPath("$.id")/jsonPath("$.subjectId") 검증이 우연히만
    // 맞아떨어질 텐데, auth stub의 userId를 9001이 아닌 값으로 바꾸면 이 테스트가 깨진다).
    void bootstrap_uses_auth_memberId_for_org_member_then_grant_in_order_and_saves_persona() {
        authServer.expect(requestTo("http://auth-server/api/auth/agents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", ADMIN_BEARER))
                .andExpect(jsonPath("$.slug").value("qa-bot"))
                .andExpect(jsonPath("$.name").value("QA Bot"))
                .andRespond(withSuccess("""
                        {"userId":9001,"slug":"qa-bot","created":true}
                        """, MediaType.APPLICATION_JSON));

        orgServer.expect(requestTo("http://org-service/api/org/members/agents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", ADMIN_BEARER))
                .andExpect(jsonPath("$.id").value(9001))
                .andExpect(jsonPath("$.displayName").value("QA Bot"))
                .andRespond(withSuccess("""
                        {"id":9001,"displayName":"QA Bot","email":"agents+qa-bot@platform.local","status":"ACTIVE","kind":"AGENT"}
                        """, MediaType.APPLICATION_JSON));

        orgServer.expect(requestTo("http://org-service/api/org/grants"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", ADMIN_BEARER))
                .andExpect(jsonPath("$.subjectType").value("USER"))
                .andExpect(jsonPath("$.subjectId").value(9001))
                .andExpect(jsonPath("$.resourceType").value("PROJECT"))
                .andExpect(jsonPath("$.resourceId").value("P1"))
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"id":1,"subjectType":"USER","subjectId":9001,"resourceType":"PROJECT","resourceId":"P1","role":"EDITOR"}
                        """));

        var req = new PersonaCreateRequest("qa-bot", PersonaRole.REVIEWER, "QA Bot", "🤖", "너는 리뷰어다", null,
                List.of(new PersonaCreateRequest.GrantRequest("PROJECT", "P1", "EDITOR")));

        PersonaService.BootstrapResult result = personaService.bootstrap(req, ADMIN_BEARER);

        assertThat(result.created()).isTrue();
        assertThat(result.persona().memberId()).isEqualTo(9001L);
        assertThat(result.persona().slug()).isEqualTo("qa-bot");
        authServer.verify();
        orgServer.verify();

        assertThat(personaRepository.findBySlug("qa-bot")).isPresent();
        assertThat(personaRepository.findBySlug("qa-bot").get().getMemberId()).isEqualTo(9001L);
    }

    @Test
    void bootstrap_defaults_synthetic_email_when_absent() {
        authServer.expect(requestTo("http://auth-server/api/auth/agents"))
                .andExpect(jsonPath("$.email").value("agents+qa-bot@platform.local"))
                .andRespond(withSuccess("""
                        {"userId":9001,"slug":"qa-bot","created":true}
                        """, MediaType.APPLICATION_JSON));
        orgServer.expect(requestTo("http://org-service/api/org/members/agents"))
                .andExpect(jsonPath("$.email").value("agents+qa-bot@platform.local"))
                .andRespond(withSuccess("""
                        {"id":9001,"displayName":"QA Bot","email":"agents+qa-bot@platform.local","status":"ACTIVE","kind":"AGENT"}
                        """, MediaType.APPLICATION_JSON));

        var req = new PersonaCreateRequest("qa-bot", PersonaRole.REVIEWER, "QA Bot", null, null, null, null);
        personaService.bootstrap(req, ADMIN_BEARER);

        authServer.verify();
        orgServer.verify();
    }

    @Test
    void bootstrap_is_idempotent_for_existing_slug_and_skips_downstream_calls() {
        personaRepository.save(Persona.of(9001L, "qa-bot", PersonaRole.REVIEWER, "Old Name", "🙂", "old prompt"));

        var req = new PersonaCreateRequest("qa-bot", PersonaRole.REVIEWER, "QA Bot", "🤖", "new prompt", null, null);
        PersonaService.BootstrapResult result = personaService.bootstrap(req, ADMIN_BEARER);

        assertThat(result.created()).isFalse();
        assertThat(result.persona().memberId()).isEqualTo(9001L);
        assertThat(result.persona().name()).isEqualTo("QA Bot");
        assertThat(result.persona().emoji()).isEqualTo("🤖");

        // 다운스트림 기대를 하나도 등록하지 않았다 — 예상 밖 요청이 나가면 verify()가 실패한다.
        authServer.verify();
        orgServer.verify();

        assertThat(personaRepository.findBySlug("qa-bot").get().getName()).isEqualTo("QA Bot");
    }

    @Test
    void auth_forbidden_maps_to_forbidden_exception() {
        authServer.expect(requestTo("http://auth-server/api/auth/agents"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"관리자 권한이 필요합니다\"}"));

        var req = new PersonaCreateRequest("qa-bot", PersonaRole.REVIEWER, "QA Bot", null, null, null, null);

        assertThatThrownBy(() -> personaService.bootstrap(req, ADMIN_BEARER))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("관리자 권한이 필요합니다");
    }

    @Test
    void org_conflict_maps_to_conflict_exception() {
        authServer.expect(requestTo("http://auth-server/api/auth/agents"))
                .andRespond(withSuccess("""
                        {"userId":9001,"slug":"qa-bot","created":true}
                        """, MediaType.APPLICATION_JSON));
        orgServer.expect(requestTo("http://org-service/api/org/members/agents"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"요청 값이 올바르지 않습니다\"}"));

        var req = new PersonaCreateRequest("qa-bot", PersonaRole.REVIEWER, "QA Bot", null, null, null, null);

        assertThatThrownBy(() -> personaService.bootstrap(req, ADMIN_BEARER))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void org_down_maps_to_service_unavailable() {
        authServer.expect(requestTo("http://auth-server/api/auth/agents"))
                .andRespond(withSuccess("""
                        {"userId":9001,"slug":"qa-bot","created":true}
                        """, MediaType.APPLICATION_JSON));
        orgServer.expect(requestTo("http://org-service/api/org/members/agents"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var req = new PersonaCreateRequest("qa-bot", PersonaRole.REVIEWER, "QA Bot", null, null, null, null);

        assertThatThrownBy(() -> personaService.bootstrap(req, ADMIN_BEARER))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
