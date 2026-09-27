package com.platform.agentservice.execution;

import com.platform.agentservice.alert.AlertService;
import com.platform.agentservice.budget.BudgetProperties;
import com.platform.agentservice.budget.UsageLedgerRepository;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.IssueClaimSupport;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.run.BudgetGuard;
import com.platform.agentservice.run.Dispatcher;
import com.platform.agentservice.run.MeetingService;
import com.platform.agentservice.run.ReviewService;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunService;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTokenService;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import com.platform.agentservice.run.SchedulerProperties;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.worker.CommitLinkParser;
import com.platform.agentservice.worker.WorkerLauncher;
import com.platform.agentservice.worker.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실행 위치 라우팅(P4a D-P4-1·D-P4-5) — 인프로세스 실행(스케줄러 드레인·{@code RunService.execute})은 SERVER이면서 러너에 고정되지
 * 않은 run만, 그리고 {@code in-process=true}일 때만 워커를 띄운다. 해석 순서(요청 &gt; 프로젝트 &gt; 전역)와 계보 run의 위치 승계도 여기서 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionSiteRoutingTest {

    @Mock RunRepository runRepository;
    @Mock AlmClient almClient;
    @Mock IssueClaimSupport issueClaimSupport;
    @Mock TokenService tokenService;
    @Mock PersonaRepository personaRepository;
    @Mock WorkerLauncher workerLauncher;
    @Mock UsageLedgerRepository usageLedgerRepository;
    @Mock CommitLinkParser commitLinkParser;
    @Mock BudgetGuard budgetGuard;
    @Mock ReviewService reviewService;
    @Mock MeetingService meetingService;
    @Mock AlertService alertService;
    @Mock RunTokenService runTokenService;
    @Mock ProjectExecutionSiteRepository projectSites;

    private RunService runService(boolean inProcess) {
        WorkerProperties workerProperties = new WorkerProperties("C:\\agent-work", "C:\\bundle", List.of(), "claude", 80, 40,
                "Read", "http://localhost/api/agent/mcp", Map.of("AGP", "https://example.com/agp.git"), List.of(), false);
        ExecutionProperties props = new ExecutionProperties("SERVER", inProcess);
        return new RunService(runRepository, almClient, issueClaimSupport, tokenService, personaRepository, workerLauncher,
                workerProperties, usageLedgerRepository, new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3),
                new BudgetProperties(new BigDecimal("100"), new BigDecimal("5")), commitLinkParser, budgetGuard,
                reviewService, meetingService, alertService, new ExecutionSiteResolver(projectSites, props), props,
                runTokenService);
    }

    private Run queued(long id, ExecutionSite site, Long runnerId) {
        Run run = Run.queued(RunType.TASK, "AGP-" + id, 1L, 5L, RunTrigger.SCHEDULER, "harness://default", null);
        run.assignExecutionSite(site);
        ReflectionTestUtils.setField(run, "id", id);
        ReflectionTestUtils.setField(run, "runnerId", runnerId);
        when(runRepository.findById(id)).thenReturn(Optional.of(run));
        return run;
    }

    @Test
    void execute_leaves_local_run_queued_for_a_runner() {
        when(budgetGuard.allow(anyLong())).thenReturn(true);
        Run local = queued(1L, ExecutionSite.LOCAL, null);

        runService(true).execute(1L);

        assertThat(local.getStatus()).isEqualTo(RunStatus.QUEUED);
        verify(workerLauncher, never()).launch(any(), any());
        verify(runRepository, never()).save(any());
    }

    @Test
    void execute_leaves_server_run_queued_when_in_process_execution_is_off() {
        when(budgetGuard.allow(anyLong())).thenReturn(true);
        Run server = queued(2L, ExecutionSite.SERVER, null);

        runService(false).execute(2L);

        assertThat(server.getStatus()).isEqualTo(RunStatus.QUEUED);
        verify(workerLauncher, never()).launch(any(), any());
    }

    @Test
    void execute_leaves_server_run_pinned_to_a_platform_runner_alone() {
        when(budgetGuard.allow(anyLong())).thenReturn(true);
        Run pinned = queued(3L, ExecutionSite.SERVER, 12L);

        runService(true).execute(3L);

        assertThat(pinned.getStatus()).isEqualTo(RunStatus.QUEUED);
        verify(workerLauncher, never()).launch(any(), any());
    }

    @Test
    void runs_in_process_only_for_unpinned_server_runs_with_in_process_on() {
        RunService on = runService(true);
        RunService off = runService(false);
        assertThat(on.runsInProcess(queued(4L, ExecutionSite.SERVER, null))).isTrue();
        assertThat(on.runsInProcess(queued(5L, ExecutionSite.LOCAL, null))).isFalse();
        assertThat(on.runsInProcess(queued(6L, ExecutionSite.SERVER, 9L))).isFalse();
        assertThat(off.runsInProcess(queued(7L, ExecutionSite.SERVER, null))).isFalse();
    }

    @Test
    void scheduler_drain_skips_local_runs_and_does_not_count_them_against_the_global_limit() {
        RunService real = runService(true);
        RunService spy = org.mockito.Mockito.spy(real);
        org.mockito.Mockito.doNothing().when(spy).execute(anyLong());
        Run local = queued(10L, ExecutionSite.LOCAL, null);
        Run server = queued(11L, ExecutionSite.SERVER, null);
        when(runRepository.findByStatus(RunStatus.QUEUED)).thenReturn(List.of(local, server));
        // 전역 한도 판정은 SERVER만 센다 — LOCAL 대기가 쌓여도 서버 픽업이 굶지 않는다.
        when(runRepository.countByStatusInAndExecutionSite(any(), any())).thenReturn(2L);

        new Dispatcher(new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3), budgetGuard, runRepository, spy, almClient,
                tokenService, personaRepository).tick();

        verify(spy).execute(11L);
        verify(spy, never()).execute(10L);
        verify(runRepository).countByStatusInAndExecutionSite(any(), org.mockito.ArgumentMatchers.eq(ExecutionSite.SERVER));
        verify(runRepository, never()).countByStatusIn(any());
    }

    // ---- 해석 순서 ----

    @Test
    void resolution_order_is_request_then_project_then_global_default() {
        ExecutionSiteResolver resolver = new ExecutionSiteResolver(projectSites, new ExecutionProperties("LOCAL", true));
        when(projectSites.findById(7L)).thenReturn(Optional.of(ProjectExecutionSite.of(7L, ExecutionSite.SERVER, 1L, Instant.now())));
        when(projectSites.findById(8L)).thenReturn(Optional.empty());

        assertThat(resolver.resolve("local", 7L)).as("요청 지정이 이긴다(대소문자 무관)").isEqualTo(ExecutionSite.LOCAL);
        assertThat(resolver.resolve(null, 7L)).as("프로젝트 설정").isEqualTo(ExecutionSite.SERVER);
        assertThat(resolver.resolve(" ", 8L)).as("전역 기본").isEqualTo(ExecutionSite.LOCAL);
        assertThatThrownBy(() -> resolver.resolve("MOON", 8L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalid_global_default_falls_back_to_server() {
        assertThat(new ExecutionProperties("CLOUD", true).defaultSiteOrServer()).isEqualTo(ExecutionSite.SERVER);
        assertThat(new ExecutionProperties(null, null).inProcessEnabled()).as("in-process 기본 true").isTrue();
    }

    // ---- 계보 run 승계 ----

    @Test
    void review_fix_and_lineage_continuations_inherit_site_and_pin_to_the_parent_runner() {
        Run task = Run.queued(RunType.TASK, "AGP-1", 1L, 5L, RunTrigger.USER, "harness://default", null);
        task.assignExecutionSite(ExecutionSite.LOCAL);
        ReflectionTestUtils.setField(task, "id", 100L);
        ReflectionTestUtils.setField(task, "runnerId", 11L);
        ReflectionTestUtils.setField(task, "workspacePath", "C:\\agent-work\\run-100");
        ReflectionTestUtils.setField(task, "status", RunStatus.DONE);

        Run review = Run.queuedReview(task, 6L, null);
        assertThat(review.getExecutionSite()).isEqualTo(ExecutionSite.LOCAL);
        assertThat(review.getRunnerId()).as("승계 워크스페이스는 그 러너 디스크에만 있다").isEqualTo(11L);

        Run fix = Run.fixContinuation(task, 200L, 1);
        assertThat(fix.getExecutionSite()).isEqualTo(ExecutionSite.LOCAL);
        assertThat(fix.getRunnerId()).isEqualTo(11L);

        ReflectionTestUtils.setField(review, "status", RunStatus.BLOCKED);
        Run reviewResume = Run.continuation(review);
        assertThat(reviewResume.getRunnerId()).isEqualTo(11L);

        // 일반 TASK 재개는 새로 clone하므로 러너를 고정하지 않는다(위치만 승계).
        ReflectionTestUtils.setField(task, "status", RunStatus.BLOCKED);
        Run taskResume = Run.continuation(task);
        assertThat(taskResume.getExecutionSite()).isEqualTo(ExecutionSite.LOCAL);
        assertThat(taskResume.getRunnerId()).isNull();
    }

    @Test
    void new_runs_default_to_server() {
        Run run = Run.queued(RunType.TASK, "AGP-1", 1L, 5L, RunTrigger.USER, "harness://default", null);
        assertThat(run.getExecutionSite()).isEqualTo(ExecutionSite.SERVER);
        assertThat(run.getRunnerId()).isNull();
    }
}
