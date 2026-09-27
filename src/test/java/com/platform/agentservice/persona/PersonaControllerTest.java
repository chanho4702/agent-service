package com.platform.agentservice.persona;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.authz.PermissionClient;
import com.platform.agentservice.authz.PermissionDecision;
import com.platform.proto.org.v1.ResourceType;
import com.platform.agentservice.persona.dto.PersonaDetailResponse;
import com.platform.agentservice.persona.dto.PersonaResponse;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러의 인가 계약(전역 관리자 또는 소속 프로젝트 관리자 + 부여 상한, P3f)과 상태 코드(생성=201/기존 갱신=200)만 본다 —
 * auth-server·org-service 연동 자체는 {@link PersonaServiceTest}(MockRestServiceServer)가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonaControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean PersonaService personaService;
    @MockitoBean PermissionClient permissionClient;
    @Autowired PersonaRepository personaRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // 목 기본값(null)이 판정 NPE로 새지 않게 — 테스트가 명시한 자원 외에는 org가 거부한다고 둔다.
        given(permissionClient.checkAdmin(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).willReturn(PermissionDecision.deny("NO_GRANT"));
    }

    @Test
    void admin_creates_persona_returns_201() throws Exception {
        PersonaResponse response = new PersonaResponse(1L, 9001L, "qa-bot", PersonaRole.REVIEWER, "QA Bot", "🤖", true, null);
        given(personaService.bootstrap(any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .willReturn(new PersonaService.BootstrapResult(response, true));

        String body = """
                {"slug":"qa-bot","role":"REVIEWER","name":"QA Bot","emoji":"🤖"}
                """;

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.admin(1L, "Admin")))
                        .header("Authorization", "AdminSession admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("qa-bot"))
                .andExpect(jsonPath("$.memberId").value(9001));
    }

    @Test
    void admin_refresh_of_existing_slug_returns_200() throws Exception {
        PersonaResponse response = new PersonaResponse(1L, 9001L, "qa-bot", PersonaRole.REVIEWER, "QA Bot v2", "🤖", true, null);
        given(personaService.bootstrap(any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .willReturn(new PersonaService.BootstrapResult(response, false));

        String body = """
                {"slug":"qa-bot","role":"REVIEWER","name":"QA Bot v2"}
                """;

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.admin(1L, "Admin")))
                        .header("Authorization", "AdminSession admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("QA Bot v2"));
    }

    /** M4 — name은 persona.name VARCHAR(80). 초과분은 DB까지 가지 않고 400으로 끊어야 한다. */
    @Test
    void name_over_column_width_returns_400_with_korean_error() throws Exception {
        String body = """
                {"slug":"qa-bot","role":"REVIEWER","name":"%s"}
                """.formatted("가".repeat(81));

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.admin(1L, "Admin")))
                        .header("Authorization", "AdminSession admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("name은 80자 이하여야 합니다"));
    }

    /** M4 — emoji는 persona.emoji VARCHAR(16). */
    @Test
    void emoji_over_column_width_returns_400() throws Exception {
        String body = """
                {"slug":"qa-bot","role":"REVIEWER","name":"QA Bot","emoji":"%s"}
                """.formatted("x".repeat(17));

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.admin(1L, "Admin")))
                        .header("Authorization", "AdminSession admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("emoji는 16자 이하여야 합니다"));
    }

    /** 경계값 — 정확히 컬럼폭까지는 통과해야 한다(80자 name, 16자 emoji). */
    @Test
    void name_and_emoji_at_exact_column_width_are_accepted() throws Exception {
        PersonaResponse response = new PersonaResponse(1L, 9001L, "qa-bot", PersonaRole.REVIEWER, "n", "e", true, null);
        given(personaService.bootstrap(any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .willReturn(new PersonaService.BootstrapResult(response, true));

        String body = """
                {"slug":"qa-bot","role":"REVIEWER","name":"%s","emoji":"%s"}
                """.formatted("가".repeat(80), "x".repeat(16));

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.admin(1L, "Admin")))
                        .header("Authorization", "AdminSession admin-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void non_admin_is_forbidden() throws Exception {
        String body = """
                {"slug":"qa-bot","role":"REVIEWER","name":"QA Bot"}
                """;

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob")))
                        .header("Authorization", "AdminSession user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    // ---- P3f: 프로젝트 관리자 ----

    private static final String PROJECT_PERSONA_BODY = """
            {"slug":"%s","role":"BACKEND","name":"P Bot","projectId":7,"grants":[%s]}
            """;

    private void projectAdminOf7() {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());
    }

    @Test
    void project_admin_creates_persona_in_own_project_with_grant_on_managed_resource() throws Exception {
        projectAdminOf7();
        given(personaService.bootstrap(any(), any(), org.mockito.ArgumentMatchers.anyLong())).willReturn(new PersonaService.BootstrapResult(
                new PersonaResponse(5L, 9005L, "p7-bot", PersonaRole.BACKEND, "P Bot", null, true, 7L), true));

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob")))
                        .header("Authorization", "AdminSession bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PROJECT_PERSONA_BODY.formatted("p7-bot",
                                "{\"resourceType\":\"PROJECT\",\"resourceId\":\"7\",\"role\":\"EDITOR\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").value(7));

        // 내부 등록 경로의 actorId = 호출자 JWT sub
        org.mockito.Mockito.verify(personaService).bootstrap(any(), org.mockito.ArgumentMatchers.eq("AdminSession bob"),
                org.mockito.ArgumentMatchers.eq(2L));
    }

    @Test
    void other_project_admin_cannot_create_persona_in_project() throws Exception {
        given(permissionClient.checkAdmin(3L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.deny("NO_GRANT"));

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.user(3L, "Carol")))
                        .header("Authorization", "AdminSession carol")
                        .contentType(MediaType.APPLICATION_JSON).content(PROJECT_PERSONA_BODY.formatted("p7-carol", "")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("이 프로젝트의 관리자만 할 수 있습니다"));

        org.mockito.Mockito.verify(personaService, org.mockito.Mockito.never()).bootstrap(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shared_persona_creation_is_global_admin_only() throws Exception {
        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob")))
                        .header("Authorization", "AdminSession bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"shared-x\",\"role\":\"BACKEND\",\"name\":\"S\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전사 공용 페르소나는 전역 관리자만 관리할 수 있습니다"));

        org.mockito.Mockito.verifyNoInteractions(permissionClient);
    }

    /** D-P3f-4 — 관리하지 않는 스페이스 권한을 페르소나에 실어 우회하지 못한다. */
    @Test
    void project_admin_cannot_grant_unmanaged_space() throws Exception {
        projectAdminOf7();
        given(permissionClient.checkAdmin(2L, ResourceType.SPACE, "3")).willReturn(PermissionDecision.deny("INSUFFICIENT_ROLE"));

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob")))
                        .header("Authorization", "AdminSession bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PROJECT_PERSONA_BODY.formatted("p7-space",
                                "{\"resourceType\":\"SPACE\",\"resourceId\":\"3\",\"role\":\"EDITOR\"}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("관리하지 않는 자원 권한은 부여할 수 없습니다"));

        org.mockito.Mockito.verify(personaService, org.mockito.Mockito.never()).bootstrap(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void project_admin_cannot_grant_global() throws Exception {
        projectAdminOf7();

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob")))
                        .header("Authorization", "AdminSession bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PROJECT_PERSONA_BODY.formatted("p7-global",
                                "{\"resourceType\":\"GLOBAL\",\"resourceId\":\"-\",\"role\":\"ADMIN\"}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("GLOBAL 권한은 전역 관리자만 부여할 수 있습니다"));
    }

    @Test
    void project_admin_toggles_own_project_persona_but_not_shared_one() throws Exception {
        projectAdminOf7();
        Persona own = personaRepository.save(Persona.of(9701L, "toggle-own", PersonaRole.BACKEND, "O", null, null, 7L));
        Persona shared = personaRepository.save(Persona.of(9702L, "toggle-shared", PersonaRole.BACKEND, "S", null, null));
        given(personaService.changeActive(own.getId(), false)).willReturn(PersonaResponse.from(own));

        mvc.perform(patch("/api/agent/personas/" + own.getId() + "/active").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/agent/personas/" + shared.getId() + "/active").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전사 공용 페르소나는 전역 관리자만 관리할 수 있습니다"));
    }

    /** 기존 슬러그 재호출은 요청 projectId가 아니라 그 페르소나의 소속으로 판정한다 — 남의 페르소나를 덮어쓰지 못하게. */
    @Test
    void project_admin_cannot_refresh_persona_of_other_project_by_slug() throws Exception {
        projectAdminOf7();
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "8")).willReturn(PermissionDecision.deny("NO_GRANT"));
        personaRepository.save(Persona.of(9801L, "p8-owned", PersonaRole.BACKEND, "E", null, null, 8L));

        mvc.perform(post("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob")))
                        .header("Authorization", "AdminSession bob")
                        .contentType(MediaType.APPLICATION_JSON).content(PROJECT_PERSONA_BODY.formatted("p8-owned", "")))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(personaService, org.mockito.Mockito.never()).bootstrap(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    // ---- AGP-62: PATCH /{id} 권한 — 활성 토글과 같은 판정 ----

    @Test
    void project_admin_edits_own_project_persona() throws Exception {
        projectAdminOf7();
        Persona own = personaRepository.save(Persona.of(9711L, "edit-own", PersonaRole.BACKEND, "O", null, null, 7L));
        given(personaService.edit(org.mockito.ArgumentMatchers.eq(own.getId()), any())).willReturn(new PersonaDetailResponse(
                own.getId(), 9711L, "edit-own", PersonaRole.BACKEND, "O2", null, true, 7L, null, null, "claude-x", "skill"));

        mvc.perform(patch("/api/agent/personas/" + own.getId()).with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"O2\",\"defaultModel\":\"claude-x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultModel").value("claude-x"))
                .andExpect(jsonPath("$.skills").value("skill"));
    }

    @Test
    void other_project_admin_cannot_edit_persona() throws Exception {
        given(permissionClient.checkAdmin(3L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.deny("NO_GRANT"));
        Persona own = personaRepository.save(Persona.of(9712L, "edit-other", PersonaRole.BACKEND, "O", null, null, 7L));

        mvc.perform(patch("/api/agent/personas/" + own.getId()).with(authentication(TestAuth.user(3L, "Carol")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("이 프로젝트의 관리자만 할 수 있습니다"));

        org.mockito.Mockito.verify(personaService, org.mockito.Mockito.never()).edit(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void shared_persona_edit_is_global_admin_only() throws Exception {
        projectAdminOf7();
        Persona shared = personaRepository.save(Persona.of(9713L, "edit-shared", PersonaRole.BACKEND, "S", null, null));
        given(personaService.edit(org.mockito.ArgumentMatchers.eq(shared.getId()), any())).willReturn(PersonaDetailResponse.from(shared));

        mvc.perform(patch("/api/agent/personas/" + shared.getId()).with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전사 공용 페르소나는 전역 관리자만 관리할 수 있습니다"));
        mvc.perform(patch("/api/agent/personas/" + shared.getId()).with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isOk());
    }

    /** 없는 id는 전역 관리자만 통과(서비스 404) — 프로젝트 관리자에게 존재 여부를 흘리지 않는다. */
    @Test
    void unknown_persona_edit_is_403_for_project_admin_and_404_for_global_admin() throws Exception {
        projectAdminOf7();
        given(personaService.edit(org.mockito.ArgumentMatchers.eq(987654L), any()))
                .willThrow(new com.platform.common.error.NotFoundException("페르소나를 찾을 수 없습니다: 987654"));

        mvc.perform(patch("/api/agent/personas/987654").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/agent/personas/987654").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- AGP-62: GET /{id} 관리자 전용 상세 ----

    @Test
    void detail_is_manager_only_and_shared_persona_detail_is_global_only() throws Exception {
        projectAdminOf7();
        given(permissionClient.checkAdmin(3L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.deny("NO_GRANT"));
        Persona own = personaRepository.save(Persona.of(9721L, "detail-own", PersonaRole.BACKEND, "O", null, null, 7L));
        Persona shared = personaRepository.save(Persona.of(9722L, "detail-shared", PersonaRole.BACKEND, "S", null, null));
        given(personaService.detail(own.getId())).willReturn(new PersonaDetailResponse(own.getId(), 9721L, "detail-own",
                PersonaRole.BACKEND, "O", null, true, 7L, "{\"v\":1}", "반말", "claude-x", "## 스킬"));
        given(personaService.detail(shared.getId())).willReturn(PersonaDetailResponse.from(shared));

        mvc.perform(get("/api/agent/personas/" + own.getId()).with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voicePrompt").value("반말"))
                .andExpect(jsonPath("$.defaultModel").value("claude-x"))
                .andExpect(jsonPath("$.skills").value("## 스킬"))
                .andExpect(jsonPath("$.avatarConfig").value("{\"v\":1}"));
        mvc.perform(get("/api/agent/personas/" + own.getId()).with(authentication(TestAuth.user(3L, "Carol"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/agent/personas/" + shared.getId()).with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전사 공용 페르소나는 전역 관리자만 관리할 수 있습니다"));
        mvc.perform(get("/api/agent/personas/" + shared.getId()).with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(personaService, org.mockito.Mockito.times(1)).detail(own.getId());
    }

    @Test
    void authenticated_user_can_list_personas() throws Exception {
        given(personaService.list()).willReturn(java.util.List.of(
                new PersonaResponse(1L, 9001L, "qa-bot", PersonaRole.REVIEWER, "QA Bot", "🤖", true, null)));

        mvc.perform(get("/api/agent/personas").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("qa-bot"));
    }

    @Test
    void admin_deactivates_persona_returns_200_with_active_false() throws Exception {
        given(personaService.changeActive(1L, false)).willReturn(
                new PersonaResponse(1L, 9001L, "qa-bot", PersonaRole.REVIEWER, "QA Bot", "🤖", false, null));

        mvc.perform(patch("/api/agent/personas/1/active").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void non_admin_cannot_change_persona_active() throws Exception {
        mvc.perform(patch("/api/agent/personas/1/active").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(personaService, org.mockito.Mockito.never())
                .changeActive(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void missing_active_value_returns_400_with_korean_error() throws Exception {
        mvc.perform(patch("/api/agent/personas/1/active").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("active 값이 필요합니다"));
    }

    @Test
    void unknown_persona_active_change_is_404() throws Exception {
        given(personaService.changeActive(99L, true))
                .willThrow(new com.platform.common.error.NotFoundException("페르소나를 찾을 수 없습니다: 99"));

        mvc.perform(patch("/api/agent/personas/99/active").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("페르소나를 찾을 수 없습니다: 99"));
    }
}
