package com.platform.agentservice.pat;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.authz.PermissionClient;
import com.platform.agentservice.authz.PermissionDecision;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.proto.org.v1.ResourceType;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.pat.dto.PatSummaryResponse;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인가 계약(발급/목록/철회=전역 관리자 또는 페르소나 소속 프로젝트 관리자, P3f)과 응답 shape만 본다 — 실제 발급/검증 로직은
 * {@link PatServiceTest}가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PatControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean PatService patService;
    @MockitoBean PermissionClient permissionClient;
    @Autowired PersonaRepository personaRepository;
    @Autowired PatTokenRepository patTokenRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // 목 기본값(null)이 판정 NPE로 새지 않게 — 테스트가 명시한 자원 외에는 org가 거부한다고 둔다.
        given(permissionClient.checkAdmin(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).willReturn(PermissionDecision.deny("NO_GRANT"));
    }

    @Test
    void admin_creates_token_returns_201_with_plaintext_once() throws Exception {
        given(patService.issueForHuman(any(), eq(1L), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .willReturn(new PatCreatedResponse("agp_abcdef", 10L, "ci-token", "qa-bot"));

        String body = """
                {"label":"ci-token","personaSlug":"qa-bot"}
                """;

        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("agp_abcdef"))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.label").value("ci-token"))
                .andExpect(jsonPath("$.personaSlug").value("qa-bot"));
    }

    /** M4 — label은 pat_token.label VARCHAR(120). 초과분은 DB까지 가지 않고 400으로 끊어야 한다. */
    @Test
    void label_over_column_width_returns_400_with_korean_error() throws Exception {
        String body = """
                {"label":"%s","personaSlug":"qa-bot"}
                """.formatted("a".repeat(121));

        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("label은 120자 이하여야 합니다"));
    }

    /** 경계값 — 정확히 120자 label은 통과해야 한다. */
    @Test
    void label_at_exact_column_width_is_accepted() throws Exception {
        given(patService.issueForHuman(any(), eq(1L), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .willReturn(new PatCreatedResponse("agp_abcdef", 10L, "l", "qa-bot"));

        String body = """
                {"label":"%s","personaSlug":"qa-bot"}
                """.formatted("a".repeat(120));

        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void non_admin_cannot_create_token() throws Exception {
        String body = """
                {"label":"ci-token","personaSlug":"qa-bot"}
                """;

        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_can_list_tokens_without_hash() throws Exception {
        given(patService.list(null)).willReturn(List.of(
                new PatSummaryResponse(10L, "ci-token", "qa-bot", Instant.now(), null, null, false)));

        mvc.perform(get("/api/agent/tokens").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].personaSlug").value("qa-bot"))
                .andExpect(jsonPath("$[0].revoked").value(false))
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test
    void non_admin_cannot_list_tokens() throws Exception {
        mvc.perform(get("/api/agent/tokens").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());

        verify(patService, org.mockito.Mockito.never()).list(any());
    }

    // ---- P3f: 프로젝트 관리자 ----

    @Test
    void project_admin_issues_token_for_own_project_persona_but_not_shared() throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());
        personaRepository.save(Persona.of(9711L, "pat-own", PersonaRole.BACKEND, "O", null, null, 7L));
        personaRepository.save(Persona.of(9712L, "pat-shared", PersonaRole.BACKEND, "S", null, null));
        given(patService.issueForHuman(any(), eq(2L), any(), org.mockito.ArgumentMatchers.anyBoolean())).willReturn(new PatCreatedResponse("agp_x", 11L, "l", "pat-own"));

        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"l\",\"personaSlug\":\"pat-own\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"l\",\"personaSlug\":\"pat-shared\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전사 공용 페르소나는 전역 관리자만 관리할 수 있습니다"));
    }

    @Test
    void project_admin_lists_tokens_only_with_managed_project_filter() throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "8")).willReturn(PermissionDecision.deny("NO_GRANT"));
        given(patService.list(7L)).willReturn(List.of());

        mvc.perform(get("/api/agent/tokens").param("projectId", "7").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/agent/tokens").param("projectId", "8").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/agent/tokens").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전역 관리자만 할 수 있습니다"));

        verify(patService).list(7L);
    }

    @Test
    void project_admin_revokes_token_of_own_project_persona_only() throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());
        Persona own = personaRepository.save(Persona.of(9721L, "rev-own", PersonaRole.BACKEND, "O", null, null, 7L));
        Persona shared = personaRepository.save(Persona.of(9722L, "rev-shared", PersonaRole.BACKEND, "S", null, null));
        PatToken ownToken = patTokenRepository.save(PatToken.of("a".repeat(64), "own", 1L, own.getId(), null));
        PatToken sharedToken = patTokenRepository.save(PatToken.of("b".repeat(64), "shared", 1L, shared.getId(), null));

        mvc.perform(delete("/api/agent/tokens/" + ownToken.getId()).with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/agent/tokens/" + sharedToken.getId()).with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());

        verify(patService).revoke(ownToken.getId());
        verify(patService, org.mockito.Mockito.never()).revoke(sharedToken.getId());
    }

    @Test
    void admin_revokes_token_returns_204() throws Exception {
        mvc.perform(delete("/api/agent/tokens/10").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isNoContent());

        verify(patService).revoke(10L);
    }

    @Test
    void non_admin_cannot_revoke_token() throws Exception {
        mvc.perform(delete("/api/agent/tokens/10").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
    }

    /** D-P4-3b — 만료 기간은 1~365일. 요청 경계에서 400(서비스까지 가지 않는다). */
    @Test
    void expires_in_days_out_of_range_is_400() throws Exception {
        for (String days : List.of("0", "366")) {
            mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.admin(1L, "Admin")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"label\":\"l\",\"personaSlug\":\"qa-bot\",\"expiresInDays\":" + days + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("expiresInDays는 1~365 사이여야 합니다"));
        }
        verify(patService, org.mockito.Mockito.never()).issueForHuman(any(), org.mockito.ArgumentMatchers.anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** D-P4-3b — 컨트롤러는 JWT의 전역 관리자 여부와 email 클레임을 서비스 정책에 넘긴다. */
    @Test
    void create_passes_global_admin_flag_and_owner_email_to_policy() throws Exception {
        given(patService.issueForHuman(any(), eq(2L), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .willReturn(new PatCreatedResponse("agp_x", 12L, "l", "qa-bot", Instant.now()));
        personaRepository.save(Persona.of(9713L, "email-own", PersonaRole.BACKEND, "O", null, null, 7L));
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());

        mvc.perform(post("/api/agent/tokens").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"l\",\"personaSlug\":\"email-own\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").exists());
        verify(patService).issueForHuman(any(), eq(2L), org.mockito.ArgumentMatchers.isNull(), eq(false));
    }
}
