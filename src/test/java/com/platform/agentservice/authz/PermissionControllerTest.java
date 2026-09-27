package com.platform.agentservice.authz;

import com.platform.agentservice.TestAuth;
import com.platform.proto.org.v1.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/agent/permissions}(D-P3f-6) — 응답 키 이름({canManage, isGlobalAdmin})이 프론트 계약이다. */
@SpringBootTest
@ActiveProfiles("test")
class PermissionControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean PermissionClient permissionClient;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // 목 기본값(null)이 판정 NPE로 새지 않게 — 테스트가 명시한 자원 외에는 org가 거부한다고 둔다.
        given(permissionClient.checkAdmin(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).willReturn(PermissionDecision.deny("NO_GRANT"));
    }

    @Test
    void project_admin_can_manage_own_project() throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());

        mvc.perform(get("/api/agent/permissions").param("projectId", "7").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.isGlobalAdmin").value(false));
    }

    @Test
    void non_manager_and_org_failure_both_answer_false_not_error() throws Exception {
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "8")).willReturn(PermissionDecision.deny("NO_GRANT"));
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "9")).willReturn(PermissionDecision.orgFailure());

        mvc.perform(get("/api/agent/permissions").param("projectId", "8").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(false));
        mvc.perform(get("/api/agent/permissions").param("projectId", "9").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(false));
    }

    @Test
    void global_admin_can_manage_everything_without_org() throws Exception {
        mvc.perform(get("/api/agent/permissions").param("projectId", "7").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.isGlobalAdmin").value(true));

        verifyNoInteractions(permissionClient);
    }

    @Test
    void without_project_only_global_admin_can_manage() throws Exception {
        mvc.perform(get("/api/agent/permissions").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(false))
                .andExpect(jsonPath("$.isGlobalAdmin").value(false));
    }

    @Test
    void unauthenticated_is_401() throws Exception {
        mvc.perform(get("/api/agent/permissions")).andExpect(status().isUnauthorized());
    }
}
