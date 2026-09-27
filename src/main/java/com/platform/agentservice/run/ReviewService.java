package com.platform.agentservice.run;

import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.common.error.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 리뷰 run 상시화(P2c T3, AGP-44 — 스펙 §10.4-4 "검증 없는 확정 없음") — TASK DONE 뒤 REVIEW run을 띄우고,
 * REVIEW 반려 뒤 원 페르소나의 반려-fix run을 띄운다.
 *
 * <p><b>훅 위치</b>: 두 메서드 모두 {@link RunService}의 결과 반영 단계에서만 부른다 — 워커가 MCP
 * {@code report_result}로 스스로 종결한 경우와 프로세스 성공으로 종결한 경우가 합류하는 지점이다.
 * {@code report_result} 도구 쪽에서 부르지 않는 이유: 그 시점엔 워커 프로세스가 아직 살아 있어 같은
 * 워크스페이스를 두 프로세스가 동시에 쓰게 되고, 워크스페이스 실경로도 프로세스 종료 후에야 기록돼
 * ("pending") 리뷰가 항상 fail-closed로 떨어진다. 반려(FAILED)는 합류점이 이미 재시도 판단을 하므로
 * 도구 쪽에서 또 처리하면 반려-fix와 재시도 continuation이 이중으로 생긴다.
 *
 * <p><b>순환 의존</b>: {@link RunService}가 이 서비스를 호출하고, 이 서비스는 새 run 실행을
 * {@link RunService#execute}(@Async)로 제출해야 한다. 생성자 주입끼리는 순환이라 스프링이 거부하고,
 * 실행 제출을 {@link RunService} 안으로 되돌리면 자기호출이라 @Async 프록시를 건너뛴다(워커 스레드가
 * 다음 run 종료까지 블로킹). 그래서 이쪽이 {@link ObjectProvider}로 실행 시점에 프록시를 지연 조회한다.
 */
@Slf4j
@Service
public class ReviewService {

    private final RunRepository runRepository;
    private final PersonaRepository personaRepository;
    private final TokenService tokenService;
    private final AlmClient almClient;
    private final ReviewProperties reviewProperties;
    private final SchedulerProperties schedulerProperties;
    private final MeetingService meetingService;
    private final ObjectProvider<RunService> runServiceProvider;

    public ReviewService(RunRepository runRepository, PersonaRepository personaRepository, TokenService tokenService,
                         AlmClient almClient, ReviewProperties reviewProperties, SchedulerProperties schedulerProperties,
                         MeetingService meetingService, ObjectProvider<RunService> runServiceProvider) {
        this.runRepository = runRepository;
        this.personaRepository = personaRepository;
        this.tokenService = tokenService;
        this.almClient = almClient;
        this.reviewProperties = reviewProperties;
        this.schedulerProperties = schedulerProperties;
        this.meetingService = meetingService;
        this.runServiceProvider = runServiceProvider;
    }

    /**
     * DONE으로 종결된 run의 후처리. REVIEW의 DONE은 새 REVIEW를 낳지 않는다(D-P2c-4). 리뷰를 띄울 수 없는
     * 설정 결함이면 run은 DONE으로 두고 이슈만 미확정(inprogress)으로 남겨 경고한다(D-P2c-3).
     */
    public void onRunDone(Run run) {
        if (run.getType() != RunType.TASK || !reviewProperties.enabled()) {
            return;
        }
        if (!isUsableWorkspace(run.getWorkspacePath())) {
            warnUnverified(run, "워커가 워크스페이스 경로를 남기지 않았습니다");
            return;
        }
        String slug = reviewProperties.personaSlug();
        if (slug == null || slug.isBlank()) {
            warnUnverified(run, "리뷰어 페르소나가 설정되지 않았습니다(platform.agent.review.persona-slug)");
            return;
        }
        Optional<Persona> reviewer = personaRepository.findBySlug(slug.trim()).filter(Persona::isActive);
        if (reviewer.isEmpty()) {
            warnUnverified(run, "리뷰어 페르소나를 찾을 수 없거나 비활성입니다: " + slug.trim());
            return;
        }
        if (reviewer.get().getId().equals(run.getPersonaId())) {
            // 작업자가 자기 결과를 승인하면 검증이 아니다 — RunType 정의("다른 페르소나가 검증")를 강제한다.
            warnUnverified(run, "리뷰어가 작업자와 같은 페르소나입니다: " + slug.trim());
            return;
        }

        Run review = runRepository.save(Run.queuedReview(run, reviewer.get().getId(), reviewModel(run)));
        log.info("TASK run={} 완료 → 검증 run={} 생성(리뷰어={})", run.getId(), review.getId(), reviewer.get().getSlug());
        submit(review.getId());
        commentBestEffort(run.getPersonaId(), run.getIssueKey(), "🔍 검증 run " + review.getId() + " 시작 — 리뷰어 "
                + reviewer.get().getName() + "가 확인 후 통과 시 done 처리합니다.");
    }

    /**
     * 리뷰어가 {@code report_result(FAILED)}로 반려한 REVIEW run의 후처리 — 원 TASK run을 이어 같은 워크스페이스에서
     * 원 페르소나가 지적을 고치는 fix run을 띄운다(D-P2c-2). 지적 코멘트는 fix run 프롬프트의 최근 코멘트로 유입된다.
     * 이어갈 수 없으면(한도 소진·원 run 소실·워크스페이스 없음) 반려 run을 BLOCKED로 올려 사람에게 넘긴다 — 이슈는
     * inprogress에 남는다.
     */
    public void onReviewRejected(Run reviewRun) {
        if (reviewRun.getType() != RunType.REVIEW) {
            return;
        }
        Run task = reviewRun.getParentRunId() == null ? null
                : runRepository.findById(reviewRun.getParentRunId()).orElse(null);
        if (task == null || task.getType() != RunType.TASK || task.getStatus() != RunStatus.DONE) {
            blockRejected(reviewRun, "반려됐지만 이어갈 원 작업 run을 찾을 수 없음 — 사람 확인 필요");
            return;
        }
        if (!isUsableWorkspace(task.getWorkspacePath())) {
            blockRejected(reviewRun, "반려됐지만 원 작업 워크스페이스가 없음 — 사람 확인 필요");
            return;
        }
        int maxAttempts = schedulerProperties.retryMaxAttempts();
        int nextAttempt = task.getAttempt() + 1;
        if (nextAttempt > maxAttempts) {
            String reason = "리뷰 반려 — 시도 한도 " + maxAttempts + "회 소진, 사람 확인 필요";
            blockRejected(reviewRun, reason);
            safelyEscalate(reviewRun, reason);
            return;
        }

        Run fix = runRepository.save(Run.fixContinuation(task, reviewRun.getId()));
        log.info("검증 run={} 반려 → 수정 run={} 생성(원 run={}, attempt {}/{})",
                reviewRun.getId(), fix.getId(), task.getId(), fix.getAttempt(), maxAttempts);
        submit(fix.getId());
        commentBestEffort(reviewRun.getPersonaId(), reviewRun.getIssueKey(), "🔁 리뷰 반려 — 수정 run " + fix.getId()
                + " 시작(" + fix.getAttempt() + "/" + maxAttempts + "), 위 지적사항을 반영합니다.");
    }

    // ---- 내부 ----

    static boolean isUsableWorkspace(String workspacePath) {
        return workspacePath != null && !workspacePath.isBlank() && !RunService.PENDING_WORKSPACE.equals(workspacePath);
    }

    /** D-P2c-5: 리뷰 전용 모델 > 프로젝트별 > 전역 > 워커 기본. REVIEW는 시스템이 띄우는 run이라 USER 지정 단계가 없다. */
    private String reviewModel(Run task) {
        String model = reviewProperties.model();
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        return schedulerProperties.modelFor(RunService.projectKeyOf(task.getIssueKey()));
    }

    /**
     * run은 이미 QUEUED로 커밋됐다 — 스레드풀 포화로 제출이 거부돼도 여기서 삼킨다. 새면 호출자가 "생성 실패"로
     * 잘못 기록하고 안내 코멘트도 빠지며, 반려 경로에서는 반려 run이 "FAILED로 남아 사람 재개 대상"으로 보이는데
     * fix run(QUEUED)도 이미 있어 이중 상태가 된다.
     */
    private void submit(long runId) {
        try {
            runServiceProvider.getObject().execute(runId);
        } catch (TaskRejectedException e) {
            log.warn("run 실행 제출이 거부돼 QUEUED로 남깁니다 — 스케줄러가 켜져 있으면 드레인 틱이 집어갑니다: id={} error={}",
                    runId, e.getMessage());
        }
    }

    private void warnUnverified(Run run, String reason) {
        log.warn("TASK run={} 검증 run을 만들 수 없음 — 이슈 {}는 미확정으로 남습니다: {}", run.getId(), run.getIssueKey(), reason);
        commentBestEffort(run.getPersonaId(), run.getIssueKey(),
                "⚠️ 검증 run을 만들 수 없음(" + reason + ") — 이슈는 미확정(inprogress)으로 남습니다. 사람이 확인하세요.");
    }

    /** FAILED → BLOCKED는 합법 전이다. BLOCKED는 활성 상태라 같은 이슈가 사람 확인 전에 재픽업되지 않는다. */
    private void blockRejected(Run reviewRun, String reason) {
        log.warn("검증 run={} 반려 후속 불가: {}", reviewRun.getId(), reason);
        reviewRun.block(reason);
        runRepository.save(reviewRun);
        commentBestEffort(reviewRun.getPersonaId(), reviewRun.getIssueKey(), "⛔ " + reason);
    }

    /** 반려 run의 BLOCKED는 이미 커밋됐다 — 자동 에스컬레이션(D-P3b-4③) 실패는 삼킨다. */
    private void safelyEscalate(Run blockedRun, String reason) {
        try {
            meetingService.onBlocked(blockedRun, reason);
        } catch (Exception e) {
            log.warn("검증 run={} 자동 에스컬레이션 생성 실패 — BLOCKED로 남아 사람 재개 대상입니다: {}", blockedRun.getId(), e.getMessage());
        }
    }

    private void commentBestEffort(long personaId, String issueKey, String body) {
        try {
            Persona persona = personaRepository.findById(personaId)
                    .orElseThrow(() -> new NotFoundException("페르소나를 찾을 수 없습니다: " + personaId));
            String bearer = tokenService.bearerFor(persona.getMemberId());
            IssueResponse issue = almClient.getByKey(issueKey, bearer);
            almClient.addComment(issue.id(), body, bearer);
        } catch (Exception e) {
            log.warn("검증 흐름 이슈 코멘트 기록 실패(상태는 저장됨): issueKey={} : {}", issueKey, e.getMessage());
        }
    }
}
