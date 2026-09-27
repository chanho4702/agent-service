package com.platform.agentservice.runner;

import com.platform.agentservice.alert.AlertKind;
import com.platform.agentservice.alert.AlertService;
import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.authz.AgentAuthz;
import com.platform.agentservice.budget.BudgetProperties;
import com.platform.agentservice.budget.UsageLedgerRepository;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.IssueClaimSupport;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.credential.CredentialResolver;
import com.platform.agentservice.execution.ExecutionProperties;
import com.platform.agentservice.execution.ExecutionSite;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.run.BudgetGuard;
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
import com.platform.agentservice.worker.CommitLinkParser;
import com.platform.agentservice.worker.WorkerLauncher;
import com.platform.agentservice.worker.WorkerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 생존 판정 → 사고형 BLOCKED(D-P4-3)를 실제 JPA(H2) 아래 끝까지 태운다 — 러너 heartbeat가 90초 넘게 끊기면 그 러너의 RUNNING run이
 * 인프로세스 실패와 같은 경로({@code RunService.failIncident} → fail → block)로 BLOCKED가 되고, 이슈 코멘트·메일(INCIDENT)이 나간다.
 * 협력자 중 run·러너 저장소만 진짜고 ALM·메일은 목이다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunnerLivenessTest {

    static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @Autowired RunRepository runRepository;
    @Autowired RunnerRepository runnerRepository;
    @Autowired PersonaRepository personaRepository;
    @Autowired UsageLedgerRepository usageLedgerRepository;

    private final AlmClient almClient = Mockito.mock(AlmClient.class);
    private final AlertService alertService = Mockito.mock(AlertService.class);
    private final RunTokenService runTokenService = Mockito.mock(RunTokenService.class);
    private RunnerService runnerService;
    private Persona persona;

    @BeforeEach
    void setUp() {
        persona = personaRepository.save(Persona.of(42L, "jiho", PersonaRole.BACKEND, "지호", "🔧", null));
        TokenService tokenService = Mockito.mock(TokenService.class);
        when(tokenService.bearerFor(anyLong())).thenReturn("Bearer x");
        IssueResponse issue = new IssueResponse(1L, "AGP-9", 1L, "제목", "본문", "task", "inprogress", "high",
                42L, 1L, null, null, null, null, null, null, List.of(), List.of(), 0L, 1, null, null, null);
        when(almClient.getByKey(anyString(), anyString())).thenReturn(issue);
        when(almClient.addComment(anyLong(), anyString(), anyString()))
                .thenReturn(new CommentResponse(1L, 1L, 42L, "b", null, null));

        WorkerProperties workerProperties = new WorkerProperties("C:\\agent-work", "C:\\bundle", List.of(), "claude", 80, 40,
                "Read", "http://localhost/api/agent/mcp", Map.of("AGP", "https://example.com/agp.git"), List.of(), false);
        BudgetProperties budgetProperties = new BudgetProperties(new BigDecimal("100"), new BigDecimal("5"));
        BudgetGuard budgetGuard = Mockito.mock(BudgetGuard.class);
        RunService runService = new RunService(runRepository, almClient, Mockito.mock(IssueClaimSupport.class), tokenService,
                personaRepository, Mockito.mock(WorkerLauncher.class), workerProperties, usageLedgerRepository,
                new SchedulerProperties(false, 60000L, 2, 1, "jiho", 3), budgetProperties,
                Mockito.mock(CommitLinkParser.class), budgetGuard, Mockito.mock(ReviewService.class),
                Mockito.mock(MeetingService.class), alertService);
        runnerService = new RunnerService(runnerRepository, runRepository, runService, Mockito.mock(WorkerLauncher.class),
                runTokenService, Mockito.mock(CredentialResolver.class), budgetGuard, budgetProperties,
                new RunnerProperties(null, Duration.ofSeconds(90), 30_000L, null), workerProperties,
                new ExecutionProperties("SERVER", false), Mockito.mock(HarnessBundleService.class),
                Mockito.mock(AuditService.class), Mockito.mock(AgentAuthz.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void cleanUp() {
        runRepository.deleteAll();
        runnerRepository.deleteAll();
        personaRepository.deleteAll();
    }

    private Runner runnerLastSeen(Instant lastHeartbeat) {
        Runner runner = Runner.local("내 PC", 1L, 5L, "hash-" + lastHeartbeat.getEpochSecond(), "agr_abcd");
        runner.touch(lastHeartbeat);
        return runnerRepository.save(runner);
    }

    private Run claimedBy(Runner runner) {
        Run run = Run.queued(RunType.TASK, "AGP-9", 1L, persona.getId(), RunTrigger.USER, "harness://default", null);
        run.assignExecutionSite(ExecutionSite.LOCAL);
        run = runRepository.save(run);
        runRepository.claimForRunner(run.getId(), runner.getId(), ExecutionSite.LOCAL, RunStatus.QUEUED, RunStatus.RUNNING,
                "pending", NOW.minusSeconds(300));
        Run running = runRepository.findById(run.getId()).orElseThrow();
        running.attachRunnerLaunch(900L, "LOCAL");
        return runRepository.save(running);
    }

    @Test
    void disconnected_runner_run_lands_on_blocked_with_comment_and_incident_mail() {
        Runner silent = runnerLastSeen(NOW.minusSeconds(91));
        Run run = claimedBy(silent);

        runnerService.sweepOffline();

        Run after = runRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.BLOCKED);
        assertThat(after.getError()).contains("러너 연결 끊김");
        assertThat(after.getPatId()).as("run 토큰 참조는 철회 후 지운다").isNull();
        verify(runTokenService).revoke(900L);
        verify(almClient).addComment(eq(1L), contains("러너 연결 끊김"), anyString());
        verify(alertService).notifyBlocked(any(Run.class), contains("러너 연결 끊김"), eq(AlertKind.INCIDENT));
    }

    @Test
    void runner_heard_from_within_90s_keeps_its_run_running() {
        Runner alive = runnerLastSeen(NOW.minusSeconds(30));
        Run run = claimedBy(alive);

        runnerService.sweepOffline();

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }
}
