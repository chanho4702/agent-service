package com.platform.agentservice.run;

import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.IssuePageResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link Dispatcher} — 순수 Mockito 단위테스트(P2a T4). 동시성 상한·라벨/상태 필터·
 * 중복 활성 run 스킵·QUEUED 드레인을 검증한다. {@code @Scheduled}가 실제로 도는지는
 * 검증하지 않는다(그건 {@code SchedulerEnablementConfig}의 조건부 등록 몫) — {@link
 * Dispatcher#tick}을 직접 호출해서 정책만 본다.
 */
@ExtendWith(MockitoExtension.class)
class DispatcherTest {

    private static final Set<RunStatus> CONCURRENCY_STATUSES = EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING);
    private static final long PERSONA_MEMBER_ID = 42L;
    private static final long PERSONA_ID = 5L;
    private static final String BEARER = "Bearer persona-token";

    @Mock BudgetGuard budgetGuard;
    @Mock RunRepository runRepository;
    @Mock RunService runService;
    @Mock AlmClient almClient;
    @Mock TokenService tokenService;
    @Mock PersonaRepository personaRepository;

    private SchedulerProperties enabledProperties(int maxGlobal, int maxPerProject) {
        return new SchedulerProperties(true, 60000L, maxGlobal, maxPerProject, "jiho", 3);
    }

    private Dispatcher dispatcher(SchedulerProperties properties) {
        return new Dispatcher(properties, budgetGuard, runRepository, runService, almClient, tokenService, personaRepository);
    }

    private Persona persona() {
        Persona p = Persona.of(PERSONA_MEMBER_ID, "jiho", PersonaRole.BACKEND, "지호", "🔧", null);
        ReflectionTestUtils.setField(p, "id", PERSONA_ID);
        return p;
    }

    private IssueResponse issue(long id, String key, long projectId) {
        return new IssueResponse(id, key, projectId, "제목", "본문", "task", "todo", "medium", null, 1L,
                null, null, null, null, null, null, List.of("auto"), List.of(), 0L, 1, null, null, null);
    }

    private Run queuedRun(long id) {
        Run r = Run.queued(RunType.TASK, "AGP-" + id, 1L, PERSONA_ID, RunTrigger.SCHEDULER, "harness://default", null);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    // ---- disabled ----

    @Test
    void disabled_scheduler_is_a_complete_noop() {
        SchedulerProperties disabled = new SchedulerProperties(false, 60000L, 2, 1, "jiho", 3);
        Dispatcher dispatcher = dispatcher(disabled);

        dispatcher.tick();

        verifyNoInteractions(runRepository, runService, almClient, tokenService, personaRepository, budgetGuard);
    }

    // ---- concurrency cap blocks pick ----

    @Test
    void global_concurrency_cap_blocks_new_issue_pickup() {
        when(runRepository.findByStatus(RunStatus.QUEUED)).thenReturn(List.of());
        when(runRepository.countByStatusIn(CONCURRENCY_STATUSES)).thenReturn(2L); // == maxConcurrentGlobal

        dispatcher(enabledProperties(2, 1)).tick();

        verify(personaRepository, never()).findBySlug(anyString());
        verify(almClient, never()).search(any(), any(), any(), any(), any(), any(), anyString());
        verify(runService, never()).createQueuedForIssue(any(), any(), any());
    }

    // ---- picks issue & creates+executes ----

    @Test
    void picks_new_auto_labeled_todo_issue_and_creates_and_executes_run() {
        when(runRepository.findByStatus(RunStatus.QUEUED)).thenReturn(List.of());
        when(runRepository.countByStatusIn(CONCURRENCY_STATUSES)).thenReturn(0L);
        when(personaRepository.findBySlug("jiho")).thenReturn(Optional.of(persona()));
        when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);

        IssueResponse issue = issue(1L, "AGP-1", 9L);
        when(almClient.search(eq(null), eq(null), eq(List.of("todo")), eq(null), eq(List.of("auto")), eq(0), eq(BEARER)))
                .thenReturn(new IssuePageResponse(List.of(issue), 0, 20, 1));
        when(runRepository.existsByIssueKeyAndStatusIn("AGP-1", RunService.ACTIVE_STATUSES)).thenReturn(false);
        when(budgetGuard.allow(9L)).thenReturn(true);
        when(runRepository.countByStatusInAndProjectId(CONCURRENCY_STATUSES, 9L)).thenReturn(0L);

        Run created = queuedRun(100L);
        when(runService.createQueuedForIssue(
                eq(new RunService.IssueRef("AGP-1", 9L, PERSONA_ID)), eq(RunTrigger.SCHEDULER), eq(null)))
                .thenReturn(created);

        dispatcher(enabledProperties(2, 1)).tick();

        verify(runService).createQueuedForIssue(
                eq(new RunService.IssueRef("AGP-1", 9L, PERSONA_ID)), eq(RunTrigger.SCHEDULER), eq(null));
        verify(runService).execute(100L);
    }

    // ---- skips issue with active run, moves to next ----

    @Test
    void skips_issue_that_already_has_an_active_run_and_picks_next_one() {
        when(runRepository.findByStatus(RunStatus.QUEUED)).thenReturn(List.of());
        when(runRepository.countByStatusIn(CONCURRENCY_STATUSES)).thenReturn(0L);
        when(personaRepository.findBySlug("jiho")).thenReturn(Optional.of(persona()));
        when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);

        IssueResponse busy = issue(1L, "AGP-1", 9L);
        IssueResponse free = issue(2L, "AGP-2", 9L);
        when(almClient.search(any(), any(), any(), any(), any(), any(), eq(BEARER)))
                .thenReturn(new IssuePageResponse(List.of(busy, free), 0, 20, 2));
        when(runRepository.existsByIssueKeyAndStatusIn("AGP-1", RunService.ACTIVE_STATUSES)).thenReturn(true);
        when(runRepository.existsByIssueKeyAndStatusIn("AGP-2", RunService.ACTIVE_STATUSES)).thenReturn(false);
        when(budgetGuard.allow(9L)).thenReturn(true);
        when(runRepository.countByStatusInAndProjectId(CONCURRENCY_STATUSES, 9L)).thenReturn(0L);

        Run created = queuedRun(101L);
        when(runService.createQueuedForIssue(any(), eq(RunTrigger.SCHEDULER), eq(null))).thenReturn(created);

        dispatcher(enabledProperties(2, 1)).tick();

        verify(runService).createQueuedForIssue(
                eq(new RunService.IssueRef("AGP-2", 9L, PERSONA_ID)), eq(RunTrigger.SCHEDULER), eq(null));
        verify(runService, never()).createQueuedForIssue(
                eq(new RunService.IssueRef("AGP-1", 9L, PERSONA_ID)), any(), any());
    }

    // ---- drains QUEUED continuation ----

    @Test
    void drains_existing_queued_runs_including_retry_continuations() {
        Run continuationRun = queuedRun(7L);
        when(runRepository.findByStatus(RunStatus.QUEUED)).thenReturn(List.of(continuationRun));
        // 픽업 단계는 전역 한도 초과로 막아 드레인 동작만 분리해서 본다.
        when(runRepository.countByStatusIn(CONCURRENCY_STATUSES)).thenReturn(2L);

        dispatcher(enabledProperties(2, 1)).tick();

        verify(runService).execute(7L);
        verify(runService, never()).createQueuedForIssue(any(), any(), any());
    }

    // ---- fix round 1 (I2): a rejected drain submission must not abort the rest of the tick ----

    @Test
    void drain_submission_rejection_on_one_run_does_not_abort_remaining_drain_or_subsequent_pick() {
        Run r1 = queuedRun(1L);
        Run r2 = queuedRun(2L);
        when(runRepository.findByStatus(RunStatus.QUEUED)).thenReturn(List.of(r1, r2));
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("실행기 포화"))
                .when(runService).execute(1L);

        when(runRepository.countByStatusIn(CONCURRENCY_STATUSES)).thenReturn(0L);
        when(personaRepository.findBySlug("jiho")).thenReturn(Optional.of(persona()));
        when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);
        IssueResponse issue = issue(9L, "AGP-9", 9L);
        when(almClient.search(any(), any(), any(), any(), any(), any(), eq(BEARER)))
                .thenReturn(new IssuePageResponse(List.of(issue), 0, 20, 1));
        when(runRepository.existsByIssueKeyAndStatusIn("AGP-9", RunService.ACTIVE_STATUSES)).thenReturn(false);
        when(budgetGuard.allow(9L)).thenReturn(true);
        when(runRepository.countByStatusInAndProjectId(CONCURRENCY_STATUSES, 9L)).thenReturn(0L);
        Run created = queuedRun(200L);
        when(runService.createQueuedForIssue(any(), eq(RunTrigger.SCHEDULER), eq(null))).thenReturn(created);

        dispatcher(enabledProperties(2, 1)).tick();

        verify(runService).execute(1L);   // 시도는 했다(거부당함)
        verify(runService).execute(2L);   // 드레인은 나머지 run으로 계속됐다
        verify(runService).createQueuedForIssue(any(), eq(RunTrigger.SCHEDULER), eq(null));
        verify(runService).execute(200L); // 드레인 실패와 무관하게 픽업도 정상 실행됐다
    }

    // ---- per-project concurrency cap also blocks pick ----

    @Test
    void per_project_concurrency_cap_blocks_pickup_of_that_project_issue() {
        when(runRepository.findByStatus(RunStatus.QUEUED)).thenReturn(List.of());
        when(runRepository.countByStatusIn(CONCURRENCY_STATUSES)).thenReturn(0L);
        when(personaRepository.findBySlug("jiho")).thenReturn(Optional.of(persona()));
        when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);

        IssueResponse issue = issue(1L, "AGP-1", 9L);
        when(almClient.search(any(), any(), any(), any(), any(), any(), eq(BEARER)))
                .thenReturn(new IssuePageResponse(List.of(issue), 0, 20, 1));
        when(runRepository.existsByIssueKeyAndStatusIn("AGP-1", RunService.ACTIVE_STATUSES)).thenReturn(false);
        when(budgetGuard.allow(9L)).thenReturn(true);
        when(runRepository.countByStatusInAndProjectId(CONCURRENCY_STATUSES, 9L)).thenReturn(1L); // == maxConcurrentPerProject

        dispatcher(enabledProperties(2, 1)).tick();

        verify(runService, never()).createQueuedForIssue(any(), any(), any());
    }

    // ---- budget guard blocks pick ----

    @Test
    void budget_guard_denial_blocks_pickup() {
        when(runRepository.findByStatus(RunStatus.QUEUED)).thenReturn(List.of());
        when(runRepository.countByStatusIn(CONCURRENCY_STATUSES)).thenReturn(0L);
        when(personaRepository.findBySlug("jiho")).thenReturn(Optional.of(persona()));
        when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);

        IssueResponse issue = issue(1L, "AGP-1", 9L);
        when(almClient.search(any(), any(), any(), any(), any(), any(), eq(BEARER)))
                .thenReturn(new IssuePageResponse(List.of(issue), 0, 20, 1));
        when(runRepository.existsByIssueKeyAndStatusIn("AGP-1", RunService.ACTIVE_STATUSES)).thenReturn(false);
        when(budgetGuard.allow(9L)).thenReturn(false);

        dispatcher(enabledProperties(2, 1)).tick();

        verify(runService, never()).createQueuedForIssue(any(), any(), any());
        verify(runRepository, never()).countByStatusInAndProjectId(any(), anyLong());
    }

    // ---- no default persona configured ----

    @Test
    void missing_default_persona_skips_pickup_without_error() {
        when(runRepository.findByStatus(RunStatus.QUEUED)).thenReturn(List.of());
        when(runRepository.countByStatusIn(CONCURRENCY_STATUSES)).thenReturn(0L);
        when(personaRepository.findBySlug("jiho")).thenReturn(Optional.empty());

        dispatcher(enabledProperties(2, 1)).tick();

        verify(almClient, never()).search(any(), any(), any(), any(), any(), any(), anyString());
        verify(runService, never()).createQueuedForIssue(any(), any(), any());
    }
}
