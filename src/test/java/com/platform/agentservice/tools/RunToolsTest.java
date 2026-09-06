package com.platform.agentservice.tools;

import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.pat.PatPrincipal;
import com.platform.agentservice.run.Gate;
import com.platform.agentservice.run.GateRepository;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunToolService;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code RunTools} — {@link RunToolService}는 실제 인스턴스를 쓰고(repo만 목업, T2 디스패치
 * 지시), {@link AlmClient}/{@link TokenService}는 목업으로 이슈 코멘트 호출을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RunToolsTest {

    private static final long OWNER_MEMBER_ID = 100L;
    private static final long PERSONA_ID = 5L;
    private static final long OTHER_PERSONA_ID = 6L;
    private static final long PERSONA_MEMBER_ID = 42L;
    private static final String BEARER = "Bearer persona-token";
    private static final String ISSUE_KEY = "AGP-9";

    @Mock RunRepository runRepository;
    @Mock GateRepository gateRepository;
    @Mock AlmClient almClient;
    @Mock TokenService tokenService;
    @Mock AuditService auditService;

    private RunTools runTools;

    @BeforeEach
    void setUp() {
        RunToolService runToolService = new RunToolService(runRepository, gateRepository);
        Audited audited = new Audited(auditService);
        runTools = new RunTools(runToolService, almClient, tokenService, audited);

        PatPrincipal principal = new PatPrincipal(OWNER_MEMBER_ID, PERSONA_ID, PERSONA_MEMBER_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));

        org.mockito.Mockito.lenient().when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Run runningRun(long id, long personaId) {
        Run run = Run.queued(RunType.TASK, ISSUE_KEY, 1L, personaId, RunTrigger.USER, "harness://local", null);
        ReflectionTestUtils.setField(run, "id", id);
        run.start("/work/AGP-9", 1L);
        return run;
    }

    private Run queuedRun(long id, long personaId) {
        Run run = Run.queued(RunType.TASK, ISSUE_KEY, 1L, personaId, RunTrigger.USER, "harness://local", null);
        ReflectionTestUtils.setField(run, "id", id);
        return run;
    }

    private IssueResponse issue(long id, String key) {
        return new IssueResponse(id, key, 9L, "제목", "설명", "task", "inprogress", "high", null, 1L,
                null, null, null, null, null, null, List.of(), List.of(), 0L, 1, null, null, null);
    }

    // ---- report_progress ----

    @Test
    void report_progress_happy_path_adds_comment_and_audits_ok() {
        Run run = runningRun(42L, PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(issue(1L, ISSUE_KEY));
        when(almClient.addComment(1L, "🤖 진행: 절반 완료", BEARER))
                .thenReturn(new CommentResponse(5L, 1L, PERSONA_MEMBER_ID, "🤖 진행: 절반 완료", null, null));

        String result = runTools.reportProgress(42L, "절반 완료");

        assertThat(result).isEqualTo("진행상황 기록 완료");
        verify(almClient).addComment(1L, "🤖 진행: 절반 완료", BEARER);
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("report_progress"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.OK));
    }

    @Test
    void report_progress_rejects_wrong_persona() {
        Run run = runningRun(42L, OTHER_PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));

        String result = runTools.reportProgress(42L, "절반 완료");

        assertThat(result).startsWith("오류:");
        verify(almClient, never()).addComment(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("report_progress"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.ERROR));
    }

    @Test
    void report_progress_rejects_wrong_state() {
        Run run = queuedRun(42L, PERSONA_ID); // QUEUED, not RUNNING
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));

        String result = runTools.reportProgress(42L, "절반 완료");

        assertThat(result).startsWith("오류:");
        verify(almClient, never()).addComment(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    // ---- request_gate ----

    @Test
    void request_gate_happy_path_creates_gate_parks_run_and_adds_comment() {
        Run run = runningRun(42L, PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        Gate savedGate = Gate.request(42L, com.platform.agentservice.run.GateKind.MERGE, "머지해도 될까요?");
        ReflectionTestUtils.setField(savedGate, "id", 7L);
        when(gateRepository.save(org.mockito.ArgumentMatchers.any(Gate.class))).thenReturn(savedGate);
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(issue(1L, ISSUE_KEY));
        when(almClient.addComment(eq(1L), org.mockito.ArgumentMatchers.anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(6L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        String result = runTools.requestGate(42L, "merge", "머지해도 될까요?");

        assertThat(result).contains("게이트 등록됨(gate id=7)").contains("이제 종료하라");
        assertThat(run.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(almClient).addComment(eq(1L), bodyCaptor.capture(), eq(BEARER));
        assertThat(bodyCaptor.getValue()).startsWith("⏸ 승인 대기(MERGE): 머지해도 될까요?");
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("request_gate"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.OK));
    }

    // ---- comment best-effort (fix round 1): state change is the source of truth, comment failure is non-fatal ----

    @Test
    void request_gate_comment_failure_still_reports_success_with_warning_and_keeps_state() {
        Run run = runningRun(42L, PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        Gate savedGate = Gate.request(42L, com.platform.agentservice.run.GateKind.MERGE, "머지해도 될까요?");
        ReflectionTestUtils.setField(savedGate, "id", 7L);
        when(gateRepository.save(org.mockito.ArgumentMatchers.any(Gate.class))).thenReturn(savedGate);
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenThrow(new RuntimeException("alm-backend 다운"));

        String result = runTools.requestGate(42L, "merge", "머지해도 될까요?");

        // 상태 전이는 이미 커밋됐으므로 도구는 성공을 보고한다 — 코멘트 실패는 경고로만 덧붙인다.
        assertThat(result).contains("게이트 등록됨(gate id=7)").contains("경고: 이슈 코멘트 기록 실패");
        assertThat(run.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("request_gate"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.OK));
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("request_gate.comment"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.ERROR));
    }

    @Test
    void request_gate_rejects_wrong_persona() {
        Run run = runningRun(42L, OTHER_PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));

        String result = runTools.requestGate(42L, "MERGE", "머지해도 될까요?");

        assertThat(result).startsWith("오류:");
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        verify(gateRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void request_gate_rejects_wrong_state() {
        Run run = queuedRun(42L, PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));

        String result = runTools.requestGate(42L, "MERGE", "머지해도 될까요?");

        assertThat(result).startsWith("오류:");
        verify(gateRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void request_gate_rejects_bad_kind_text() {
        // parseGateKind가 runRepository 조회보다 먼저 실패하므로 run 조회 자체가 일어나지 않는다.
        String result = runTools.requestGate(42L, "NOPE", "머지해도 될까요?");

        assertThat(result).startsWith("오류:").contains("NOPE");
        verify(gateRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(runRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    // ---- report_result ----

    @Test
    void report_result_done_completes_run_and_adds_comment() {
        Run run = runningRun(42L, PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(issue(1L, ISSUE_KEY));
        when(almClient.addComment(eq(1L), org.mockito.ArgumentMatchers.anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(8L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        String result = runTools.reportResult(42L, "DONE", "작업 완료했습니다");

        assertThat(result).contains("DONE");
        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(almClient).addComment(1L, "✅ 완료: 작업 완료했습니다", BEARER);
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("report_result"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.OK));
    }

    @Test
    void report_result_comment_failure_still_reports_success_with_warning_and_keeps_state() {
        Run run = runningRun(42L, PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenThrow(new RuntimeException("alm-backend 다운"));

        String result = runTools.reportResult(42L, "DONE", "작업 완료했습니다");

        // run 종결은 이미 커밋됐으므로 도구는 성공을 보고한다 — 코멘트 실패는 경고로만 덧붙인다.
        assertThat(result).contains("DONE").contains("경고: 이슈 코멘트 기록 실패");
        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("report_result"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.OK));
        verify(auditService).record(eq(PERSONA_ID), eq(OWNER_MEMBER_ID), eq("report_result.comment"), org.mockito.ArgumentMatchers.anyString(), eq(AuditStatus.ERROR));
    }

    @Test
    void report_result_failed_fails_run_with_summary_as_error() {
        Run run = runningRun(42L, PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(issue(1L, ISSUE_KEY));
        when(almClient.addComment(eq(1L), org.mockito.ArgumentMatchers.anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(8L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        String result = runTools.reportResult(42L, "failed", "타임아웃");

        assertThat(result).contains("FAILED");
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getError()).isEqualTo("타임아웃");
        verify(almClient).addComment(1L, "❌ 실패: 타임아웃", BEARER);
    }

    @Test
    void report_result_blocked_blocks_run() {
        Run run = runningRun(42L, PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(issue(1L, ISSUE_KEY));
        when(almClient.addComment(eq(1L), org.mockito.ArgumentMatchers.anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(8L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        String result = runTools.reportResult(42L, "BLOCKED", "사람 개입 필요");

        assertThat(result).contains("BLOCKED");
        assertThat(run.getStatus()).isEqualTo(RunStatus.BLOCKED);
        verify(almClient).addComment(1L, "⛔ 차단: 사람 개입 필요", BEARER);
    }

    @Test
    void report_result_rejects_bad_status_text() {
        // applyOutcome의 switch default가 runRepository 조회보다 먼저 실패하므로 run 조회가 일어나지 않는다.
        String result = runTools.reportResult(42L, "WAT", "무슨 상태?");

        assertThat(result).startsWith("오류:").contains("WAT");
        verify(runRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
        verify(almClient, never()).addComment(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void report_result_rejects_wrong_persona() {
        Run run = runningRun(42L, OTHER_PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));

        String result = runTools.reportResult(42L, "DONE", "완료");

        assertThat(result).startsWith("오류:");
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void report_result_rejects_wrong_state() {
        Run run = queuedRun(42L, PERSONA_ID);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));

        String result = runTools.reportResult(42L, "DONE", "완료");

        assertThat(result).startsWith("오류:");
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
    }
}
