package com.platform.agentservice.run;

import com.platform.agentservice.budget.BudgetProperties;
import com.platform.agentservice.budget.LedgerScope;
import com.platform.agentservice.budget.UsageLedger;
import com.platform.agentservice.budget.UsageLedgerRepository;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.IssueClaimSupport;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.worker.CommitLinkParser;
import com.platform.agentservice.worker.WorkerJob;
import com.platform.agentservice.worker.WorkerLauncher;
import com.platform.agentservice.worker.WorkerProperties;
import com.platform.agentservice.worker.WorkerResult;
import com.platform.common.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RunService} — WorkerLauncher/AlmClient/PersonaRepository는 전부 목업이다(P2a T4).
 * {@code execute}는 {@code @Async}지만 이 테스트는 RunService를 직접 new해서 쓰므로(스프링
 * 프록시 없음) 호출 스레드에서 동기 실행된다 — 상태 전이를 즉시 단언할 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceTest {

    private static final long PERSONA_ID = 5L;
    private static final long PERSONA_MEMBER_ID = 42L;
    private static final String BEARER = "Bearer persona-token";
    private static final String ISSUE_KEY = "AGP-9";
    private static final long PROJECT_ID = 1L;

    @Mock RunRepository runRepository;
    @Mock AlmClient almClient;
    @Mock IssueClaimSupport issueClaimSupport;
    @Mock TokenService tokenService;
    @Mock PersonaRepository personaRepository;
    @Mock WorkerLauncher workerLauncher;
    @Mock UsageLedgerRepository usageLedgerRepository;
    @Mock CommitLinkParser commitLinkParser;
    @Mock BudgetGuard budgetGuard;

    private RunService runService;
    private SchedulerProperties schedulerProperties;
    private BudgetProperties budgetProperties;

    @BeforeEach
    void setUp() {
        schedulerProperties = new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3);
        WorkerProperties workerProperties = new WorkerProperties(
                "C:\\agent-work", "C:\\bundle", List.of(), "claude", 80, 40,
                "Read,Edit,Write", "http://localhost/api/agent/mcp", Map.of("AGP", "https://example.com/agp.git"));
        budgetProperties = new BudgetProperties(new BigDecimal("100"), new BigDecimal("5"));
        runService = new RunService(runRepository, almClient, issueClaimSupport, tokenService, personaRepository,
                workerLauncher, workerProperties, usageLedgerRepository, schedulerProperties, budgetProperties,
                commitLinkParser, budgetGuard);

        Persona persona = Persona.of(PERSONA_MEMBER_ID, "jiho", PersonaRole.BACKEND, "지호", "🔧", null);
        ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
        org.mockito.Mockito.lenient().when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.of(persona));
        org.mockito.Mockito.lenient().when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);
        // 기본은 워크스페이스 없음(=커밋 파서를 태우지 않음) — 커밋 링크 테스트만 명시적으로 스텁한다.
        org.mockito.Mockito.lenient().when(commitLinkParser.parse(any())).thenReturn(List.of());
        // 기본은 킬 스위치/예산 캡 통과 — I3 전용 테스트만 false로 재스텁한다.
        org.mockito.Mockito.lenient().when(budgetGuard.allow(anyLong())).thenReturn(true);
    }

    private Run queuedRun(long id) {
        Run run = Run.queued(RunType.TASK, ISSUE_KEY, PROJECT_ID, PERSONA_ID, RunTrigger.SCHEDULER, "harness://default", null);
        ReflectionTestUtils.setField(run, "id", id);
        return run;
    }

    private IssueResponse issue(long id, String key, String status, int version) {
        return new IssueResponse(id, key, PROJECT_ID, "이슈 제목", "이슈 본문", "task", status, "high", PERSONA_MEMBER_ID,
                1L, null, null, null, null, null, null, List.of(), List.of(), 0L, version, null, null, null);
    }

    /** save()를 호출자에게 넘겨받은 인자 그대로 반환하도록 스텁 — 상태 전이 후 값을 그대로 관찰할 수 있게 한다. */
    private void stubSaveReturnsArgument() {
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- createQueuedForIssue ----

    @Test
    void createQueuedForIssue_rejects_when_active_run_exists() {
        when(runRepository.existsByIssueKeyAndStatusIn(ISSUE_KEY, RunService.ACTIVE_STATUSES)).thenReturn(true);

        assertThatThrownBy(() -> runService.createQueuedForIssue(
                new RunService.IssueRef(ISSUE_KEY, PROJECT_ID, PERSONA_ID), RunTrigger.SCHEDULER, null))
                .isInstanceOf(ConflictException.class);

        verify(runRepository, never()).save(any());
    }

    // ---- F2b (fix round, task-7): BLOCKED must count as an active/"in progress" status ----

    @Test
    void active_statuses_includes_blocked_so_a_stuck_chain_cannot_be_repicked() {
        // Dispatcher.pickNewIssue와 createQueuedForIssue 둘 다 이 상수 하나로 "이미 진행
        // 중" 여부를 판정한다 — BLOCKED가 빠지면 사람이 확인하기 전에 같은 이슈가
        // attempt=1부터 새로 픽업된다(task-7 E2E 실측 버그, 5회 반복).
        assertThat(RunService.ACTIVE_STATUSES).contains(
                RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.WAITING_APPROVAL, RunStatus.BLOCKED);
        assertThat(RunService.ACTIVE_STATUSES).doesNotContain(RunStatus.DONE, RunStatus.FAILED, RunStatus.CANCELLED);
    }

    @Test
    void createQueuedForIssue_saves_queued_run_when_no_active_run() {
        when(runRepository.existsByIssueKeyAndStatusIn(ISSUE_KEY, RunService.ACTIVE_STATUSES)).thenReturn(false);
        stubSaveReturnsArgument();

        Run run = runService.createQueuedForIssue(
                new RunService.IssueRef(ISSUE_KEY, PROJECT_ID, PERSONA_ID), RunTrigger.SCHEDULER, "claude-opus-5");

        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(run.getIssueKey()).isEqualTo(ISSUE_KEY);
        assertThat(run.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(run.getPersonaId()).isEqualTo(PERSONA_ID);
        assertThat(run.getModel()).isEqualTo("claude-opus-5");
    }

    // ---- execute: happy path ----

    @Test
    void execute_happy_path_claims_issue_runs_worker_completes_and_records_ledger() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of(
                new CommentResponse(1L, 1L, PERSONA_MEMBER_ID, "댓글1", null, null)));

        WorkerResult result = new WorkerResult(0, false, "작업 완료했습니다", "sess-1",
                new BigDecimal("1.2345"), 100L, 200L, "claude-opus-5", "raw", "C:\\agent-work\\run-42");
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);

        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(run.getSessionId()).isEqualTo("sess-1");

        ArgumentCaptor<WorkerJob> jobCaptor = ArgumentCaptor.forClass(WorkerJob.class);
        verify(workerLauncher).launch(any(Run.class), jobCaptor.capture());
        assertThat(jobCaptor.getValue().repoUrl()).isEqualTo("https://example.com/agp.git");
        assertThat(jobCaptor.getValue().recentComments()).containsExactly("댓글1");

        verify(usageLedgerRepository).save(argThatLedger(LedgerScope.PROJECT, String.valueOf(PROJECT_ID)));
        verify(usageLedgerRepository).save(argThatLedger(LedgerScope.PLATFORM, "platform"));
        verify(almClient).addComment(eq(1L), org.mockito.ArgumentMatchers.contains("작업 완료했습니다"), eq(BEARER));
    }

    // ---- execute: fix round 1 (I1) — RUNNING is committed before buildJob(), so a concurrent redrain is a no-op ----

    @Test
    void execute_commits_running_before_buildJob_so_concurrent_redrain_is_a_noop() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        // buildJob()의 첫 네트워크 호출(claim) 시점에 run이 이미 RUNNING이어야 한다 — 그 안에서
        // 같은 runId로 재드레인(execute 재호출)을 재현해도 QUEUED가 아니라서 즉시 no-op이어야 한다.
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenAnswer(inv -> {
            assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
            runService.execute(42L); // 재드레인 재현 — 이 재귀 호출은 QUEUED가 아니므로 즉시 반환해야 한다
            return claimed;
        });
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult result = new WorkerResult(0, false, "완료", "sess-9", null, 0L, 0L, null, "raw", null);
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        // 재드레인이 launch를 다시 호출하지 않았다 — 최초 실행 한 번만 워커를 띄웠다.
        verify(workerLauncher, times(1)).launch(any(Run.class), any(WorkerJob.class));
    }

    // ---- execute: per-run budget cap exceeded -> warning comment, run still completes ----

    @Test
    void execute_cost_over_per_run_cap_adds_warning_comment_but_still_completes() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        // per-run cap in setUp() is 5 — 12.50 exceeds it.
        WorkerResult result = new WorkerResult(0, false, "완료", "sess-3",
                new BigDecimal("12.50"), 500L, 900L, "claude-opus-5", "raw", null);
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(almClient).addComment(eq(1L),
                eq("⚠️ run 비용 상한 초과: $12.50 > $5"), eq(BEARER));
        verify(almClient).addComment(eq(1L),
                org.mockito.ArgumentMatchers.contains("완료"), eq(BEARER));
    }

    @Test
    void execute_cost_within_per_run_cap_adds_no_warning_comment() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult result = new WorkerResult(0, false, "완료", "sess-4",
                new BigDecimal("2.00"), 100L, 200L, "claude-opus-5", "raw", null);
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(almClient, times(1)).addComment(eq(1L), anyString(), eq(BEARER));
        verify(almClient, never()).addComment(eq(1L),
                org.mockito.ArgumentMatchers.contains("상한 초과"), eq(BEARER));
    }

    private UsageLedger argThatLedger(LedgerScope scope, String scopeId) {
        return org.mockito.ArgumentMatchers.argThat(u -> u != null && u.getScope() == scope && u.getScopeId().equals(scopeId));
    }

    // ---- execute: worker already self-terminated via MCP before launch() returns ----

    @Test
    void execute_when_worker_already_reported_terminal_state_does_not_double_transition_but_still_records_ledger() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());

        WorkerResult result = new WorkerResult(0, false, "완료", "sess-2",
                new BigDecimal("0.5"), 10L, 20L, "claude-opus-5", "raw", null);
        // launch()가 반환하기 전에 워커가 MCP report_result로 스스로 DONE 처리했다고 가정 —
        // launch 스텁 안에서 run 상태를 미리 바꿔 재조회 시 DONE이 보이게 한다.
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenAnswer(inv -> {
            run.complete();
            return result;
        });

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(almClient, never()).addComment(anyLong(), anyString(), anyString());
        verify(usageLedgerRepository, times(2)).save(any(UsageLedger.class));
    }

    /**
     * 최종 리뷰 I1: 워커가 launch() 반환 전에 MCP report_result(status=FAILED)로 스스로
     * 종결했으면(자가-DONE과 같은 패턴, 여기선 자가-FAILED) 재조회 시 상태가 FAILED다.
     * 예전에는 "RUNNING 아니면 물러난다" 가드에 걸려 여기서 그냥 반환해 재시도/BLOCKED
     * 승격이 전혀 안 됐다 — 그 run은 FAILED에 영원히 멈췄다. 이제는 재시도 continuation이
     * 생겨야 한다(attempt=1 < 한도 3).
     */
    @Test
    void execute_self_reported_failed_status_is_no_longer_a_dead_end_and_creates_continuation() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult result = new WorkerResult(0, false, null, null, null, 0L, 0L, null, "raw", null);
        // launch()가 반환하기 전에 워커가 MCP report_result(FAILED)로 스스로 종결했다고 가정.
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenAnswer(inv -> {
            run.fail("워커가 스스로 실패로 보고함");
            return result;
        });

        runService.execute(42L);

        ArgumentCaptor<Run> savedCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository, org.mockito.Mockito.atLeastOnce()).save(savedCaptor.capture());
        boolean continuationSaved = savedCaptor.getAllValues().stream()
                .anyMatch(r -> r != run && r.getStatus() == RunStatus.QUEUED && r.getAttempt() == 2);
        assertThat(continuationSaved).isTrue();
        verify(almClient).addComment(eq(1L), eq("🔁 재시도 2/3"), eq(BEARER));
    }

    // ---- execute: 최종 리뷰 I2 — applyOutcome 사전 단계 방어(원장 기록 실패가 run을 좌초시키면 안 됨) ----

    @Test
    void execute_ledger_write_failure_does_not_strand_run_in_running() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult result = new WorkerResult(0, false, "완료", "sess-x",
                new BigDecimal("1.00"), 10L, 20L, "claude-opus-5", "raw", null);
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);
        when(usageLedgerRepository.save(any(UsageLedger.class))).thenThrow(new RuntimeException("DB 순간 장애"));

        runService.execute(42L);

        // I2: 원장 기록이 던져도 run이 RUNNING에 멈추지 않고 정상 종결돼야 한다.
        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(almClient).addComment(eq(1L), org.mockito.ArgumentMatchers.contains("완료"), eq(BEARER));
    }

    // ---- execute: 최종 리뷰 I3 — 킬 스위치/예산 캡을 execute() 진입점에서 전면 확인 ----

    @Test
    void execute_denied_by_kill_switch_or_budget_cap_leaves_run_queued_without_launching() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(budgetGuard.allow(PROJECT_ID)).thenReturn(false);

        runService.execute(42L);

        // QUEUED 그대로 — 킬 스위치/캡이 풀리면 다음 드레인 틱이 재시도한다(재시도 카운트 소모 없음).
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
        verify(workerLauncher, never()).launch(any(), any());
        verify(issueClaimSupport, never()).claim(anyString(), anyLong(), anyString(), anyString());
        verify(runRepository, never()).save(any());
    }

    // ---- execute: commit link parser integration (P2a T6b) ----

    @Test
    void execute_links_parsed_commits_to_their_resolved_issues_as_commit_web_links() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult result = new WorkerResult(0, false, "완료", "sess-5", null, 0L, 0L, null, "raw",
                "C:\\agent-work\\run-42");
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);

        when(commitLinkParser.parse(java.nio.file.Path.of("C:\\agent-work\\run-42"))).thenReturn(List.of(
                new CommitLinkParser.CommitLink("AGP-100", "aaa111", "AGP-100: fix bug", "https://github.com/o/r/commit/aaa111"),
                new CommitLinkParser.CommitLink("AGP-200", "bbb222", "AGP-200: add feature", "https://github.com/o/r/commit/bbb222")));

        IssueResponse target100 = issue(101L, "AGP-100", "todo", 1);
        IssueResponse target200 = issue(102L, "AGP-200", "todo", 1);
        when(almClient.getByKey("AGP-100", BEARER)).thenReturn(target100);
        when(almClient.getByKey("AGP-200", BEARER)).thenReturn(target200);

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(almClient).addWebLink(101L, "https://github.com/o/r/commit/aaa111", "AGP-100: fix bug", "COMMIT", BEARER);
        verify(almClient).addWebLink(102L, "https://github.com/o/r/commit/bbb222", "AGP-200: add feature", "COMMIT", BEARER);
    }

    @Test
    void execute_skips_commit_link_when_issue_key_does_not_resolve_but_still_links_the_others_and_completes() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult result = new WorkerResult(0, false, "완료", "sess-6", null, 0L, 0L, null, "raw",
                "C:\\agent-work\\run-42");
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);

        when(commitLinkParser.parse(java.nio.file.Path.of("C:\\agent-work\\run-42"))).thenReturn(List.of(
                new CommitLinkParser.CommitLink("NOPE-1", "ccc333", "NOPE-1: unknown project", "https://github.com/o/r/commit/ccc333"),
                new CommitLinkParser.CommitLink("AGP-100", "ddd444", "AGP-100: known", "https://github.com/o/r/commit/ddd444")));

        // alm-backend가 404를 던지면 DownstreamErrors가 ConflictException으로 감싼다(실측, 4xx 기본 매핑).
        when(almClient.getByKey("NOPE-1", BEARER)).thenThrow(new ConflictException("이슈를 찾을 수 없습니다: NOPE-1"));
        IssueResponse target100 = issue(101L, "AGP-100", "todo", 1);
        when(almClient.getByKey("AGP-100", BEARER)).thenReturn(target100);

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(almClient, never()).addWebLink(anyLong(), eq("https://github.com/o/r/commit/ccc333"), anyString(), anyString(), anyString());
        verify(almClient).addWebLink(101L, "https://github.com/o/r/commit/ddd444", "AGP-100: known", "COMMIT", BEARER);
    }

    // ---- execute: commit link fix round 1 (I1 — outer catch, I2 — null-url skip) ----

    @Test
    void execute_bearer_lookup_failure_during_commit_linking_does_not_fail_the_run() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult result = new WorkerResult(0, false, "완료", "sess-9b", null, 0L, 0L, null, "raw",
                "C:\\agent-work\\run-42");
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);

        when(commitLinkParser.parse(java.nio.file.Path.of("C:\\agent-work\\run-42"))).thenReturn(List.of(
                new CommitLinkParser.CommitLink("AGP-100", "eee555", "AGP-100: x", "https://github.com/o/r/commit/eee555")));

        // 1번째 호출(buildJob)은 정상, 2번째 호출(linkCommits 안)만 장애를 재현하고, 3번째
        // 호출(종결 후 commentBestEffort)은 다시 정상으로 돌아온다 — resolution 단계
        // (findById/findById/bearerFor)에서 던진 예외가 applyOutcome 밖으로 새지 않고 run이
        // 정상 종결돼야 한다는 것만 검증한다(fix round 1, I1).
        when(tokenService.bearerFor(PERSONA_MEMBER_ID))
                .thenReturn(BEARER)
                .thenThrow(new RuntimeException("token 서비스 장애"))
                .thenReturn(BEARER);

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(almClient, never()).addWebLink(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void execute_skips_commit_link_with_unresolved_url_but_still_links_others() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult result = new WorkerResult(0, false, "완료", "sess-9c", null, 0L, 0L, null, "raw",
                "C:\\agent-work\\run-42");
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);

        // 첫 커밋은 원격 URL을 정규화하지 못해 url=null(예: 원격 없음) — HTTP 호출 없이 건너뛰어야 한다(I2).
        when(commitLinkParser.parse(java.nio.file.Path.of("C:\\agent-work\\run-42"))).thenReturn(List.of(
                new CommitLinkParser.CommitLink("AGP-300", "fff666", "AGP-300: no remote", null),
                new CommitLinkParser.CommitLink("AGP-100", "ggg777", "AGP-100: has remote", "https://github.com/o/r/commit/ggg777")));

        IssueResponse target100 = issue(101L, "AGP-100", "todo", 1);
        when(almClient.getByKey("AGP-100", BEARER)).thenReturn(target100);

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(almClient, never()).getByKey(eq("AGP-300"), anyString());
        verify(almClient, never()).addWebLink(anyLong(), anyString(), eq("AGP-300: no remote"), anyString(), anyString());
        verify(almClient).addWebLink(101L, "https://github.com/o/r/commit/ggg777", "AGP-100: has remote", "COMMIT", BEARER);
    }

    @Test
    void execute_commit_parser_throwing_does_not_fail_the_run() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult result = new WorkerResult(0, false, "완료", "sess-7", null, 0L, 0L, null, "raw",
                "C:\\agent-work\\run-42");
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);
        when(commitLinkParser.parse(any())).thenThrow(new RuntimeException("git binary missing"));

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
    }

    @Test
    void execute_never_parses_commits_when_workspace_path_is_absent() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult result = new WorkerResult(0, false, "완료", "sess-8", null, 0L, 0L, null, "raw", null);
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        verify(commitLinkParser, never()).parse(any());
    }

    // ---- execute: timeout ----

    @Test
    void execute_timeout_fails_run_and_creates_continuation_with_retry_comment() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        WorkerResult timedOut = WorkerResult.failure(-1, true, "partial output");
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(timedOut);

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getError()).isEqualTo("시간 초과");

        ArgumentCaptor<Run> savedCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository, org.mockito.Mockito.atLeastOnce()).save(savedCaptor.capture());
        boolean continuationSaved = savedCaptor.getAllValues().stream()
                .anyMatch(r -> r != run && r.getStatus() == RunStatus.QUEUED && r.getAttempt() == 2);
        assertThat(continuationSaved).isTrue();

        verify(almClient).addComment(eq(1L), eq("🔁 재시도 2/3"), eq(BEARER));
    }

    // ---- execute: attempt hits max -> BLOCKED ----

    @Test
    void execute_failure_at_max_attempts_blocks_run_with_escalation_comment() {
        Run third = queuedRun(3L);
        ReflectionTestUtils.setField(third, "attempt", 3); // retryMaxAttempts와 동일 — 더 이상 재시도하지 않는다

        when(runRepository.findById(3L)).thenReturn(Optional.of(third));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class)))
                .thenReturn(WorkerResult.failure(1, false, "boom"));

        runService.execute(3L);

        assertThat(third.getStatus()).isEqualTo(RunStatus.BLOCKED);
        verify(almClient).addComment(eq(1L), eq("⛔ 3회 실패 — 사람 확인 필요"), eq(BEARER));
    }

    // ---- execute: missing repo mapping ----

    @Test
    void execute_missing_repo_mapping_fails_without_calling_launcher_or_claiming_issue() {
        Run run = Run.queued(RunType.TASK, "NOPROJ-1", PROJECT_ID, PERSONA_ID, RunTrigger.SCHEDULER, "harness://default", null);
        ReflectionTestUtils.setField(run, "id", 55L);
        when(runRepository.findById(55L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse issue = new IssueResponse(2L, "NOPROJ-1", PROJECT_ID, "제목", "본문", "task", "todo", "high",
                PERSONA_MEMBER_ID, 1L, null, null, null, null, null, null, List.of(), List.of(), 0L, 1, null, null, null);
        when(almClient.getByKey("NOPROJ-1", BEARER)).thenReturn(issue);
        when(almClient.addComment(eq(2L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 2L, PERSONA_MEMBER_ID, "body", null, null));

        runService.execute(55L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getError()).contains("리포 매핑 없음");
        verify(issueClaimSupport, never()).claim(anyString(), anyLong(), anyString(), anyString());
        verify(workerLauncher, never()).launch(any(), any());
    }

    /**
     * F3(fix round, task-7 실측): env var({@code PLATFORM_AGENT_WORKER_REPOS_AGP=...})로
     * repos 맵을 주입하면 Spring relaxed binding이 키를 소문자로 접는다({@code "agp"}) —
     * ALM 프로젝트 키는 항상 대문자({@code "AGP"})이므로 대소문자 무관 조회가 아니면 이
     * 조합은 매번 "리포 매핑 없음"으로 죽는다(실측: run 1~12, 12회 반복). 이 테스트는
     * 소문자 키 맵으로 별도 RunService를 만들어 그래도 정상 해결되는지 확인한다.
     */
    @Test
    void execute_resolves_repo_mapping_even_when_map_key_is_lowercased_by_env_binding() {
        WorkerProperties lowercasedRepos = new WorkerProperties(
                "C:\\agent-work", "C:\\bundle", List.of(), "claude", 80, 40,
                "Read,Edit,Write", "http://localhost/api/agent/mcp", Map.of("agp", "https://example.com/agp.git"));
        RunService serviceWithLowercasedRepos = new RunService(runRepository, almClient, issueClaimSupport,
                tokenService, personaRepository, workerLauncher, lowercasedRepos, usageLedgerRepository,
                schedulerProperties, new com.platform.agentservice.budget.BudgetProperties(
                        new BigDecimal("100"), new BigDecimal("5")), commitLinkParser, budgetGuard);

        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));
        WorkerResult result = new WorkerResult(0, false, "완료", "sess-1", null, 0L, 0L, null, "raw", null);
        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class))).thenReturn(result);

        serviceWithLowercasedRepos.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        ArgumentCaptor<WorkerJob> jobCaptor = ArgumentCaptor.forClass(WorkerJob.class);
        verify(workerLauncher).launch(any(Run.class), jobCaptor.capture());
        assertThat(jobCaptor.getValue().repoUrl()).isEqualTo("https://example.com/agp.git");
    }

    // ---- execute: launcher throws (infra failure) ----

    @Test
    void execute_launcher_infra_throw_fails_run() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();

        IssueResponse claimed = issue(1L, ISSUE_KEY, "inprogress", 2);
        when(issueClaimSupport.claim(ISSUE_KEY, PERSONA_MEMBER_ID, "inprogress", BEARER)).thenReturn(claimed);
        when(almClient.comments(1L, BEARER)).thenReturn(List.of());
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        when(workerLauncher.launch(any(Run.class), any(WorkerJob.class)))
                .thenThrow(new RuntimeException("워크스페이스 생성 실패"));

        runService.execute(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getError()).contains("실행 인프라 오류").contains("워크스페이스 생성 실패");
    }

    // ---- execute: run not found / not QUEUED ----

    @Test
    void execute_missing_run_is_a_noop() {
        when(runRepository.findById(999L)).thenReturn(Optional.empty());

        runService.execute(999L);

        verify(workerLauncher, never()).launch(any(), any());
        verify(runRepository, never()).save(any());
    }

    @Test
    void execute_run_not_queued_is_a_noop() {
        Run run = queuedRun(42L);
        run.start("pending", null); // 이미 RUNNING
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));

        runService.execute(42L);

        verify(workerLauncher, never()).launch(any(), any());
        verify(issueClaimSupport, never()).claim(anyString(), anyLong(), anyString(), anyString());
    }

    // ---- cancel ----

    @Test
    void cancel_transitions_queued_run_to_cancelled() {
        Run run = queuedRun(42L);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        stubSaveReturnsArgument();
        IssueResponse claimed = issue(1L, ISSUE_KEY, "todo", 1);
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(claimed);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));

        runService.cancel(42L);

        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        verify(almClient).addComment(eq(1L), org.mockito.ArgumentMatchers.contains("취소"), eq(BEARER));
    }

    // ---- list ----

    @Test
    void list_maps_runs_to_summary_dto() {
        Run run = queuedRun(42L);
        when(runRepository.findByStatus(RunStatus.QUEUED)).thenReturn(List.of(run));

        var summaries = runService.list(RunStatus.QUEUED);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).id()).isEqualTo(42L);
        assertThat(summaries.get(0).issueKey()).isEqualTo(ISSUE_KEY);
        assertThat(summaries.get(0).status()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void list_without_status_returns_all_runs() {
        Run run = queuedRun(42L);
        when(runRepository.findAll()).thenReturn(List.of(run));

        var summaries = runService.list(null);

        assertThat(summaries).hasSize(1);
        verify(runRepository, never()).findByStatus(any());
    }
}
