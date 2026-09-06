package com.platform.agentservice.run;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.run.dto.RunSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RunController} — 인가 계약(취소=ROLE_ADMIN, 목록=인증 사용자 누구나)과 상태 코드만
 * 본다(PersonaControllerTest와 같은 패턴). 오케스트레이션 자체는 {@link RunServiceTest}가 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class RunControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean RunService runService;
    @MockitoBean RunResumeService runResumeService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void admin_cancel_returns_204() throws Exception {
        mvc.perform(post("/api/agent/runs/42/cancel").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isNoContent());

        verify(runService).cancel(42L);
    }

    @Test
    void non_admin_cancel_is_forbidden() throws Exception {
        mvc.perform(post("/api/agent/runs/42/cancel").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_resume_returns_204() throws Exception {
        mvc.perform(post("/api/agent/runs/42/resume").with(authentication(TestAuth.admin(1L, "Admin"))))
                .andExpect(status().isNoContent());

        verify(runResumeService).resume(42L);
    }

    @Test
    void non_admin_resume_is_forbidden() throws Exception {
        mvc.perform(post("/api/agent/runs/42/resume").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticated_user_can_list_runs_without_status_filter() throws Exception {
        RunSummaryResponse summary = new RunSummaryResponse(1L, "AGP-1", RunStatus.RUNNING, 5L, 1,
                "claude-opus-5", Instant.parse("2026-09-06T00:00:00Z"), null);
        given(runService.list(null)).willReturn(List.of(summary));

        mvc.perform(get("/api/agent/runs").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].issueKey").value("AGP-1"))
                .andExpect(jsonPath("$[0].status").value("RUNNING"));

        verify(runService).list(isNull());
    }

    @Test
    void authenticated_user_can_list_runs_with_status_filter() throws Exception {
        given(runService.list(RunStatus.BLOCKED)).willReturn(List.of());

        mvc.perform(get("/api/agent/runs").param("status", "BLOCKED").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(runService).list(eq(RunStatus.BLOCKED));
    }
}
