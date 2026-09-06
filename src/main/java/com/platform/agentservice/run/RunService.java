package com.platform.agentservice.run;

import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.IssueClaimSupport;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.budget.BudgetProperties;
import com.platform.agentservice.budget.LedgerScope;
import com.platform.agentservice.budget.UsageLedger;
import com.platform.agentservice.budget.UsageLedgerRepository;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.run.dto.RunSummaryResponse;
import com.platform.agentservice.worker.CommitLinkParser;
import com.platform.agentservice.worker.WorkerJob;
import com.platform.agentservice.worker.WorkerLauncher;
import com.platform.agentservice.worker.WorkerProperties;
import com.platform.agentservice.worker.WorkerResult;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * run 생성·실행·종결 오케스트레이션(P2a T4) — {@link Dispatcher}가 소비하고, T5(예산·게이트
 * 승인 재큐)가 이 위에 더 얹는다. {@link #execute}는 {@code @Async}로 워커 실행 전체를
 * 백그라운드 스레드에 태운다 — {@link WorkerLauncher#launch}가 프로세스 실행 동안(최대
 * {@code timeoutMinutes}) 블로킹되므로, 이 메서드 안에서 하나의 긴 DB 트랜잭션을 걸지
 * 않는다: 각 상태 전이는 {@code findById → 엔티티 메서드 → save}로 짧게 끊어서 커밋하고,
 * 그 사이(워커 실행 중)에 {@code report_result}/{@code request_gate} 같은 다른 요청
 * 스레드가 같은 run을 스스로 종결할 수 있다는 것을 전제로 한다({@link #applyOutcome}이
 * 재조회해서 상태가 이미 RUNNING이 아니면 존중하고 물러난다).
 *
 * <p>워크스페이스/PAT 발급은 {@link WorkerLauncher}가 스스로 처리한다(T3 규칙 — launch()는
 * run 상태를 건드리지 않는다). 그래서 이 서비스는 launch 호출 전에 {@code
 * workspacePath="pending"}, {@code patId=null}로 먼저 RUNNING 전이를 커밋해 둔다(T4
 * 브리핑 확정 결정) — 실제 workspacePath는 어차피 로컬 파일시스템 경로일 뿐 이 서비스가
 * 이후 참조하지 않는다.
 *
 * <p><b>T6b 추가</b>: 위 전제가 완전히는 맞지 않게 됐다 — {@link #linkCommits}가 커밋
 * 파서로 워크스페이스의 git 로그를 훑어 이슈에 COMMIT 웹링크를 남기려면 실제 경로가
 * 필요하다. DB 컬럼({@code Run.workspacePath})은 여전히 갱신하지 않고(엔티티 변경 없음),
 * {@link WorkerResult#workspacePath()}가 대신 {@link WorkerLauncher#launch}가 실제로 만든
 * 경로를 실어 나른다 — {@link #applyOutcome}에서만 쓰고 버린다.
 */
@Slf4j
@Service
public class RunService {

    /**
     * {@link Run#existsByIssueKeyAndStatusIn} / {@link Dispatcher#pickNewIssue}의 "이미
     * 진행 중이라 재선택하면 안 되는" 상태 — WAITING_APPROVAL(사람 승인 대기)뿐 아니라
     * {@code BLOCKED}도 포함한다(fix round, P2a T7, F2b). BLOCKED는 재시도 한도를 다 쓰고
     * 사람 확인이 필요한 상태라 "끝난 것"이 아니다 — 여기 빠져 있으면 다음 Dispatcher 틱이
     * 사람이 보기도 전에 같은 이슈를 attempt=1부터 완전히 새로 픽업해 버린다(task-7 E2E
     * 실측: BLOCKED 승격이 안 되던 버그와 겹쳐서 15개 run에 걸쳐 1→2→3→(재시작)1→2→3...
     * 패턴이 5회 반복됨 — F2a로 BLOCKED 승격 자체를 고쳤어도 이 가드가 없으면 여전히
     * 재선택됐을 것이다). BLOCKED에서 벗어나는 유일한 합법 경로는 {@link Run#continuation}
     * (사람이 게이트를 승인하는 재개 흐름, {@link GateService} 참고)이며, 그 경로는 이
     * 집합을 거치지 않고 직접 continuation을 만들기 때문에 이 가드와 충돌하지 않는다.
     */
    public static final Set<RunStatus> ACTIVE_STATUSES =
            EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.WAITING_APPROVAL, RunStatus.BLOCKED);

    private static final String PENDING_WORKSPACE = "pending";
    /** {@code harnessRef}는 현재 전역 하네스 번들 하나뿐이다(T3) — run별 선택지가 없어 상수로 둔다. */
    private static final String DEFAULT_HARNESS_REF = "harness://default";
    private static final String CLAIM_STATUS = "inprogress";
    private static final int RECENT_COMMENTS_LIMIT = 10;
    private static final int SUMMARY_MAX_LENGTH = 500;

    private final RunRepository runRepository;
    private final AlmClient almClient;
    private final IssueClaimSupport issueClaimSupport;
    private final TokenService tokenService;
    private final PersonaRepository personaRepository;
    private final WorkerLauncher workerLauncher;
    private final WorkerProperties workerProperties;
    private final UsageLedgerRepository usageLedgerRepository;
    private final SchedulerProperties schedulerProperties;
    private final BudgetProperties budgetProperties;
    private final CommitLinkParser commitLinkParser;
    private final BudgetGuard budgetGuard;

    public RunService(RunRepository runRepository, AlmClient almClient, IssueClaimSupport issueClaimSupport,
                       TokenService tokenService, PersonaRepository personaRepository, WorkerLauncher workerLauncher,
                       WorkerProperties workerProperties, UsageLedgerRepository usageLedgerRepository,
                       SchedulerProperties schedulerProperties, BudgetProperties budgetProperties,
                       CommitLinkParser commitLinkParser, BudgetGuard budgetGuard) {
        this.runRepository = runRepository;
        this.almClient = almClient;
        this.issueClaimSupport = issueClaimSupport;
        this.tokenService = tokenService;
        this.personaRepository = personaRepository;
        this.workerLauncher = workerLauncher;
        this.workerProperties = workerProperties;
        this.usageLedgerRepository = usageLedgerRepository;
        this.schedulerProperties = schedulerProperties;
        this.budgetProperties = budgetProperties;
        this.commitLinkParser = commitLinkParser;
        this.budgetGuard = budgetGuard;
    }

    /** Dispatcher가 새 이슈를 픽업할 때 넘기는 최소 참조. */
    public record IssueRef(String issueKey, long projectId, long personaId) {
    }

    /** 같은 이슈에 이미 활성(QUEUED/RUNNING/WAITING_APPROVAL) run이 있으면 409로 거부한다. */
    public Run createQueuedForIssue(IssueRef issue, RunTrigger trigger, String model) {
        if (runRepository.existsByIssueKeyAndStatusIn(issue.issueKey(), ACTIVE_STATUSES)) {
            throw new ConflictException("이미 진행 중인 run이 있습니다: " + issue.issueKey());
        }
        Run run = Run.queued(RunType.TASK, issue.issueKey(), issue.projectId(), issue.personaId(),
                trigger, DEFAULT_HARNESS_REF, model);
        return runRepository.save(run);
    }

    /**
     * QUEUED run 하나를 워커로 실행한다. 워커 준비(리포 매핑 해석·이슈 claim) 실패 시
     * RUNNING을 거쳐 곧바로 실패 처리한다 — {@link Run#fail}이 RUNNING에서만 허용되므로
     * 상태 규약을 지키려면 실패도 RUNNING을 통과해야 한다.
     *
     * <p><b>킬 스위치·예산 캡 전면화(최종 리뷰 I3)</b>: {@link BudgetGuard#allow}는 원래
     * {@link Dispatcher#pickNewIssue}에서만 확인해 "새 이슈 픽업"만 막았다 — 그런데 QUEUED
     * continuation의 드레인({@link Dispatcher#drainQueued}), 게이트 승인({@link
     * GateService#approve}), BLOCKED/FAILED 사람 재개({@link RunResumeService#resume})는
     * 전부 이 {@link #execute}를 직접 호출해서 킬 스위치를 그냥 지나쳐 버렸다(문서
     * {@code CLAUDE.md} §5.2가 "즉시 전체 차단"이라고 약속한 것과 실제가 달랐다). 그래서
     * 진입점 자체(여기)에서 한 번 더 확인한다 — 거부되면 QUEUED로 그대로 남겨 둔다(RUNNING
     * 전이 자체를 하지 않는다). run을 실패 처리하지 않는 이유: 킬 스위치/예산 캡은 일시적
     * 상태이고, 풀리면 다음 Dispatcher 드레인 틱이 이 QUEUED run을 다시 집어 처리하면
     * 되기 때문이다(재시도 카운트 소모 없음).
     */
    @Async("workerExecutor")
    public void execute(long runId) {
        Run run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("run을 찾을 수 없어 실행을 건너뜁니다: id={}", runId);
            return;
        }
        if (run.getStatus() != RunStatus.QUEUED) {
            log.debug("QUEUED 상태가 아니라 실행을 건너뜁니다: id={} status={}", runId, run.getStatus());
            return;
        }
        if (!budgetGuard.allow(run.getProjectId())) {
            log.info("킬 스위치/예산 캡으로 실행을 보류합니다 — QUEUED로 남겨 다음 드레인 틱이 재시도합니다: id={}", runId);
            return;
        }

        // fix round 1 (I1): QUEUED 확인 직후, buildJob()의 네트워크 호출(수십 초 걸릴 수 있다)보다
        // 먼저 RUNNING을 커밋한다 — 그래야 다음 Dispatcher 드레인 틱이 같은 run을 곧바로
        // "QUEUED 아님"으로 보고 재제출하지 않는다. @Version(Run.version)이 그 사이에도 남는
        // 경합 창(findById~save)을 낙관적 락으로 막는 2차 방어망이다.
        run.start(PENDING_WORKSPACE, null);
        runRepository.save(run);

        WorkerJob job;
        try {
            job = buildJob(run);
        } catch (Exception e) {
            finishFailed(runId, describeFailure(e));
            return;
        }

        WorkerResult result;
        try {
            Run runningRun = runRepository.findById(runId).orElseThrow();
            result = workerLauncher.launch(runningRun, job);
        } catch (Exception e) {
            finishFailed(runId, "실행 인프라 오류: " + e.getMessage());
            return;
        }

        applyOutcome(runId, result);
    }

    /** {@code POST /api/agent/runs/{id}/cancel} — 실행 중인 워커 OS 프로세스 강제 종료는 P2a 범위 밖이다. */
    public void cancel(long runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("run을 찾을 수 없습니다: " + runId));
        run.cancel();
        runRepository.save(run);
        commentBestEffort(run, "🛑 관리자 요청으로 취소됨 — 이미 실행 중인 워커 프로세스는 강제 종료되지 않습니다(P2a 범위 밖).");
    }

    /** {@code GET /api/agent/runs?status=} 감독 API — 최소 필드 목록. */
    public List<RunSummaryResponse> list(RunStatus status) {
        List<Run> runs = status != null ? runRepository.findByStatus(status) : runRepository.findAll();
        return runs.stream().map(RunSummaryResponse::of).toList();
    }

    // ---- 내부 ----

    /**
     * 리포 매핑 해석 → 이슈 claim(담당자=페르소나, 상태=inprogress) → 최근 코멘트 조회 순.
     * 리포 매핑이 없으면 ALM을 건드리지 않고 바로 실패시킨다(claim으로 이슈 상태를
     * 바꿔놓고 워커를 못 띄우는 상황을 피한다).
     */
    private WorkerJob buildJob(Run run) {
        String repoUrl = resolveRepoUrl(run.getIssueKey());

        Persona persona = personaRepository.findById(run.getPersonaId())
                .orElseThrow(() -> new NotFoundException("run의 페르소나를 찾을 수 없습니다: " + run.getPersonaId()));
        String bearer = tokenService.bearerFor(persona.getMemberId());

        IssueResponse claimed = issueClaimSupport.claim(run.getIssueKey(), persona.getMemberId(), CLAIM_STATUS, bearer);
        List<CommentResponse> comments = almClient.comments(claimed.id(), bearer);
        List<String> recentComments = comments.stream()
                .skip(Math.max(0, comments.size() - RECENT_COMMENTS_LIMIT))
                .map(CommentResponse::body)
                .toList();

        return new WorkerJob(repoUrl, claimed.title(), claimed.description(), recentComments);
    }

    /** F3(fix round, task-7): 대소문자 무관 조회 — env var 주입 시 Spring relaxed binding이 맵 키를 소문자로 접기 때문. */
    private String resolveRepoUrl(String issueKey) {
        String projectKey = projectKeyOf(issueKey);
        String repoUrl = workerProperties.repoFor(projectKey);
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new MissingRepoMappingException("리포 매핑 없음: " + projectKey);
        }
        return repoUrl;
    }

    private static String projectKeyOf(String issueKey) {
        int dash = issueKey.indexOf('-');
        return dash > 0 ? issueKey.substring(0, dash) : issueKey;
    }

    private String describeFailure(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * 워커 실행 결과를 반영한다. 원장 기록(세션 id·비용)은 run이 이미 다른 경로(워커의
     * MCP {@code report_result}/{@code request_gate})로 종결됐어도 항상 남긴다 — 실제로
     * 토큰이 소모됐다는 사실은 최종 상태와 무관하다. 상태 전이는 run이 여전히 RUNNING일
     * 때만 이 메서드가 결정한다(이미 다른 상태라면 그쪽이 진실의 원천이므로 덮지 않는다).
     *
     * <p><b>self-FAILED 데드엔드 수정(최종 리뷰 I1)</b>: 워커가 launch() 반환 전에 MCP
     * {@code report_result(status=FAILED)}를 스스로 불렀으면 재조회 시 상태가 FAILED다 —
     * 예전에는 "RUNNING 아니면 물러난다" 가드에 걸려 여기서 그냥 반환했고, 그러면 재시도도
     * BLOCKED 승격도 전혀 일어나지 않아 그 run은 FAILED에 영원히 멈췄다(재개 API도 원래
     * BLOCKED만 받아서 막다른 골목이었다 — {@link RunResumeService}도 함께 넓혔다). FAILED는
     * {@code Run}의 차단 가능·이어가기 가능 상태 집합 둘 다에 이미 포함돼 있으므로
     * {@link #handleRetryOrBlock}을 그대로 태울 수 있다.
     *
     * <p><b>방어적 래핑(최종 리뷰 I2)</b>: {@link #recordSessionAndLedger}/{@link
     * #warnIfOverPerRunBudget}는 (DB 순간 장애·낙관적 락 등으로) 던질 수 있는데, 이 메서드는
     * {@code @Async} 진입점({@link #execute}) 안에서 호출되므로 여기서 던진 예외는 스프링
     * 기본 비동기 예외 핸들러가 조용히 삼킨다 — run이 RUNNING에 발이 묶이고 원장도 못 쓴
     * 채로 끝난다. {@link #linkCommits}처럼(이미 자체 try/catch) 각 단계를 개별적으로
     * log-and-continue로 감싸고, 이 메서드 전체도 바깥 try/catch로 감싸 그래도 뭔가 새면
     * {@link #finishFailed}로 대체 시도한다(그 폴백조차 실패하면 로그만 남기고 포기 —
     * 더는 이 메서드가 할 수 있는 게 없다).
     */
    private void applyOutcome(long runId, WorkerResult result) {
        try {
            safelyRecordSessionAndLedger(runId, result);
            linkCommits(runId, result);
            safelyWarnIfOverPerRunBudget(runId, result);

            Run run = runRepository.findById(runId).orElseThrow();
            if (run.getStatus() == RunStatus.FAILED) {
                log.debug("워커가 스스로 report_result(FAILED)로 종결했습니다(run={}) — 재시도/차단 판단을 이어갑니다.", runId);
                handleRetryOrBlock(run);
                return;
            }
            if (run.getStatus() != RunStatus.RUNNING) {
                log.debug("워커가 이미 스스로 종결했습니다(run={}, status={}) — 재전이하지 않습니다.", runId, run.getStatus());
                return;
            }

            if (result.timedOut()) {
                finishFailed(runId, "시간 초과");
                return;
            }
            if (!result.succeeded()) {
                finishFailed(runId, truncate(result.rawTail()));
                return;
            }

            run.complete();
            runRepository.save(run);
            commentBestEffort(run, "✅ 워커 종료: " + truncate(result.resultText()));
        } catch (Exception e) {
            log.warn("run={} 결과 반영 중 예기치 못한 오류 — 실패 처리로 대체 시도합니다: {}", runId, e.getMessage());
            try {
                finishFailed(runId, "결과 반영 오류: " + e.getMessage());
            } catch (Exception fallbackFailure) {
                log.error("run={} 실패 처리 폴백도 실패했습니다 — run이 멈춰 있을 수 있습니다: {}", runId, fallbackFailure.getMessage());
            }
        }
    }

    private void safelyRecordSessionAndLedger(long runId, WorkerResult result) {
        try {
            recordSessionAndLedger(runId, result);
        } catch (Exception e) {
            log.warn("run={} 세션/원장 기록 실패 — 건너뜁니다: {}", runId, e.getMessage());
        }
    }

    private void safelyWarnIfOverPerRunBudget(long runId, WorkerResult result) {
        try {
            warnIfOverPerRunBudget(runId, result);
        } catch (Exception e) {
            log.warn("run={} 비용 상한 경고 처리 실패 — 건너뜁니다: {}", runId, e.getMessage());
        }
    }

    private void recordSessionAndLedger(long runId, WorkerResult result) {
        Run run = runRepository.findById(runId).orElseThrow();
        if (result.sessionId() != null && !result.sessionId().isBlank()) {
            run.recordSession(result.sessionId());
            runRepository.save(run);
        }
        if (result.costUsd() != null && result.costUsd().compareTo(BigDecimal.ZERO) > 0) {
            usageLedgerRepository.save(UsageLedger.of(runId, LedgerScope.PROJECT, String.valueOf(run.getProjectId()),
                    result.costUsd(), result.inputTokens(), result.outputTokens(), result.model()));
            usageLedgerRepository.save(UsageLedger.of(runId, LedgerScope.PLATFORM, "platform",
                    result.costUsd(), result.inputTokens(), result.outputTokens(), result.model()));
        }
    }

    /**
     * 워커 워크스페이스에 남은 로컬 커밋에서 이슈 키를 찾아 COMMIT 웹링크로 등록한다(P2a T6b).
     * {@link #recordSessionAndLedger}와 같은 위치(양쪽 종결 경로 — 워커가 스스로
     * {@code report_result}로 이미 종결한 경우와 여기서 종결하는 경우 — 가 합류하는 지점)에서
     * run 상태와 무관하게 항상 시도한다: 커밋은 run 상태가 무엇이든 이미 워크스페이스에
     * 남아 있고, 실제로 뭔가 커밋됐다는 사실은 최종 상태와 무관하기 때문이다(비용 기록과
     * 같은 논리).
     *
     * <p>워크스페이스 경로가 없으면(클론조차 못 한 초기 실패 등) 조용히 건너뛴다. 그 밖의
     * 전체 몸통(파싱·run/페르소나 조회·bearer 발급·등록 루프)은 바깥쪽 하나의 try/catch로
     * 감싼다(fix round 1, I1) — 이 메서드 전체가 {@link #applyOutcome}에서 {@code
     * commentBestEffort}와 동급의 best-effort 부가 단계이기 때문에, 어느 단계에서 예외가
     * 나도(예: {@code tokenService.bearerFor}가 던지는 경우) run이 RUNNING에 발이 묶이는 일
     * 없이 조용히 삼켜야 한다. 이슈 키 하나하나는 그 안에서 다시 독립적으로 최선노력이다 —
     * 알 수 없는 키(alm-backend 404 — {@link AlmClient}가 {@code ConflictException}으로
     * 감싼다)나 웹링크 등록 실패가 있어도 나머지 커밋 처리를 막지 않는다. URL을 정규화하지
     * 못한 링크(예: 원격이 없거나 파싱 불가)는 HTTP 호출 자체를 걸지 않고 건너뛴다(fix round
     * 1, I2 — alm-backend가 blank URL을 400으로 거부하는 걸 매번 유발할 필요가 없다).
     * {@code Audited.note} 감사 패턴은 MCP 도구 호출({@link
     * com.platform.agentservice.tools.ToolActor#current()}가 필요) 맥락에서만 쓸 수 있어
     * 여기(백그라운드 워커 스레드, PAT principal 없음)서는 적용할 수 없다 — 기존
     * {@link #commentBestEffort}와 같은 방식으로 로그만 남기고 삼킨다(선택, T6b 브리핑).
     */
    private void linkCommits(long runId, WorkerResult result) {
        String workspacePath = result.workspacePath();
        if (workspacePath == null || workspacePath.isBlank()) {
            return;
        }
        try {
            List<CommitLinkParser.CommitLink> links = commitLinkParser.parse(Path.of(workspacePath));
            if (links.isEmpty()) {
                return;
            }

            Run run = runRepository.findById(runId)
                    .orElseThrow(() -> new NotFoundException("run을 찾을 수 없습니다: " + runId));
            Persona persona = personaRepository.findById(run.getPersonaId())
                    .orElseThrow(() -> new NotFoundException("run의 페르소나를 찾을 수 없습니다: " + run.getPersonaId()));
            String bearer = tokenService.bearerFor(persona.getMemberId());

            for (CommitLinkParser.CommitLink link : links) {
                if (link.url() == null) {
                    log.debug("run={} 커밋({}) 원격 URL을 정규화하지 못해 건너뜁니다(이슈 키={})",
                            runId, link.sha(), link.issueKey());
                    continue;
                }
                try {
                    IssueResponse issue = almClient.getByKey(link.issueKey(), bearer);
                    almClient.addWebLink(issue.id(), link.url(), truncateCommitTitle(link.subject()), "COMMIT", bearer);
                } catch (Exception e) {
                    log.warn("run={} 커밋({}) 웹링크 등록 실패 — 건너뜁니다(이슈 키={}): {}",
                            runId, link.sha(), link.issueKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("run={} 커밋 링크 처리 실패 — 건너뜁니다: {}", runId, e.getMessage());
        }
    }

    private static final int COMMIT_TITLE_MAX_LENGTH = 80;

    private String truncateCommitTitle(String subject) {
        if (subject == null) {
            return null;
        }
        return subject.length() <= COMMIT_TITLE_MAX_LENGTH ? subject : subject.substring(0, COMMIT_TITLE_MAX_LENGTH);
    }

    /**
     * run 한 건의 비용이 {@code platform.agent.budget.per-run-usd-cap}을 넘으면 경고 코멘트만
     * 남긴다(P2a T5) — 이미 종료된 run이라 상태를 되돌리지 않는다. 다음 픽업은 월간 누적
     * 캡({@code BudgetService.allow})이 자연스럽게 막는다.
     */
    private void warnIfOverPerRunBudget(long runId, WorkerResult result) {
        BigDecimal cap = budgetProperties.perRunUsdCap();
        if (result.costUsd() == null || cap == null || result.costUsd().compareTo(cap) <= 0) {
            return;
        }
        Run run = runRepository.findById(runId).orElseThrow();
        commentBestEffort(run, "⚠️ run 비용 상한 초과: $" + result.costUsd() + " > $" + cap);
    }

    /**
     * RUNNING일 때만 FAILED로 옮기고 재시도/차단을 판단한다(이미 다른 상태면 손대지 않는다).
     *
     * <p><b>fix round(P2a T7, F2a)</b>: {@code run = runRepository.save(run)}로 반환값을
     * 반드시 다시 담는다 — {@code save()}는 detached 엔티티를 merge하며, DB에 반영된 최신
     * {@code @Version}을 담은 "새" 인스턴스를 돌려준다(원래 인자 {@code run}의 in-memory
     * version 필드는 그대로 남는다). 이 재할당 없이 그대로 {@code handleRetryOrBlock(run)}에
     * 넘기면, attempt==한도 분기에서 그 stale-version 인스턴스로 {@code block()+save()}를
     * 한 번 더 시도하다 {@code ObjectOptimisticLockingFailureException}을 던진다 — task-7
     * E2E 실측(run id=3)에서 정확히 이 지점에서 터졌고, run이 FAILED에 멈춘 채 BLOCKED로
     * 승격되지 못한 원인이었다(재현: {@code RunServiceOptimisticLockingTest}).
     */
    private void finishFailed(long runId, String error) {
        Run run = runRepository.findById(runId).orElseThrow();
        if (run.getStatus() != RunStatus.RUNNING) {
            return;
        }
        run.fail(error);
        run = runRepository.save(run);
        handleRetryOrBlock(run);
    }

    /**
     * attempt &lt; 한도면 continuation(QUEUED)을 만들어 다음 Dispatcher 틱의 drain이
     * 집어가게 한다(여기서 즉시 실행하지 않는다 — Dispatcher 책임과 분리). 한도에 닿으면
     * BLOCKED로 에스컬레이션한다.
     */
    private void handleRetryOrBlock(Run failedRun) {
        int maxAttempts = schedulerProperties.retryMaxAttempts();
        if (failedRun.getAttempt() < maxAttempts) {
            Run next = runRepository.save(Run.continuation(failedRun));
            commentBestEffort(failedRun, "🔁 재시도 " + next.getAttempt() + "/" + maxAttempts);
        } else {
            String blockReason = maxAttempts + "회 실패 — 사람 확인 필요";
            failedRun.block(blockReason);
            runRepository.save(failedRun);
            commentBestEffort(failedRun, "⛔ " + blockReason);
        }
    }

    /** 상태 전이는 이미 커밋된 뒤의 부가 알림이다 — 실패해도 run 처리 흐름을 막지 않는다. */
    private void commentBestEffort(Run run, String body) {
        try {
            Persona persona = personaRepository.findById(run.getPersonaId())
                    .orElseThrow(() -> new NotFoundException("run의 페르소나를 찾을 수 없습니다: " + run.getPersonaId()));
            String bearer = tokenService.bearerFor(persona.getMemberId());
            IssueResponse issue = almClient.getByKey(run.getIssueKey(), bearer);
            almClient.addComment(issue.id(), body, bearer);
        } catch (Exception e) {
            log.warn("run={} 이슈 코멘트 기록 실패(상태는 저장됨): {}", run.getId(), e.getMessage());
        }
    }

    private String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "(결과 없음)";
        }
        return text.length() <= SUMMARY_MAX_LENGTH ? text : text.substring(0, SUMMARY_MAX_LENGTH);
    }

    /** 리포 매핑이 없을 때만 던지는 내부 전용 예외 — 실패 메시지 구성 목적만 있다. */
    private static final class MissingRepoMappingException extends RuntimeException {
        MissingRepoMappingException(String message) {
            super(message);
        }
    }
}
