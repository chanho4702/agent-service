package com.platform.agentservice.run;

import com.platform.agentservice.TestAuth;
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
 * {@link GateController} — 인가 계약(승인/거절=ROLE_ADMIN, 목록=인증 사용자 누구나)과
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

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
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
