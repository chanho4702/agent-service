package com.platform.agentservice.persona;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.authz.PermissionClient;
import com.platform.agentservice.authz.PermissionDecision;
import com.platform.agentservice.client.AuthTokenClient;
import com.platform.agentservice.client.OrgClient;
import com.platform.proto.org.v1.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P3f 블로커 해소 확인 — 전역 관리자가 아닌 프로젝트 관리자가 새 페르소나를 끝까지 만든다. 컨트롤러·{@code AgentAuthz}·
 * {@link PersonaService}·저장소는 실물이고, 다운스트림 클라이언트와 org 판정만 목이다. 내부 경로의 HTTP 헤더·본문은
 * {@link PersonaServiceTest}가 MockRestServiceServer로 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonaBootstrapEndToEndTest {

    @Autowired WebApplicationContext context;
    @Autowired PersonaRepository personaRepository;
    @MockitoBean PermissionClient permissionClient;
    @MockitoBean AuthTokenClient authTokenClient;
    @MockitoBean OrgClient orgClient;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        given(permissionClient.checkAdmin(anyLong(), any(), any())).willReturn(PermissionDecision.deny("NO_GRANT"));
    }

    @Test
    void project_admin_creates_new_persona_through_internal_registration() throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());
        given(permissionClient.checkAdmin(2L, ResourceType.SPACE, "3")).willReturn(PermissionDecision.allow());
        given(authTokenClient.registerAgent("e2e-bot", "E2E Bot", "agents+e2e-bot@platform.local"))
                .willReturn(new AuthTokenClient.AgentRegistration(9101L, "e2e-bot", true));

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob")))
                        .header("Authorization", "AdminSession bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"e2e-bot","role":"BACKEND","name":"E2E Bot","projectId":7,
                                 "grants":[{"resourceType":"PROJECT","resourceId":"7","role":"EDITOR"},
                                           {"resourceType":"SPACE","resourceId":"3","role":"EDITOR"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").value(9101))
                .andExpect(jsonPath("$.projectId").value(7));

        var order = inOrder(authTokenClient, orgClient);
        order.verify(authTokenClient).registerAgent("e2e-bot", "E2E Bot", "agents+e2e-bot@platform.local");
        order.verify(orgClient).registerAgentMember(9101L, "E2E Bot", "agents+e2e-bot@platform.local", 2L);
        order.verify(orgClient).grant(9101L, "PROJECT", "7", "EDITOR", "AdminSession bob");
        order.verify(orgClient).grant(9101L, "SPACE", "3", "EDITOR", "AdminSession bob");

        Persona saved = personaRepository.findBySlug("e2e-bot").orElseThrow();
        assertThat(saved.getProjectId()).isEqualTo(7L);
        assertThat(saved.getMemberId()).isEqualTo(9101L);
    }
}
