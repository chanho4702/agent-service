package com.platform.agentservice.runner;

import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.authz.AgentAuthz;
import com.platform.agentservice.authz.AgentCaller;
import com.platform.agentservice.budget.BudgetProperties;
import com.platform.agentservice.credential.CredentialResolver;
import com.platform.agentservice.credential.ResolvedCredential;
import com.platform.agentservice.execution.ExecutionProperties;
import com.platform.agentservice.pat.TokenHashing;
import com.platform.agentservice.run.BudgetGuard;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunService;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTokenService;
import com.platform.agentservice.runner.dto.RunnerClaimResponse;
import com.platform.agentservice.runner.dto.RunnerCreateRequest;
import com.platform.agentservice.runner.dto.RunnerCreatedResponse;
import com.platform.agentservice.runner.dto.RunnerHeartbeatRequest;
import com.platform.agentservice.runner.dto.RunnerHeartbeatResponse;
import com.platform.agentservice.runner.dto.RunnerResultRequest;
import com.platform.agentservice.runner.dto.RunnerResultResponse;
import com.platform.agentservice.runner.dto.RunnerSummaryResponse;
import com.platform.agentservice.worker.CommitLinkParser;
import com.platform.agentservice.worker.WorkSpec;
import com.platform.agentservice.worker.WorkerExecution;
import com.platform.agentservice.worker.WorkerJob;
import com.platform.agentservice.worker.WorkerLauncher;
import com.platform.agentservice.worker.WorkerProperties;
import com.platform.agentservice.worker.WorkerResult;
import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 러너 도메인(P4a AGP-69, D-P4-3·D-P4-5) — 토큰 인증·heartbeat·원자적 claim·결과 보고·생존 판정·관리(발급·목록·철회)·PLATFORM 러너
 * 부트스트랩.
 *
 * <p><b>인프로세스 경로와 같은 준비·종결</b>: claim은 {@link RunService#prepareJob}(인프로세스 {@code execute}와 같은 리포 매핑·이슈
 * claim·코멘트 수집)과 {@link WorkerLauncher#buildSpec}(같은 프롬프트·모델·도구 허용 목록)을 쓰고, 결과는
 * {@link RunService#applyExternalOutcome}(같은 {@code applyOutcome} — 원장·리뷰 상시화·반려 예산·사고형 즉시 중단·에스컬레이션)으로 반영한다.
 * 준비 실패·러너 연결 끊김은 {@link RunService#failIncident}(인프로세스 실패와 같은 BLOCKED + 코멘트 + 메일).
 *
 * <p><b>LLM 키(D-P4-5)</b>: PLATFORM 러너는 claim 응답으로 해석된 키(프로젝트 &gt; 전역 &gt; 서비스 env)를 받고, 키가 없으면 run을 넘기지 않고
 * 사고형으로 실패시킨다. LOCAL 러너는 어떤 층의 키도 받지 않는다 — 그 PC의 구독·자기 env 키로 돈다(과금 분리). 키·토큰은 로그·감사에
 * 싣지 않는다.
 */
@Slf4j
@Service("runnerService")
public class RunnerService {

    public static final String TOKEN_PREFIX = "agr_";
    /** AGP-68 {@link WorkerLauncher.MissingLlmKeyException}과 같은 문구 — 서버 실행에는 API 키가 필수다(구독 세션 금지). */
    public static final String MISSING_LLM_KEY = WorkerLauncher.MissingLlmKeyException.MESSAGE;
    /** LOCAL 러너 run의 원장 과금 출처 — 우리 키가 아니라 그 PC의 인증. */
    public static final String LOCAL_CREDENTIAL_SCOPE = "LOCAL";
    static final int CLAIM_CANDIDATE_LIMIT = 20;
    static final int MODEL_MAX = 60;
    private static final int PLATFORM_TOKEN_MIN_LENGTH = 24;
    private static final Pattern ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9_]{1,11}-\\d+");
    /** 러너가 돌리고 있다면 멈춰야 하는 상태 — WAITING_APPROVAL·DONE은 워커가 마무리 중일 수 있어 멈추지 않는다. */
    private static final Set<RunStatus> STOP_STATUSES = EnumSet.of(RunStatus.CANCELLED, RunStatus.BLOCKED);

    private final RunnerRepository runnerRepository;
    private final RunRepository runRepository;
    private final RunService runService;
    private final WorkerLauncher workerLauncher;
    private final RunTokenService runTokenService;
    private final CredentialResolver credentialResolver;
    private final BudgetGuard budgetGuard;
    private final BudgetProperties budgetProperties;
    private final RunnerProperties runnerProperties;
    private final WorkerProperties workerProperties;
    private final ExecutionProperties executionProperties;
    private final HarnessBundleService harnessBundleService;
    private final AuditService auditService;
    private final AgentAuthz agentAuthz;
    private final Clock clock;

    @Autowired
    public RunnerService(RunnerRepository runnerRepository, RunRepository runRepository, RunService runService,
                         WorkerLauncher workerLauncher, RunTokenService runTokenService,
                         CredentialResolver credentialResolver, BudgetGuard budgetGuard, BudgetProperties budgetProperties,
                         RunnerProperties runnerProperties, WorkerProperties workerProperties,
                         ExecutionProperties executionProperties, HarnessBundleService harnessBundleService,
                         AuditService auditService, AgentAuthz agentAuthz) {
        this(runnerRepository, runRepository, runService, workerLauncher, runTokenService, credentialResolver, budgetGuard,
                budgetProperties, runnerProperties, workerProperties, executionProperties, harnessBundleService, auditService,
                agentAuthz, Clock.systemUTC());
    }

    RunnerService(RunnerRepository runnerRepository, RunRepository runRepository, RunService runService,
                  WorkerLauncher workerLauncher, RunTokenService runTokenService, CredentialResolver credentialResolver,
                  BudgetGuard budgetGuard, BudgetProperties budgetProperties, RunnerProperties runnerProperties,
                  WorkerProperties workerProperties, ExecutionProperties executionProperties,
                  HarnessBundleService harnessBundleService, AuditService auditService, AgentAuthz agentAuthz, Clock clock) {
        this.runnerRepository = runnerRepository;
        this.runRepository = runRepository;
        this.runService = runService;
        this.workerLauncher = workerLauncher;
        this.runTokenService = runTokenService;
        this.credentialResolver = credentialResolver;
        this.budgetGuard = budgetGuard;
        this.budgetProperties = budgetProperties;
        this.runnerProperties = runnerProperties;
        this.workerProperties = workerProperties;
        this.executionProperties = executionProperties;
        this.harnessBundleService = harnessBundleService;
        this.auditService = auditService;
        this.agentAuthz = agentAuthz;
        this.clock = clock;
    }

    // ---- 인증 ----

    /** {@link RunnerAuthFilter} 전용. {@code agr_}가 아니거나 철회됐거나 모르는 토큰이면 empty(401). 원문은 로그에 싣지 않는다. */
    @Transactional(readOnly = true)
    public Optional<RunnerPrincipal> authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Optional<Runner> runner = runnerRepository.findByTokenHash(TokenHashing.sha256Hex(rawToken));
        if (runner.isEmpty()) {
            log.warn("러너 토큰 거부 — 일치하는 러너 없음: prefix={}…", TokenHashing.displayPrefix(rawToken));
            return Optional.empty();
        }
        if (runner.get().isRevoked()) {
            log.warn("러너 토큰 거부 — 철회된 러너: id={}", runner.get().getId());
            return Optional.empty();
        }
        Runner r = runner.get();
        return Optional.of(new RunnerPrincipal(r.getId(), r.getKind(), r.getProjectId()));
    }

    // ---- 프로토콜 ----

    @Transactional
    public RunnerHeartbeatResponse heartbeat(RunnerPrincipal principal, RunnerHeartbeatRequest request) {
        Runner runner = loadActive(principal);
        Instant now = clock.instant();
        runner.heartbeat(now, request == null ? null : request.version(), request == null ? null : request.os(),
                request == null ? null : request.maxConcurrency());
        List<Long> stop = new ArrayList<>();
        List<Long> reported = request == null || request.runIds() == null ? List.of() : request.runIds();
        for (Long runId : reported.stream().filter(java.util.Objects::nonNull).distinct().limit(50).toList()) {
            Optional<Run> run = runRepository.findById(runId);
            boolean mine = run.isPresent() && runner.getId().equals(run.get().getRunnerId());
            if (!mine || STOP_STATUSES.contains(run.get().getStatus())) {
                stop.add(runId);
            }
        }
        return new RunnerHeartbeatResponse(runner.getId(), runner.getKind(), now, stop,
                runnerProperties.offlineAfter().toSeconds());
    }

    /**
     * QUEUED run 하나를 이 러너에 원자적으로 배정한다(조건부 UPDATE — {@link RunRepository#claimForRunner}). 줄 게 없으면 empty(204).
     *
     * <p>거부 조건: 킬 스위치·예산 캡({@link BudgetGuard#allow} — 인프로세스 {@code execute} 진입점과 같은 판정), 동시 한도(이 러너에
     * RUNNING인 run 수 ≥ max_concurrency), PLATFORM 러너인데 인프로세스 실행이 켜져 있음(SERVER run은 서비스가 직접 돈다 — 두 경로가
     * 같은 run을 두고 다투지 않게). 범위: PLATFORM=SERVER run 전체, LOCAL=LOCAL run 중 그 프로젝트(프로젝트 러너) 또는 전체(전역 러너).
     * 계보 run이 다른 러너에 고정돼 있으면 후보에서 빠진다.
     */
    public Optional<RunnerClaimResponse> claim(RunnerPrincipal principal) {
        Runner runner = touch(principal);
        if (runner.getKind() == RunnerKind.PLATFORM && executionProperties.inProcessEnabled()) {
            log.debug("인프로세스 실행이 켜져 있어 PLATFORM 러너 claim을 건너뜁니다: runner={}", runner.getId());
            return Optional.empty();
        }
        if (runRepository.countByRunnerIdAndStatus(runner.getId(), RunStatus.RUNNING) >= runner.getMaxConcurrency()) {
            return Optional.empty();
        }
        List<Long> candidates = runner.getKind() == RunnerKind.LOCAL && runner.getProjectId() != null
                ? runRepository.findClaimCandidatesInProject(RunStatus.QUEUED, runner.getKind().site, runner.getProjectId(),
                        runner.getId(), PageRequest.of(0, CLAIM_CANDIDATE_LIMIT))
                : runRepository.findClaimCandidates(RunStatus.QUEUED, runner.getKind().site, runner.getId(),
                        PageRequest.of(0, CLAIM_CANDIDATE_LIMIT));
        for (Long runId : candidates) {
            Optional<Run> candidate = runRepository.findById(runId);
            if (candidate.isEmpty() || !budgetGuard.allow(candidate.get().getProjectId())) {
                continue;
            }
            int claimed = runRepository.claimForRunner(runId, runner.getId(), runner.getKind().site, RunStatus.QUEUED,
                    RunStatus.RUNNING, WorkerExecution.PENDING_WORKSPACE, clock.instant());
            if (claimed != 1) {
                continue; // 다른 러너·인프로세스가 먼저 집었다
            }
            Optional<RunnerClaimResponse> launched = launch(runner, runId);
            if (launched.isPresent()) {
                return launched;
            }
        }
        return Optional.empty();
    }

    /** 배정된 run의 명세·토큰 발급. 실패하면 발급한 토큰을 철회하고 인프로세스 실패와 같은 사고형 BLOCKED로 넘긴다. */
    private Optional<RunnerClaimResponse> launch(Runner runner, long runId) {
        RunTokenService.IssuedRunToken issued = null;
        try {
            Run run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException("run을 찾을 수 없습니다: " + runId));
            WorkerJob job = runService.prepareJob(run);

            String llmKey = null;
            String credentialScope;
            if (runner.getKind() == RunnerKind.PLATFORM) {
                ResolvedCredential credential = credentialResolver.resolve(run.getProjectId());
                if (!credential.present()) {
                    throw new WorkerLauncher.MissingLlmKeyException();
                }
                llmKey = credential.apiKey();
                credentialScope = credential.source().name();
            } else {
                credentialScope = LOCAL_CREDENTIAL_SCOPE;
            }
            WorkSpec spec = workerLauncher.buildSpec(run, job, mcpUrl());
            issued = runTokenService.issueFor(run);

            Run running = runRepository.findById(runId).orElseThrow();
            running.attachRunnerLaunch(issued.patId(), credentialScope);
            runRepository.save(running);

            HarnessBundleService.Bundle bundle = harnessBundleService.current();
            log.info("run={} 러너 배정 — runner={}({}) 키 출처={}", runId, runner.getId(), runner.getKind(), credentialScope);
            return Optional.of(new RunnerClaimResponse(runId, running.getIssueKey(), running.getProjectId(),
                    running.getAttempt(), running.getExecutionSite(), spec, issued.token(), llmKey, credentialScope,
                    new RunnerClaimResponse.Harness(bundle.sha256(), RunnerPaths.HARNESS),
                    new RunnerClaimResponse.Budget(budgetProperties.perRunUsdCap(), budgetProperties.monthlyUsdCap())));
        } catch (Exception e) {
            if (issued != null) {
                safelyRevoke(issued.patId());
            }
            log.warn("run={} 러너 배정 후 준비 실패 — 사고형 중단합니다: {}", runId, e.getMessage());
            try {
                runService.failIncident(runId, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } catch (Exception failure) {
                log.error("run={} 준비 실패 처리도 실패했습니다 — run이 RUNNING에 남을 수 있습니다(생존 판정이 정리): {}",
                        runId, failure.getMessage());
            }
            return Optional.empty();
        }
    }

    /**
     * 결과 보고 — 배정받은 러너만(아니면 403). 같은 보고가 다시 와도 한 번만 처리한다(조건부 UPDATE 표식). 처리 순서: run 토큰 철회 →
     * 인프로세스와 같은 {@code applyOutcome}.
     */
    public RunnerResultResponse reportResult(RunnerPrincipal principal, long runId, RunnerResultRequest request) {
        Runner runner = touch(principal);
        Run run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException("run을 찾을 수 없습니다: " + runId));
        if (run.getRunnerId() == null || !run.getRunnerId().equals(runner.getId())) {
            throw new ForbiddenException("이 러너가 배정받은 run이 아닙니다");
        }
        if (runRepository.markRunnerResult(runId, runner.getId(), clock.instant()) != 1) {
            RunStatus status = runRepository.findById(runId).map(Run::getStatus).orElse(null);
            log.info("run={} 러너 결과 중복 보고 — 무시합니다(runner={})", runId, runner.getId());
            return new RunnerResultResponse(false, true, status);
        }
        Run marked = runRepository.findById(runId).orElseThrow();
        revokeRunToken(marked);

        WorkerResult result = new WorkerResult(request.exitCode(), Boolean.TRUE.equals(request.timedOut()), request.resultText(),
                request.sessionId(), request.costUsd(), nonNegative(request.inputTokens()), nonNegative(request.outputTokens()),
                cut(request.model(), MODEL_MAX), WorkerExecution.tail(request.rawTail()), request.workspacePath(),
                marked.getCredentialScope());
        runService.applyExternalOutcome(runId, result, commitsOf(request));
        RunStatus status = runRepository.findById(runId).map(Run::getStatus).orElse(null);
        return new RunnerResultResponse(true, false, status);
    }

    /**
     * 생존 판정(D-P4-3) — heartbeat가 {@code offline-after}보다 오래 끊긴(또는 철회된) 러너에 배정돼 RUNNING인 run은 사고형 BLOCKED
     * ("러너 연결 끊김") + 이슈 코멘트 + 메일(인프로세스 실패와 같은 {@link RunService#failIncident} 경로). 결과 보고를 받았는데 반영이
     * 끝나지 않은 채 멈춘 run(표식만 남고 RUNNING)도 같은 창이 지나면 정리한다. 오프라인 러너에 남은 run 토큰도 철회한다.
     */
    public void sweepOffline() {
        Instant now = clock.instant();
        Duration offlineAfter = runnerProperties.offlineAfter();
        Map<Long, Runner> runners = runnerRepository.findAll().stream()
                .collect(Collectors.toMap(Runner::getId, Function.identity()));
        for (Run run : runRepository.findByStatusAndRunnerIdIsNotNull(RunStatus.RUNNING)) {
            try {
                String reason = offlineReason(runners.get(run.getRunnerId()), run, now, offlineAfter);
                if (reason == null && run.getRunnerResultAt() != null
                        && run.getRunnerResultAt().isBefore(now.minus(offlineAfter))) {
                    reason = "러너 결과 반영 실패 — 결과를 받았지만 처리가 끝나지 않았습니다(runner=" + run.getRunnerId() + ")";
                }
                if (reason != null) {
                    log.warn("run={} {}", run.getId(), reason);
                    revokeRunToken(run);
                    runService.failIncident(run.getId(), reason);
                }
            } catch (Exception e) {
                log.warn("run={} 생존 판정 처리 실패 — 다음 주기에 다시 봅니다: {}", run.getId(), e.getMessage());
            }
        }
        for (Run run : runRepository.findByRunnerIdIsNotNullAndPatIdIsNotNull()) {
            try {
                Runner runner = runners.get(run.getRunnerId());
                if (run.getStatus() != RunStatus.RUNNING && (runner == null || !runner.isOnline(now, offlineAfter))) {
                    revokeRunToken(run);
                }
            } catch (Exception e) {
                log.warn("run={} 남은 run 토큰 철회 실패 — 다음 주기에 다시 봅니다: {}", run.getId(), e.getMessage());
            }
        }
    }

    private static String offlineReason(Runner runner, Run run, Instant now, Duration offlineAfter) {
        if (runner == null) {
            return "러너 연결 끊김 — 배정 러너 " + run.getRunnerId() + "를 찾을 수 없습니다";
        }
        if (runner.isRevoked()) {
            return "러너 연결 끊김 — 배정 러너 " + runner.getId() + "(" + runner.getName() + ")가 철회됐습니다";
        }
        if (!runner.isOnline(now, offlineAfter)) {
            return "러너 연결 끊김 — 러너 " + runner.getId() + "(" + runner.getName() + ") heartbeat가 "
                    + offlineAfter.toSeconds() + "초 넘게 없습니다(마지막 " + runner.getLastHeartbeatAt() + ")";
        }
        return null;
    }

    // ---- 관리 ----

    /** LOCAL 러너 토큰 발급(PLATFORM은 env로만). 권한 판정은 컨트롤러(@PreAuthorize — 프로젝트 null이면 전역 관리자만). */
    @Transactional
    public RunnerCreatedResponse create(RunnerCreateRequest request, AgentCaller caller) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name은 필수입니다");
        }
        String token = TokenHashing.generate(TOKEN_PREFIX);
        String prefix = TokenHashing.displayPrefix(token);
        Runner saved = runnerRepository.save(Runner.local(name, request.projectId(), caller.userId(),
                TokenHashing.sha256Hex(token), prefix));
        auditService.record(null, caller.userId(), "runner.create",
                "runnerId=" + saved.getId() + " projectId=" + saved.getProjectId() + " prefix=" + prefix, AuditStatus.OK);
        return new RunnerCreatedResponse(saved.getId(), saved.getKind(), saved.getName(), saved.getProjectId(), token,
                prefix, saved.getCreatedAt());
    }

    /** projectId 없으면 전체(전역 관리자), 있으면 그 프로젝트 LOCAL 러너 + 플랫폼 전역 LOCAL 러너(그 프로젝트 run도 집을 수 있다). */
    @Transactional(readOnly = true)
    public List<RunnerSummaryResponse> list(Long projectId) {
        Instant now = clock.instant();
        return runnerRepository.findAllByOrderByIdAsc().stream()
                .filter(r -> projectId == null
                        || (r.getKind() == RunnerKind.LOCAL && (r.getProjectId() == null || projectId.equals(r.getProjectId()))))
                .map(r -> new RunnerSummaryResponse(r.getId(), r.getKind(), r.getName(), r.getProjectId(), r.getIssuedBy(),
                        r.getTokenPrefix(), r.getCreatedAt(), r.getRevokedAt(), r.getLastHeartbeatAt(), r.getRunnerVersion(),
                        r.getOs(), r.getMaxConcurrency(), r.status(now, runnerProperties.offlineAfter()),
                        runRepository.findByRunnerIdAndStatus(r.getId(), RunStatus.RUNNING).stream().map(Run::getId).toList()))
                .toList();
    }

    /**
     * 철회 — 멱등(없는 id도 204). PLATFORM은 env가 정본이라 API로 철회하지 않는다(409). 이 러너에 배정돼 돌던 run은 다음 생존 판정에서
     * "러너 연결 끊김"으로 BLOCKED된다. 이 러너에 고정된 QUEUED 계보 run은 그대로 남는다(사무실에 "러너 대기" — 사람이 취소한다).
     */
    @Transactional
    public void revoke(long runnerId, AgentCaller caller) {
        Optional<Runner> runner = runnerRepository.findById(runnerId);
        if (runner.isEmpty()) {
            return;
        }
        if (runner.get().getKind() == RunnerKind.PLATFORM) {
            throw new ConflictException("플랫폼 러너는 AGENT_PLATFORM_RUNNER_TOKEN으로 관리합니다 — API로 철회할 수 없습니다");
        }
        if (!runner.get().isRevoked()) {
            runner.get().revoke(clock.instant());
            auditService.record(null, caller.userId(), "runner.revoke", "runnerId=" + runnerId, AuditStatus.OK);
        }
    }

    /**
     * {@code @PreAuthorize("@runnerService.canManageRunner(authentication, #id)")} — 러너 소속 프로젝트 관리자, 전역 러너·PLATFORM·
     * 없는 id는 전역 관리자만(프로젝트 관리자에게 존재 여부를 흘리지 않는다 — §7 규칙).
     */
    @Transactional(readOnly = true)
    public boolean canManageRunner(Authentication authentication, long runnerId) {
        Long projectId = runnerRepository.findById(runnerId)
                .filter(r -> r.getKind() == RunnerKind.LOCAL)
                .map(Runner::getProjectId)
                .orElse(null);
        return agentAuthz.canManageProject(authentication, projectId);
    }

    // ---- PLATFORM 러너 부트스트랩(D-P4-5) ----

    /**
     * 기동 시 env {@code AGENT_PLATFORM_RUNNER_TOKEN}에 PLATFORM 러너 행을 맞춘다 — env가 정본이다. 설정돼 있으면 그 해시의 PLATFORM
     * 행을 만들거나 되살리고 다른 PLATFORM 행은 철회한다(토큰 교체). 비어 있거나 형식이 틀리면 PLATFORM 러너가 없다(전부 철회).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapPlatformRunner() {
        String token = runnerProperties.platformToken() == null ? "" : runnerProperties.platformToken().trim();
        List<Runner> platforms = runnerRepository.findByKind(RunnerKind.PLATFORM);
        Instant now = clock.instant();
        if (token.isEmpty()) {
            platforms.stream().filter(r -> !r.isRevoked()).forEach(r -> {
                r.revoke(now);
                log.info("PLATFORM 러너 철회 — AGENT_PLATFORM_RUNNER_TOKEN 미설정: runner={}", r.getId());
            });
            return;
        }
        if (!token.startsWith(TOKEN_PREFIX) || token.length() < PLATFORM_TOKEN_MIN_LENGTH) {
            log.warn("AGENT_PLATFORM_RUNNER_TOKEN 형식이 아닙니다(agr_ 접두·{}자 이상) — 플랫폼 러너를 두지 않습니다",
                    PLATFORM_TOKEN_MIN_LENGTH);
            platforms.forEach(r -> r.revoke(now));
            return;
        }
        String hash = TokenHashing.sha256Hex(token);
        Optional<Runner> existing = runnerRepository.findByTokenHash(hash);
        Runner platform;
        if (existing.isPresent()) {
            if (existing.get().getKind() != RunnerKind.PLATFORM) {
                log.warn("AGENT_PLATFORM_RUNNER_TOKEN이 LOCAL 러너 토큰과 같습니다 — 플랫폼 러너를 두지 않습니다: runner={}",
                        existing.get().getId());
                platforms.forEach(r -> r.revoke(now));
                return;
            }
            platform = existing.get();
            platform.reactivate();
        } else {
            platform = runnerRepository.save(Runner.platform(hash, TokenHashing.displayPrefix(token)));
        }
        long platformId = platform.getId();
        platforms.stream().filter(r -> r.getId() != platformId && !r.isRevoked()).forEach(r -> r.revoke(now));
        log.info("PLATFORM 러너 준비: runner={} prefix={}", platformId, platform.getTokenPrefix());
    }

    // ---- 내부 ----

    private String mcpUrl() {
        String url = runnerProperties.publicMcpUrl();
        return url == null || url.isBlank() ? workerProperties.mcpUrl() : url.trim();
    }

    private Runner loadActive(RunnerPrincipal principal) {
        Runner runner = runnerRepository.findById(principal.runnerId())
                .orElseThrow(() -> new ForbiddenException("러너를 찾을 수 없습니다"));
        if (runner.isRevoked()) {
            throw new ForbiddenException("철회된 러너입니다");
        }
        return runner;
    }

    /** claim·결과 보고도 살아 있다는 신호 — heartbeat 사이에 오는 호출로 오프라인 판정을 늦춘다. */
    private Runner touch(RunnerPrincipal principal) {
        Runner runner = loadActive(principal);
        runner.touch(clock.instant());
        return runnerRepository.save(runner);
    }

    private void revokeRunToken(Run run) {
        Long patId = run.getPatId();
        if (patId == null) {
            return;
        }
        safelyRevoke(patId);
        Run fresh = runRepository.findById(run.getId()).orElse(null);
        if (fresh != null && fresh.getPatId() != null) {
            fresh.clearRunToken();
            runRepository.save(fresh);
        }
    }

    private void safelyRevoke(long patId) {
        try {
            runTokenService.revoke(patId);
        } catch (Exception e) {
            log.warn("run 토큰 철회 실패(pat={}) — 다음 생존 판정에서 다시 시도합니다: {}", patId, e.getMessage());
        }
    }

    /** 러너가 보낸 커밋 링크 — 이슈 키 형식이 아니거나 http(s) URL이 아닌 항목은 버린다(URL 없는 항목은 서버 파서와 같이 url=null). */
    private static List<CommitLinkParser.CommitLink> commitsOf(RunnerResultRequest request) {
        if (request.commits() == null) {
            return List.of();
        }
        List<CommitLinkParser.CommitLink> links = new ArrayList<>();
        for (RunnerResultRequest.CommitReport c : request.commits()) {
            if (c == null || c.issueKey() == null || !ISSUE_KEY.matcher(c.issueKey()).matches()) {
                continue;
            }
            String url = c.url() != null && (c.url().startsWith("https://") || c.url().startsWith("http://")) ? c.url() : null;
            links.add(new CommitLinkParser.CommitLink(c.issueKey(), c.sha(), c.subject(), url));
        }
        return links;
    }

    private static long nonNegative(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
