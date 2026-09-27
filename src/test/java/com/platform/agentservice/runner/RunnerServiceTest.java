package com.platform.agentservice.runner;

import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.authz.AgentAuthz;
import com.platform.agentservice.budget.BudgetProperties;
import com.platform.agentservice.credential.CredentialResolver;
import com.platform.agentservice.credential.CredentialSource;
import com.platform.agentservice.credential.ResolvedCredential;
import com.platform.agentservice.execution.ExecutionProperties;
import com.platform.agentservice.execution.ExecutionSite;
import com.platform.agentservice.pat.TokenHashing;
import com.platform.agentservice.run.BudgetGuard;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunService;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTokenService;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import com.platform.agentservice.runner.dto.RunnerClaimResponse;
import com.platform.agentservice.runner.dto.RunnerHeartbeatRequest;
import com.platform.agentservice.runner.dto.RunnerHeartbeatResponse;
import com.platform.agentservice.runner.dto.RunnerResultRequest;
import com.platform.agentservice.runner.dto.RunnerResultResponse;
import com.platform.agentservice.worker.CommitLinkParser;
import com.platform.agentservice.worker.WorkSpec;
import com.platform.agentservice.worker.WorkerJob;
import com.platform.agentservice.worker.WorkerLauncher;
import com.platform.agentservice.worker.WorkerProperties;
import com.platform.agentservice.worker.WorkerResult;
import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 러너 서비스 정책(P4a AGP-69) — 배정 거부 조건(킬 스위치·예산·동시 한도·인프로세스와의 배타), 키 전달 규칙(PLATFORM만, 없으면 사고형),
 * 결과 보고의 소유·멱등, 생존 판정, PLATFORM 부트스트랩. 원자성 자체는 {@link RunnerClaimRepositoryTest}(실 PostgreSQL)가 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunnerServiceTest {

    static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    static final long LOCAL_RUNNER_ID = 11L;
    static final long PLATFORM_RUNNER_ID = 12L;

    @Mock RunnerRepository runnerRepository;
    @Mock RunRepository runRepository;
    @Mock RunService runService;
    @Mock WorkerLauncher workerLauncher;
    @Mock RunTokenService runTokenService;
    @Mock CredentialResolver credentialResolver;
    @Mock BudgetGuard budgetGuard;
    @Mock HarnessBundleService harnessBundleService;
    @Mock AuditService auditService;
    @Mock AgentAuthz agentAuthz;

    private final Map<Long, Run> runs = new HashMap<>();
    private Runner localRunner;
    private Runner platformRunner;
    private boolean inProcess = false;
    private String platformToken = "";

    private RunnerService service() {
        WorkerProperties workerProperties = new WorkerProperties("C:\\agent-work", "C:\\bundle", List.of(), "claude", 80, 40,
                "Read", "http://localhost:9160/api/agent/mcp", Map.of(), List.of(), false);
        return new RunnerService(runnerRepository, runRepository, runService, workerLauncher, runTokenService,
                credentialResolver, budgetGuard, new BudgetProperties(new BigDecimal("100"), new BigDecimal("5")),
                new RunnerProperties("https://platform.example/api/agent/mcp", Duration.ofSeconds(90), 30_000L, platformToken),
                workerProperties, new ExecutionProperties("SERVER", inProcess), harnessBundleService, auditService,
                agentAuthz, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        localRunner = runner(LOCAL_RUNNER_ID, Runner.local("내 PC", 1L, 5L, "h-local", "agr_abcd"), NOW.minusSeconds(10));
        platformRunner = runner(PLATFORM_RUNNER_ID, Runner.platform("h-platform", "agr_plat"), NOW.minusSeconds(10));
        when(runnerRepository.findById(LOCAL_RUNNER_ID)).thenReturn(Optional.of(localRunner));
        when(runnerRepository.findById(PLATFORM_RUNNER_ID)).thenReturn(Optional.of(platformRunner));
        when(runnerRepository.save(any(Runner.class))).thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(runs.get(inv.<Long>getArgument(0))));
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> inv.getArgument(0));
        when(budgetGuard.allow(anyLong())).thenReturn(true);
        when(harnessBundleService.current()).thenReturn(new HarnessBundleService.Bundle(new byte[0], "sha-abc", NOW));
        when(runTokenService.issueFor(any())).thenReturn(new RunTokenService.IssuedRunToken(900L, "agp_run-token"));
        when(workerLauncher.buildSpec(any(), any(), anyString())).thenAnswer(inv -> new WorkSpec(
                inv.<Run>getArgument(0).getId(), "TASK", false, null, false, "https://example.com/r.git", "prompt", null,
                80, "Read", 40, inv.getArgument(2), null));
        when(runService.prepareJob(any())).thenReturn(new WorkerJob("https://example.com/r.git", "t", "b", List.of()));
    }

    private static Runner runner(long id, Runner r, Instant lastHeartbeat) {
        ReflectionTestUtils.setField(r, "id", id);
        if (lastHeartbeat != null) {
            r.touch(lastHeartbeat);
        }
        return r;
    }

    /** 조건부 UPDATE가 성공한 뒤의 상태(RUNNING·배정 러너)를 흉내 낸다. */
    private Run queuedRun(long id, long projectId, ExecutionSite site) {
        Run run = Run.queued(RunType.TASK, "AGP-" + id, projectId, 5L, RunTrigger.USER, "harness://default", null);
        run.assignExecutionSite(site);
        ReflectionTestUtils.setField(run, "id", id);
        runs.put(id, run);
        return run;
    }

    private void claimSucceeds(long runId, long runnerId) {
        when(runRepository.claimForRunner(eq(runId), eq(runnerId), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Run run = runs.get(runId);
            ReflectionTestUtils.setField(run, "status", RunStatus.RUNNING);
            ReflectionTestUtils.setField(run, "runnerId", runnerId);
            return 1;
        });
    }

    private RunnerPrincipal local() {
        return new RunnerPrincipal(LOCAL_RUNNER_ID, RunnerKind.LOCAL, 1L);
    }

    private RunnerPrincipal platform() {
        return new RunnerPrincipal(PLATFORM_RUNNER_ID, RunnerKind.PLATFORM, null);
    }

    // ---- claim ----

    @Test
    void local_runner_claims_project_run_without_any_llm_key_and_gets_run_token_and_public_mcp_url() {
        queuedRun(100L, 1L, ExecutionSite.LOCAL);
        when(runRepository.findClaimCandidatesInProject(eq(RunStatus.QUEUED), eq(ExecutionSite.LOCAL), eq(1L),
                eq(LOCAL_RUNNER_ID), any())).thenReturn(List.of(100L));
        claimSucceeds(100L, LOCAL_RUNNER_ID);

        RunnerClaimResponse response = service().claim(local()).orElseThrow();

        assertThat(response.runId()).isEqualTo(100L);
        assertThat(response.llmApiKey()).as("LOCAL 러너는 플랫폼·프로젝트 키를 절대 받지 않는다").isNull();
        assertThat(response.credentialScope()).isEqualTo("LOCAL");
        assertThat(response.runToken()).isEqualTo("agp_run-token");
        assertThat(response.spec().mcpUrl()).isEqualTo("https://platform.example/api/agent/mcp");
        assertThat(response.harness().sha256()).isEqualTo("sha-abc");
        assertThat(response.toString()).doesNotContain("agp_run-token");
        assertThat(runs.get(100L).getPatId()).isEqualTo(900L);
        verify(credentialResolver, never()).resolve(any());
    }

    @Test
    void platform_runner_gets_the_resolved_key_for_server_runs() {
        queuedRun(101L, 3L, ExecutionSite.SERVER);
        when(runRepository.findClaimCandidates(eq(RunStatus.QUEUED), eq(ExecutionSite.SERVER), eq(PLATFORM_RUNNER_ID), any()))
                .thenReturn(List.of(101L));
        claimSucceeds(101L, PLATFORM_RUNNER_ID);
        when(credentialResolver.resolve(3L)).thenReturn(new ResolvedCredential("sk-ant-project-key", CredentialSource.PROJECT));

        RunnerClaimResponse response = service().claim(platform()).orElseThrow();

        assertThat(response.llmApiKey()).isEqualTo("sk-ant-project-key");
        assertThat(response.credentialScope()).isEqualTo("PROJECT");
        assertThat(response.toString()).doesNotContain("sk-ant-project-key");
        assertThat(runs.get(101L).getCredentialScope()).isEqualTo("PROJECT");
    }

    @Test
    void platform_claim_without_any_llm_key_fails_the_run_as_incident_and_hands_out_nothing() {
        queuedRun(102L, 3L, ExecutionSite.SERVER);
        when(runRepository.findClaimCandidates(any(), eq(ExecutionSite.SERVER), anyLong(), any())).thenReturn(List.of(102L));
        claimSucceeds(102L, PLATFORM_RUNNER_ID);
        when(credentialResolver.resolve(3L)).thenReturn(ResolvedCredential.NONE);

        Optional<RunnerClaimResponse> response = service().claim(platform());

        assertThat(response).isEmpty();
        verify(runService).failIncident(102L, RunnerService.MISSING_LLM_KEY);
        verify(runTokenService, never()).issueFor(any());
    }

    @Test
    void platform_runner_does_not_claim_while_in_process_execution_is_on() {
        inProcess = true;

        assertThat(service().claim(platform())).isEmpty();
        verify(runRepository, never()).findClaimCandidates(any(), any(), anyLong(), any());
    }

    @Test
    void kill_switch_or_budget_cap_refuses_claim() {
        queuedRun(103L, 1L, ExecutionSite.LOCAL);
        when(runRepository.findClaimCandidatesInProject(any(), any(), anyLong(), anyLong(), any())).thenReturn(List.of(103L));
        when(budgetGuard.allow(1L)).thenReturn(false);

        assertThat(service().claim(local())).isEmpty();
        verify(runRepository, never()).claimForRunner(anyLong(), anyLong(), any(), any(), any(), any(), any());
        assertThat(runs.get(103L).getStatus()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void runner_at_its_concurrency_limit_gets_nothing() {
        when(runRepository.countByRunnerIdAndStatus(LOCAL_RUNNER_ID, RunStatus.RUNNING)).thenReturn(1L); // max_concurrency 1

        assertThat(service().claim(local())).isEmpty();
        verify(runRepository, never()).findClaimCandidatesInProject(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void losing_the_race_moves_on_to_the_next_candidate() {
        queuedRun(104L, 1L, ExecutionSite.LOCAL);
        queuedRun(105L, 1L, ExecutionSite.LOCAL);
        when(runRepository.findClaimCandidatesInProject(any(), any(), anyLong(), anyLong(), any())).thenReturn(List.of(104L, 105L));
        when(runRepository.claimForRunner(eq(104L), anyLong(), any(), any(), any(), any(), any())).thenReturn(0);
        claimSucceeds(105L, LOCAL_RUNNER_ID);

        assertThat(service().claim(local()).orElseThrow().runId()).isEqualTo(105L);
    }

    @Test
    void preparation_failure_after_claim_revokes_nothing_issued_and_blocks_as_incident() {
        queuedRun(106L, 1L, ExecutionSite.LOCAL);
        when(runRepository.findClaimCandidatesInProject(any(), any(), anyLong(), anyLong(), any())).thenReturn(List.of(106L));
        claimSucceeds(106L, LOCAL_RUNNER_ID);
        when(runService.prepareJob(any())).thenThrow(new IllegalStateException("리포 매핑 없음: AGP"));

        assertThat(service().claim(local())).isEmpty();
        verify(runService).failIncident(106L, "리포 매핑 없음: AGP");
        verify(runTokenService, never()).issueFor(any());
    }

    // ---- 결과 보고 ----

    private RunnerResultRequest result(List<RunnerResultRequest.CommitReport> commits) {
        return new RunnerResultRequest(0, false, "완료", "sess-1", new BigDecimal("0.42"), 10L, 20L, "claude-x", "tail",
                "C:\\agent-work\\run-200", commits);
    }

    private Run runningOn(long id, long runnerId) {
        Run run = queuedRun(id, 1L, ExecutionSite.LOCAL);
        ReflectionTestUtils.setField(run, "status", RunStatus.RUNNING);
        ReflectionTestUtils.setField(run, "runnerId", runnerId);
        ReflectionTestUtils.setField(run, "patId", 900L);
        ReflectionTestUtils.setField(run, "credentialScope", "LOCAL");
        return run;
    }

    @Test
    void result_from_a_runner_that_did_not_claim_the_run_is_403() {
        runningOn(200L, 99L);

        assertThatThrownBy(() -> service().reportResult(local(), 200L, result(List.of())))
                .isInstanceOf(ForbiddenException.class);
        verify(runService, never()).applyExternalOutcome(anyLong(), any(), any());
    }

    @Test
    void first_result_revokes_run_token_and_goes_through_the_same_outcome_path_with_filtered_commits() {
        runningOn(201L, LOCAL_RUNNER_ID);
        when(runRepository.markRunnerResult(eq(201L), eq(LOCAL_RUNNER_ID), any())).thenReturn(1);

        RunnerResultResponse response = service().reportResult(local(), 201L, result(List.of(
                new RunnerResultRequest.CommitReport("AGP-201", "abc123", "fix", "https://github.com/x/y/commit/abc123"),
                new RunnerResultRequest.CommitReport("not a key", "def", "x", "https://github.com/x/y/commit/def"),
                new RunnerResultRequest.CommitReport("AGP-7", "fed", "y", "file:///etc/passwd"))));

        assertThat(response.accepted()).isTrue();
        assertThat(response.duplicate()).isFalse();
        verify(runTokenService).revoke(900L);
        ArgumentCaptor<WorkerResult> resultCaptor = ArgumentCaptor.forClass(WorkerResult.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommitLinkParser.CommitLink>> commits = ArgumentCaptor.forClass(List.class);
        verify(runService).applyExternalOutcome(eq(201L), resultCaptor.capture(), commits.capture());
        assertThat(resultCaptor.getValue().credentialScope()).isEqualTo("LOCAL");
        assertThat(resultCaptor.getValue().costUsd()).isEqualByComparingTo("0.42");
        assertThat(resultCaptor.getValue().workspacePath()).isEqualTo("C:\\agent-work\\run-200");
        assertThat(commits.getValue()).extracting(CommitLinkParser.CommitLink::issueKey).containsExactly("AGP-201", "AGP-7");
        assertThat(commits.getValue().get(1).url()).as("http(s)가 아닌 URL은 링크하지 않는다").isNull();
    }

    @Test
    void repeated_result_is_idempotent() {
        runningOn(202L, LOCAL_RUNNER_ID);
        when(runRepository.markRunnerResult(eq(202L), eq(LOCAL_RUNNER_ID), any())).thenReturn(0);

        RunnerResultResponse response = service().reportResult(local(), 202L, result(null));

        assertThat(response.accepted()).isFalse();
        assertThat(response.duplicate()).isTrue();
        verify(runService, never()).applyExternalOutcome(anyLong(), any(), any());
        verify(runTokenService, never()).revoke(anyLong());
    }

    // ---- heartbeat ----

    @Test
    void heartbeat_updates_runner_and_tells_it_to_stop_runs_cancelled_or_not_its_own() {
        Run mine = runningOn(300L, LOCAL_RUNNER_ID);
        Run cancelled = runningOn(301L, LOCAL_RUNNER_ID);
        ReflectionTestUtils.setField(cancelled, "status", RunStatus.CANCELLED);
        runningOn(302L, 77L);

        RunnerHeartbeatResponse response = service().heartbeat(local(),
                new RunnerHeartbeatRequest("0.1.0", "Windows 11", 3, List.of(mine.getId(), 301L, 302L, 999L)));

        assertThat(response.stopRunIds()).containsExactly(301L, 302L, 999L);
        assertThat(localRunner.getLastHeartbeatAt()).isEqualTo(NOW);
        assertThat(localRunner.getMaxConcurrency()).isEqualTo(3);
        assertThat(localRunner.getRunnerVersion()).isEqualTo("0.1.0");
        assertThat(response.offlineAfterSeconds()).isEqualTo(90);
    }

    // ---- 생존 판정 ----

    @Test
    void runner_silent_for_more_than_90s_blocks_its_running_runs_as_incident_and_revokes_tokens() {
        localRunner.touch(NOW.minusSeconds(91));
        when(runnerRepository.findAll()).thenReturn(List.of(localRunner, platformRunner));
        Run stranded = runningOn(400L, LOCAL_RUNNER_ID);
        when(runRepository.findByStatusAndRunnerIdIsNotNull(RunStatus.RUNNING)).thenReturn(List.of(stranded));

        service().sweepOffline();

        verify(runTokenService).revoke(900L);
        verify(runService).failIncident(eq(400L), contains("러너 연결 끊김"));
    }

    @Test
    void online_runner_runs_are_left_alone() {
        when(runnerRepository.findAll()).thenReturn(List.of(localRunner));
        Run healthy = runningOn(401L, LOCAL_RUNNER_ID);
        when(runRepository.findByStatusAndRunnerIdIsNotNull(RunStatus.RUNNING)).thenReturn(List.of(healthy));

        service().sweepOffline();

        verify(runService, never()).failIncident(anyLong(), anyString());
        verify(runTokenService, never()).revoke(anyLong());
    }

    @Test
    void revoked_runner_counts_as_disconnected() {
        localRunner.revoke(NOW.minusSeconds(5));
        when(runnerRepository.findAll()).thenReturn(List.of(localRunner));
        Run stranded = runningOn(402L, LOCAL_RUNNER_ID);
        when(runRepository.findByStatusAndRunnerIdIsNotNull(RunStatus.RUNNING)).thenReturn(List.of(stranded));

        service().sweepOffline();

        verify(runService).failIncident(eq(402L), contains("철회"));
    }

    // ---- 관리·부트스트랩 ----

    @Test
    void platform_runner_cannot_be_revoked_through_the_api() {
        assertThatThrownBy(() -> service().revoke(PLATFORM_RUNNER_ID, new com.platform.agentservice.authz.AgentCaller(1L, true)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void platform_bootstrap_creates_runner_from_env_token_and_revokes_stale_ones() {
        platformToken = "agr_" + "x".repeat(40);
        String hash = TokenHashing.sha256Hex(platformToken);
        when(runnerRepository.findByKind(RunnerKind.PLATFORM)).thenReturn(List.of(platformRunner));
        when(runnerRepository.findByTokenHash(hash)).thenReturn(Optional.empty());
        when(runnerRepository.save(any(Runner.class))).thenAnswer(inv -> {
            Runner r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 50L);
            return r;
        });

        service().bootstrapPlatformRunner();

        ArgumentCaptor<Runner> saved = ArgumentCaptor.forClass(Runner.class);
        verify(runnerRepository).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(RunnerKind.PLATFORM);
        assertThat(saved.getValue().getTokenHash()).isEqualTo(hash);
        assertThat(platformRunner.isRevoked()).as("토큰 교체 — 이전 PLATFORM 행은 철회").isTrue();
    }

    @Test
    void platform_bootstrap_without_env_token_leaves_no_platform_runner() {
        when(runnerRepository.findByKind(RunnerKind.PLATFORM)).thenReturn(List.of(platformRunner));

        service().bootstrapPlatformRunner();

        assertThat(platformRunner.isRevoked()).isTrue();
        verify(runnerRepository, never()).save(any(Runner.class));
    }

    @Test
    void authenticate_accepts_only_agr_tokens_of_active_runners() {
        String token = "agr_" + "y".repeat(40);
        Runner r = runner(60L, Runner.local("pc", 1L, 5L, TokenHashing.sha256Hex(token), "agr_yyyy"), null);
        when(runnerRepository.findByTokenHash(TokenHashing.sha256Hex(token))).thenReturn(Optional.of(r));

        assertThat(service().authenticate(token)).map(RunnerPrincipal::runnerId).contains(60L);
        assertThat(service().authenticate("agp_" + "y".repeat(40))).isEmpty();
        r.revoke(NOW);
        assertThat(service().authenticate(token)).isEmpty();
    }
}
