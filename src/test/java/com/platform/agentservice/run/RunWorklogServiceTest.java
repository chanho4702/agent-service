package com.platform.agentservice.run;

import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.audit.ToolCallAuditRepository;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.WorklogRequest;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** P4b D-P4b-2(AGP-60) — run 종결 워크로그: 대상·시간 계산·중복 방지·best-effort. */
@ExtendWith(MockitoExtension.class)
class RunWorklogServiceTest {

    private static final long PERSONA_ID = 5L;
    private static final long MEMBER_ID = 42L;
    private static final String BEARER = "Bearer persona";
    /** 2026-09-29T15:30Z = 한국 9월 30일 00:30 — 작업일은 한국 날짜여야 한다. */
    private static final Instant NOW = Instant.parse("2026-09-29T15:30:00Z");

    @Mock PersonaRepository personaRepository;
    @Mock TokenService tokenService;
    @Mock AlmClient almClient;
    @Mock ToolCallAuditRepository auditRepository;

    private RunWorklogService service;

    @BeforeEach
    void setUp() {
        service = service(true);
        Persona persona = Persona.of(MEMBER_ID, "jiho", PersonaRole.BACKEND, "지호", "🔧", null);
        ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
        lenient().when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.of(persona));
        lenient().when(tokenService.bearerFor(MEMBER_ID)).thenReturn(BEARER);
        lenient().when(almClient.getByKey(any(), eq(BEARER))).thenAnswer(inv -> issue(inv.getArgument(0)));
    }

    private RunWorklogService service(boolean auto) {
        return new RunWorklogService(new WorklogProperties(auto), personaRepository, tokenService, almClient,
                auditRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static IssueResponse issue(String key) {
        return new IssueResponse(77L, key, 1L, "t", "d", "task", "inprogress", "high", MEMBER_ID,
                1L, null, null, null, null, null, null, List.of(), List.of(), 0L, 1, null, null, null);
    }

    private static Run run(RunType type, RunStatus status, String issueKey, Instant startedAt, Instant endedAt) {
        Run run = type.isMeeting()
                ? Run.queuedMeeting(type, issueKey, 1L, List.of(PERSONA_ID), RunTrigger.USER, "h", null, "안건")
                : Run.queued(type, issueKey, 1L, PERSONA_ID, RunTrigger.SCHEDULER, "h", null);
        ReflectionTestUtils.setField(run, "id", 812L);
        ReflectionTestUtils.setField(run, "status", status);
        ReflectionTestUtils.setField(run, "startedAt", startedAt);
        ReflectionTestUtils.setField(run, "endedAt", endedAt);
        return run;
    }

    private WorklogRequest capturedWorklog() {
        ArgumentCaptor<WorklogRequest> captor = ArgumentCaptor.forClass(WorklogRequest.class);
        verify(almClient).addWorklog(eq(77L), captor.capture(), eq(BEARER));
        return captor.getValue();
    }

    @Test
    void done_task_logs_ceil_minutes_as_persona_with_fixed_comment_and_korean_work_date() {
        Instant start = NOW.minusSeconds(7 * 60 + 1);
        service.onRunEnded(run(RunType.TASK, RunStatus.DONE, "AGP-9", start, NOW));

        WorklogRequest request = capturedWorklog();
        // 7분 1초 → 8분 → 8/60h = 0.1333… → 0.14(0.01h 올림)
        assertThat(request.hours()).isEqualByComparingTo(new BigDecimal("0.14"));
        assertThat(request.comment()).isEqualTo("AI run #812(TASK) 자동 기록");
        assertThat(request.workedOn()).isEqualTo(LocalDate.of(2026, 9, 30));
        verify(almClient).getByKey("AGP-9", BEARER);
    }

    @Test
    void minutes_round_up_with_one_minute_floor() {
        Instant t = Instant.parse("2026-09-29T00:00:00Z");
        assertThat(RunWorklogService.minutes(t, t)).isEqualTo(1);
        assertThat(RunWorklogService.minutes(t, t.plusSeconds(5))).isEqualTo(1);
        assertThat(RunWorklogService.minutes(t, t.plusSeconds(60))).isEqualTo(1);
        assertThat(RunWorklogService.minutes(t, t.plusSeconds(61))).isEqualTo(2);
        assertThat(RunWorklogService.minutes(t, t.plusMillis(120_001))).isEqualTo(3);
        assertThat(RunWorklogService.minutes(t, t.minusSeconds(30))).isEqualTo(1);
        // 1분도 0h가 되지 않는다(ALM NUMERIC(8,2), hours > 0 제약).
        assertThat(RunWorklogService.hours(1)).isEqualByComparingTo(new BigDecimal("0.02"));
        assertThat(RunWorklogService.hours(90)).isEqualByComparingTo(new BigDecimal("1.50"));
    }

    @Test
    void self_blocked_run_without_ended_at_uses_now() {
        service.onRunEnded(run(RunType.REVIEW, RunStatus.BLOCKED, "AGP-9", NOW.minusSeconds(3600), null));

        WorklogRequest request = capturedWorklog();
        assertThat(request.hours()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(request.comment()).isEqualTo("AI run #812(REVIEW) 자동 기록");
    }

    @Test
    void failed_task_is_logged_too() {
        service.onRunEnded(run(RunType.TASK, RunStatus.FAILED, "AGP-9", NOW.minusSeconds(120), NOW));

        assertThat(capturedWorklog().hours()).isEqualByComparingTo(new BigDecimal("0.04"));
    }

    @Test
    void skipped_when_worker_already_called_log_work_in_this_run() {
        when(auditRepository.existsByToolAndRunIdAndStatus("log_work", 812L, AuditStatus.OK)).thenReturn(true);

        service.onRunEnded(run(RunType.TASK, RunStatus.DONE, "AGP-9", NOW.minusSeconds(600), NOW));

        verify(almClient, never()).addWorklog(anyLong(), any(), any());
    }

    @Test
    void meeting_family_is_logged_only_with_agenda_issue() {
        service.onRunEnded(run(RunType.RETRO, RunStatus.DONE, Run.projectIssueKey(1L), NOW.minusSeconds(600), NOW));
        verifyNoInteractions(almClient);

        service.onRunEnded(run(RunType.ESCALATION, RunStatus.DONE, "AGP-9", NOW.minusSeconds(600), NOW));
        assertThat(capturedWorklog().comment()).isEqualTo("AI run #812(ESCALATION) 자동 기록");
    }

    @Test
    void non_terminal_or_cancelled_or_never_started_runs_are_not_logged() {
        service.onRunEnded(run(RunType.TASK, RunStatus.WAITING_APPROVAL, "AGP-9", NOW.minusSeconds(60), null));
        service.onRunEnded(run(RunType.TASK, RunStatus.CANCELLED, "AGP-9", NOW.minusSeconds(60), NOW));
        service.onRunEnded(run(RunType.TASK, RunStatus.DONE, "AGP-9", null, NOW));

        verifyNoInteractions(almClient, auditRepository);
    }

    @Test
    void switch_off_disables_auto_worklog() {
        service(false).onRunEnded(run(RunType.TASK, RunStatus.DONE, "AGP-9", NOW.minusSeconds(60), NOW));

        verifyNoInteractions(almClient, auditRepository);
    }

    @Test
    void downstream_failure_is_swallowed() {
        when(almClient.addWorklog(anyLong(), any(), any())).thenThrow(new ServiceUnavailableException("alm down"));

        service.onRunEnded(run(RunType.TASK, RunStatus.DONE, "AGP-9", NOW.minusSeconds(60), NOW));

        verify(almClient).addWorklog(anyLong(), any(), any());
    }
}
