package com.platform.agentservice.run;

import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumSet;
import java.util.Set;

/**
 * BLOCKED·FAILED run을 사람이 확인한 뒤 재개하는 오케스트레이션(fix round 2, P2a T7 재리뷰;
 * 최종 리뷰 I1에서 FAILED까지 확장) — {@link GateService#approve}와 정확히 같은 패턴을 그대로
 * 미러링한다: continuation을 먼저 만들고({@link #RESUMABLE}에서만 legal하므로 순서가
 * 중요하다), 그다음 원 run을 {@link Run#cancelWithNote}로 닫는다 — 그대로 두면 BLOCKED는
 * {@code RunService.ACTIVE_STATUSES}에 포함돼 있어(fix round 1, F2b) 다음 Dispatcher 픽업의
 * "활성 run 중복" 가드와 동시성 집계에 계속 걸리기 때문이다.
 *
 * <p><b>FAILED도 재개 대상인 이유(최종 리뷰 I1)</b>: 정상 경로에서는 워커가 스스로
 * {@code report_result(FAILED)}로 종결해도 {@code RunService.applyOutcome}이 곧바로 재시도
 * continuation이나 BLOCKED로 옮기므로 FAILED에 오래 머물지 않는다. 하지만 그 처리 자체가
 * 예외로 실패하는 잔여 케이스(예: DB 순간 장애)에서는 run이 FAILED에 멈출 수 있다 — 그때
 * 사람이 여전히 손 놓지 않도록 이 경로도 FAILED를 받아들인다.
 *
 * <p>이 재개는 게이트(사람 승인 요청) 없이 이뤄진다 — BLOCKED/FAILED는 시스템이 스스로 만든
 * 정지 상태이지 사람에게 승인을 요청한 게 아니므로, {@link Gate} 엔티티를 만들지 않는다
 * (게이트 승인/거절과는 별개 경로).
 *
 * <p>워커 재실행 킥({@code RunService.execute}, {@code @Async})과 ALM 이슈 코멘트는 이
 * 메서드의 DB 트랜잭션이 커밋된 뒤에만 실행한다({@link GateService}와 동일 이유 —
 * uncommitted continuation 행을 async 스레드가 못 찾는 문제, DB 커넥션을 네트워크 호출
 * 동안 붙드는 문제를 피한다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunResumeService {

    /** 사람이 수동 재개할 수 있는 상태(최종 리뷰 I1 — 원래 BLOCKED만이었다). */
    private static final Set<RunStatus> RESUMABLE = EnumSet.of(RunStatus.BLOCKED, RunStatus.FAILED);

    private final RunRepository runRepository;
    private final RunService runService;
    private final PersonaRepository personaRepository;
    private final TokenService tokenService;
    private final AlmClient almClient;

    /** {@code POST /api/agent/runs/{id}/resume} — 새 run(attempt+1)을 큐잉하고 비동기로 실행을 킥한다. */
    @Transactional
    public void resume(long runId) {
        Run run = loadResumableRun(runId);

        Run continuationRun = runRepository.save(Run.continuation(run));
        long oldRunId = run.getId();
        long newRunId = continuationRun.getId();
        run.cancelWithNote("사람 확인 후 재개 — 후속 run " + newRunId + "로 재개");

        String issueKey = run.getIssueKey();
        long personaId = run.getPersonaId();
        afterCommit(() -> {
            runService.execute(newRunId);
            commentBestEffort(personaId, issueKey, "▶ 사람 확인 후 재개 — run " + oldRunId + " → " + newRunId);
        });
    }

    /** 재조회 시 BLOCKED·FAILED가 아니면(예: 이미 다른 경로로 재개/취소됨) 409 — reload 가드. */
    private Run loadResumableRun(long runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("run을 찾을 수 없습니다: " + runId));
        if (!RESUMABLE.contains(run.getStatus())) {
            throw new ConflictException("BLOCKED 또는 FAILED 상태가 아닌 run은 재개할 수 없습니다(현재: "
                    + run.getStatus() + "): run=" + runId);
        }
        return run;
    }

    /** 활성 트랜잭션이 있으면 커밋 후로 미루고, 없으면(예: 테스트에서 트랜잭션 없이 직접 호출) 즉시 실행한다. */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** {@code RunService.commentBestEffort}/{@code GateService.commentBestEffort}와 동일 패턴 — 상태 전이는 이미 커밋됐으므로 코멘트 실패는 경고로만. */
    private void commentBestEffort(long personaId, String issueKey, String body) {
        try {
            Persona persona = personaRepository.findById(personaId)
                    .orElseThrow(() -> new NotFoundException("run의 페르소나를 찾을 수 없습니다: " + personaId));
            String bearer = tokenService.bearerFor(persona.getMemberId());
            IssueResponse issue = almClient.getByKey(issueKey, bearer);
            almClient.addComment(issue.id(), body, bearer);
        } catch (Exception e) {
            log.warn("run 재개 이슈 코멘트 기록 실패(상태는 저장됨): issueKey={} : {}", issueKey, e.getMessage());
        }
    }
}
