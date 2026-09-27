package com.platform.agentservice.run;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.authz.PermissionClient;
import com.platform.agentservice.authz.PermissionDecision;
import com.platform.proto.org.v1.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GateController} — 인가 계약(승인/거절=전역 관리자 또는 run 프로젝트 관리자(P3f), 목록=인증 사용자 누구나)과
 * pending 필터·응답 shape만 본다(RunControllerTest와 같은 패턴). 결정 오케스트레이션 자체는
 * {@link GateServiceTest}가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class GateControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean GateRepository gateRepository;
    @MockitoBean RunRepository runRepository;
    @MockitoBean GateService gateService;
    @MockitoBean PermissionClient permissionClient;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // 목 기본값(null)이 판정 NPE로 새지 않게 — 테스트가 명시한 자원 외에는 org가 거부한다고 둔다.
        given(permissionClient.checkAdmin(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).willReturn(PermissionDecision.deny("NO_GRANT"));
    }

    private Gate gate(long id, long runId) {
        Gate gate = Gate.request(runId, GateKind.MERGE, "요청 내용");
        ReflectionTestUtils.setField(gate, "id", id);
        return gate;
    }

    private Run run(long id, String issueKey) {
        Run run = Run.queued(RunType.TASK, issueKey, 1L, 5L, RunTrigger.USER, "harness://default", null);
        ReflectionTestUtils.setField(run, "id", id);
        return run;
    }

    @Test
    void pending_true_returns_undecided_gates_with_issue_key() throws Exception {
        given(gateRepository.findByDecisionIsNull()).willReturn(List.of(gate(100L, 10L)));
        given(runRepository.findById(10L)).willReturn(Optional.of(run(10L, "AGP-1")));

        mvc.perform(get("/api/agent/gates").param("pending", "true")
                        .with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100))
                .andExpect(jsonPath("$[0].runId").value(10))
                .andExpect(jsonPath("$[0].issueKey").value("AGP-1"))
                .andExpect(jsonPath("$[0].kind").value("MERGE"))
                .andExpect(jsonPath("$[0].decision").doesNotExist());
    }

    @Test
    void pending_absent_returns_recent_50_regardless_of_decision() throws Exception {
        given(gateRepository.findTop50ByOrderByRequestedAtDesc()).willReturn(List.of(gate(101L, 11L)));
        given(runRepository.findById(11L)).willReturn(Optional.of(run(11L, "AGP-2")));

        mvc.perform(get("/api/agent/gates").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(101))
                .andExpect(jsonPath("$[0].issueKey").value("AGP-2"));
    }

    @Test
    void pending_false_also_returns_recent_50() throws Exception {
        given(gateRepository.findTop50ByOrderByRequestedAtDesc()).willReturn(List.of());

        mvc.perform(get("/api/agent/gates").param("pending", "false").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void admin_approve_returns_204() throws Exception {
        mvc.perform(post("/api/agent/gates/100/approve").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isNoContent());

        verify(gateService).approve(100L, 1L);
    }

    /** P3f — 게이트의 대상 프로젝트는 run.projectId다(run(...)은 프로젝트 1). */
    @Test
    void project_admin_decides_gate_of_own_project_only() throws Exception {
        given(gateRepository.findById(200L)).willReturn(Optional.of(gate(200L, 70L)));
        given(runRepository.findById(70L)).willReturn(Optional.of(run(70L, "AGP-70")));
        given(permissionClient.checkAdmin(2L, ResourceType.PROJECT, "1")).willReturn(PermissionDecision.allow());
        given(permissionClient.checkAdmin(3L, ResourceType.PROJECT, "1")).willReturn(PermissionDecision.deny("NO_GRANT"));

        mvc.perform(post("/api/agent/gates/200/approve").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/agent/gates/200/reject").with(authentication(TestAuth.user(3L, "Carol"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("이 프로젝트의 관리자만 할 수 있습니다"));

        verify(gateService).approve(200L, 2L);
        verify(gateService, org.mockito.Mockito.never()).reject(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void non_admin_approve_is_forbidden() throws Exception {
        mvc.perform(post("/api/agent/gates/100/approve").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_reject_returns_204() throws Exception {
        mvc.perform(post("/api/agent/gates/100/reject").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isNoContent());

        verify(gateService).reject(100L, 1L);
    }

    @Test
    void non_admin_reject_is_forbidden() throws Exception {
        mvc.perform(post("/api/agent/gates/100/reject").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
    }
}
