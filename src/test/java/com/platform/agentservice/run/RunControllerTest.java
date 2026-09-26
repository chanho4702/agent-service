package com.platform.agentservice.run;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.run.dto.RunSummaryResponse;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RunController} — 인가 계약(생성·취소·재개=ROLE_ADMIN, 목록=인증 사용자 누구나)·요청 검증과 상태 코드만
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

    private Run queuedUserRun(long id) {
        Run run = Run.queuedUser("AGP-9", 1L, 5L, "harness://default", "claude-sonnet-5", "테스트부터");
        ReflectionTestUtils.setField(run, "id", id);
        return run;
    }

    @Test
    void admin_create_user_run_returns_201_with_summary_and_submits_execution() throws Exception {
        given(runService.createUserRun("AGP-9", "테스트부터", "claude-sonnet-5", "jiho")).willReturn(queuedUserRun(77L));

        mvc.perform(post("/api/agent/runs").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueKey\":\"AGP-9\",\"instruction\":\"테스트부터\","
                                + "\"model\":\"claude-sonnet-5\",\"personaSlug\":\"jiho\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(77))
                .andExpect(jsonPath("$.issueKey").value("AGP-9"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"))
                .andExpect(jsonPath("$.type").value("TASK"))
                .andExpect(jsonPath("$.trigger").value("USER"))
                .andExpect(jsonPath("$.parentRunId").value(org.hamcrest.Matchers.nullValue()));

        verify(runService).execute(77L);
    }

    @Test
    void create_user_run_with_only_issue_key_passes_nulls_for_optional_fields() throws Exception {
        given(runService.createUserRun("AGP-9", null, null, null)).willReturn(queuedUserRun(78L));

        mvc.perform(post("/api/agent/runs").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueKey\":\"AGP-9\"}"))
                .andExpect(status().isCreated());

        verify(runService).createUserRun("AGP-9", null, null, null);
    }

    @Test
    void create_user_run_still_returns_201_when_execution_submission_is_rejected() throws Exception {
        given(runService.createUserRun("AGP-9", null, null, null)).willReturn(queuedUserRun(79L));
        willThrow(new TaskRejectedException("pool full")).given(runService).execute(79L);

        mvc.perform(post("/api/agent/runs").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueKey\":\"AGP-9\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void non_admin_create_user_run_is_forbidden() throws Exception {
        mvc.perform(post("/api/agent/runs").with(authentication(TestAuth.user(2L, "Bob")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueKey\":\"AGP-9\"}"))
                .andExpect(status().isForbidden());

        verify(runService, never()).createUserRun(any(), any(), any(), any());
    }

    @Test
    void create_user_run_without_issue_key_is_400() throws Exception {
        mvc.perform(post("/api/agent/runs").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueKey\":\"  \",\"instruction\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(runService, never()).createUserRun(any(), any(), any(), any());
    }

    @Test
    void create_user_run_with_model_longer_than_column_is_400() throws Exception {
        mvc.perform(post("/api/agent/runs").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueKey\":\"AGP-9\",\"model\":\"" + "m".repeat(61) + "\"}"))
                .andExpect(status().isBadRequest());

        verify(runService, never()).createUserRun(any(), any(), any(), any());
    }

    @Test
    void create_user_run_with_instruction_over_limit_is_400() throws Exception {
        mvc.perform(post("/api/agent/runs").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueKey\":\"AGP-9\",\"instruction\":\"" + "가".repeat(4001) + "\"}"))
                .andExpect(status().isBadRequest());

        verify(runService, never()).createUserRun(any(), any(), any(), any());
    }

    @Test
    void create_user_run_unknown_persona_maps_to_404_error_body() throws Exception {
        given(runService.createUserRun("AGP-9", null, null, "ghost"))
                .willThrow(new NotFoundException("페르소나를 찾을 수 없습니다: ghost"));

        mvc.perform(post("/api/agent/runs").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueKey\":\"AGP-9\",\"personaSlug\":\"ghost\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("페르소나를 찾을 수 없습니다: ghost"));

        verify(runService, never()).execute(anyLong());
    }

    @Test
    void create_user_run_duplicate_active_run_maps_to_409() throws Exception {
        given(runService.createUserRun("AGP-9", null, null, null))
                .willThrow(new ConflictException("이미 진행 중인 run이 있습니다: AGP-9"));

        mvc.perform(post("/api/agent/runs").with(authentication(TestAuth.admin(1L, "Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueKey\":\"AGP-9\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("이미 진행 중인 run이 있습니다: AGP-9"));

        verify(runService, never()).execute(anyLong());
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
                "claude-opus-5", Instant.parse("2026-09-06T00:00:00Z"), null, RunType.REVIEW, RunTrigger.USER, 9L);
        given(runService.list(null)).willReturn(List.of(summary));

        mvc.perform(get("/api/agent/runs").with(authentication(TestAuth.user(2L, "Bob"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].issueKey").value("AGP-1"))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].type").value("REVIEW"))
                .andExpect(jsonPath("$[0].trigger").value("USER"))
                .andExpect(jsonPath("$[0].parentRunId").value(9));

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
