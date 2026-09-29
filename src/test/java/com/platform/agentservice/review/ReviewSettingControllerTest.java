package com.platform.agentservice.review;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.authz.PermissionClient;
import com.platform.agentservice.authz.PermissionDecision;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.proto.org.v1.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리뷰어 지정 API(D-P4b-1) — 응답 shape이 프론트 계약이다: {@code {setting:{personaId,slug,name}|null, effective:{personaId,slug,name,source}}}.
 * 권한(전역 층=전역 관리자, 프로젝트 층 변경=프로젝트 관리자, 프로젝트 조회=누구나)과 지정 검증(400 고정 문구)을 실제 컨텍스트로 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewSettingControllerTest {

    static final long PROJECT = 7L;
    static final long OTHER_PROJECT = 8L;
    private static final AtomicLong SEQ = new AtomicLong(93_000);

    @Autowired WebApplicationContext context;
    @Autowired PersonaRepository personaRepository;
    @Autowired ReviewSettingRepository settingRepository;
    @MockitoBean PermissionClient permissionClient;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        settingRepository.deleteAll();
        // 다른 테스트 클래스가 같은 H2에 남긴 활성 REVIEWER가 자동 선택에 끼지 않게 비활성으로 돌린다.
        personaRepository.findAll().stream().filter(p -> p.getRole() == PersonaRole.REVIEWER && p.isActive())
                .forEach(p -> {
                    p.changeActive(false);
                    personaRepository.save(p);
                });
        given(permissionClient.checkAdmin(anyLong(), any(), any())).willReturn(PermissionDecision.deny("NO_GRANT"));
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, String.valueOf(PROJECT))).willReturn(PermissionDecision.allow());
    }

    private Persona persona(PersonaRole role, Long projectId, boolean active) {
        long n = SEQ.incrementAndGet();
        Persona p = Persona.of(n, "rv" + n, role, "리뷰어" + n, "🔍", null, projectId);
        ReflectionTestUtils.setField(p, "active", active);
        return personaRepository.save(p);
    }

    private static String body(Long personaId) {
        return "{\"personaId\":" + personaId + "}";
    }

    @Test
    void global_admin_sets_the_platform_reviewer_and_reads_the_contract_shape() throws Exception {
        Persona shared = persona(PersonaRole.REVIEWER, null, true);

        mvc.perform(put("/api/agent/review-settings/platform").contentType(MediaType.APPLICATION_JSON).content(body(shared.getId()))
                        .with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setting.personaId").value(shared.getId()))
                .andExpect(jsonPath("$.setting.slug").value(shared.getSlug()))
                .andExpect(jsonPath("$.setting.name").value(shared.getName()))
                .andExpect(jsonPath("$.effective.personaId").value(shared.getId()))
                .andExpect(jsonPath("$.effective.source").value("PLATFORM"));

        mvc.perform(get("/api/agent/review-settings/platform").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.source").value("PLATFORM"));
        // 프로젝트 설정이 없으면 프로젝트 조회에도 전역 지정이 유효 리뷰어로 나온다.
        mvc.perform(get("/api/agent/review-settings/projects/" + PROJECT).with(authentication(TestAuth.user(5L, "U"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setting").doesNotExist())
                .andExpect(jsonPath("$.effective.personaId").value(shared.getId()))
                .andExpect(jsonPath("$.effective.source").value("PLATFORM"));
    }

    @Test
    void platform_layer_is_closed_to_non_global_admins_even_for_reads() throws Exception {
        mvc.perform(get("/api/agent/review-settings/platform").with(authentication(TestAuth.user(2L, "ProjectAdmin"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("전역 관리자만 할 수 있습니다"));
        mvc.perform(put("/api/agent/review-settings/platform").contentType(MediaType.APPLICATION_JSON).content(body(1L))
                        .with(authentication(TestAuth.user(2L, "ProjectAdmin"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/agent/review-settings/platform").with(authentication(TestAuth.user(2L, "ProjectAdmin"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void project_admin_sets_a_project_owned_reviewer_and_delete_falls_back_to_auto() throws Exception {
        Persona own = persona(PersonaRole.REVIEWER, PROJECT, true);
        Persona shared = persona(PersonaRole.REVIEWER, null, true);

        mvc.perform(put("/api/agent/review-settings/projects/" + PROJECT).contentType(MediaType.APPLICATION_JSON)
                        .content(body(shared.getId())).with(authentication(TestAuth.user(2L, "ProjectAdmin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setting.personaId").value(shared.getId()))
                .andExpect(jsonPath("$.effective.source").value("PROJECT"));
        assertThat(settingRepository.findByScopeAndScopeId(ReviewScope.PROJECT, PROJECT)).get()
                .satisfies(s -> assertThat(s.getUpdatedBy()).isEqualTo(2L));

        mvc.perform(delete("/api/agent/review-settings/projects/" + PROJECT).with(authentication(TestAuth.user(2L, "ProjectAdmin"))))
                .andExpect(status().isNoContent());
        // 지정이 없으면 자동 — 그 프로젝트 소속 REVIEWER가 공용보다 먼저다(id가 더 작아서가 아니라 소속이라서).
        mvc.perform(get("/api/agent/review-settings/projects/" + PROJECT).with(authentication(TestAuth.user(5L, "U"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setting").doesNotExist())
                .andExpect(jsonPath("$.effective.personaId").value(own.getId()))
                .andExpect(jsonPath("$.effective.source").value("AUTO"));
        // 멱등 삭제
        mvc.perform(delete("/api/agent/review-settings/projects/" + PROJECT).with(authentication(TestAuth.user(2L, "ProjectAdmin"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void non_admin_cannot_change_a_project_reviewer_but_can_read_it() throws Exception {
        Persona shared = persona(PersonaRole.REVIEWER, null, true);
        mvc.perform(put("/api/agent/review-settings/projects/" + PROJECT).contentType(MediaType.APPLICATION_JSON)
                        .content(body(shared.getId())).with(authentication(TestAuth.user(5L, "U"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("이 프로젝트의 관리자만 할 수 있습니다"));
        mvc.perform(get("/api/agent/review-settings/projects/" + PROJECT).with(authentication(TestAuth.user(5L, "U"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.source").value("AUTO"));
    }

    @Test
    void unassignable_personas_are_rejected_with_the_fixed_400_message() throws Exception {
        Persona otherProject = persona(PersonaRole.REVIEWER, OTHER_PROJECT, true);
        Persona backend = persona(PersonaRole.BACKEND, null, true);
        Persona inactive = persona(PersonaRole.REVIEWER, null, false);
        Persona ownProject = persona(PersonaRole.REVIEWER, PROJECT, true);

        for (Long id : new Long[]{otherProject.getId(), backend.getId(), inactive.getId(), 999_999L}) {
            mvc.perform(put("/api/agent/review-settings/projects/" + PROJECT).contentType(MediaType.APPLICATION_JSON)
                            .content(body(id)).with(authentication(TestAuth.user(2L, "ProjectAdmin"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(ReviewerResolver.INVALID_REVIEWER));
        }
        // 프로젝트 소속 페르소나는 전역 지정이 될 수 없다(다른 프로젝트 리뷰를 맡게 된다).
        mvc.perform(put("/api/agent/review-settings/platform").contentType(MediaType.APPLICATION_JSON)
                        .content(body(ownProject.getId())).with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(ReviewerResolver.INVALID_REVIEWER));
        mvc.perform(put("/api/agent/review-settings/projects/" + PROJECT).contentType(MediaType.APPLICATION_JSON)
                        .content("{}").with(authentication(TestAuth.user(2L, "ProjectAdmin"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(ReviewerResolver.PERSONA_ID_REQUIRED));
        assertThat(settingRepository.findAll()).isEmpty();
    }

    @Test
    void effective_is_none_with_null_fields_when_no_reviewer_exists() throws Exception {
        mvc.perform(get("/api/agent/review-settings/projects/" + PROJECT).with(authentication(TestAuth.user(5L, "U"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setting").doesNotExist())
                .andExpect(jsonPath("$.effective.personaId").doesNotExist())
                .andExpect(jsonPath("$.effective.source").value("NONE"));
    }
}
