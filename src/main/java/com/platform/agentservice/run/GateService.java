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

/**
 * 게이트 승인/거절 오케스트레이션(P2a T5, 스펙 D9) — Gate 결정과 원 run의 전이를 하나의
 * DB 트랜잭션으로 묶는다({@link #approve}/{@link #reject}). 승인은 {@link Run#continuation}의
 * "같은 행을 되돌리지 않고 새 Run을 만든다"는 원칙을 그대로 따른다: continuation을 먼저
 * 만들고(WAITING_APPROVAL에서만 legal하므로 순서가 중요하다), 그다음 원 run을
 * {@link Run#cancelWithNote}로 닫는다 — WAITING_APPROVAL에 그대로 두면 다음 Dispatcher
 * 픽업의 "활성 run 중복" 가드({@code RunService.ACTIVE_STATUSES}에 WAITING_APPROVAL 포함)와
 * 동시성 집계에 계속 걸리기 때문이다.
 *
 * <p>워커 재실행 킥({@code RunService.execute}, {@code @Async})과 ALM 이슈 코멘트는 이
 * 메서드의 DB 트랜잭션이 커밋된 뒤에만 실행한다({@link TransactionSynchronization#afterCommit}).
 * 커밋 전에 실행하면 async 스레드가 아직 보이지 않는(다른 트랜잭션에서 uncommitted) continuation
 * 행을 못 찾을 수 있고, 네트워크 호출(ALM 코멘트)이 DB 커넥션을 트랜잭션 동안 붙들게 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GateService {

    private final GateRepository gateRepository;
    private final RunRepository runRepository;
    private final RunService runService;
    private final PersonaRepository personaRepository;
    private final TokenService tokenService;
    private final AlmClient almClient;

    /** {@code POST /api/agent/gates/{id}/approve} — 새 run(attempt+1)을 큐잉하고 비동기로 실행을 킥한다. */
    @Transactional
    public void approve(long gateId, long deciderId) {
        Gate gate = loadGate(gateId);
        Run run = loadWaitingApprovalRun(gate.getRunId());

        gate.approve(deciderId);
        Run continuationRun = runRepository.save(Run.continuation(run));
        long oldRunId = run.getId();
        long newRunId = continuationRun.getId();
        run.cancelWithNote("게이트 승인 — 후속 run " + newRunId + "로 재개");

        String issueKey = run.getIssueKey();
        long personaId = run.getPersonaId();
        GateKind kind = gate.getKind();
        afterCommit(() -> {
            runService.execute(newRunId);
            commentBestEffort(personaId, issueKey,
                    "▶ 게이트 승인(" + kind + ") — run " + oldRunId + " → " + newRunId + " 재개");
        });
    }

    /** {@code POST /api/agent/gates/{id}/reject} — run을 CANCELLED로 닫는다(재개 없음). */
    @Transactional
    public void reject(long gateId, long deciderId) {
        Gate gate = loadGate(gateId);
        Run run = loadWaitingApprovalRun(gate.getRunId());

        gate.reject(deciderId);
        run.cancelWithNote("게이트 거절(" + gate.getKind() + ")");

        String issueKey = run.getIssueKey();
        long personaId = run.getPersonaId();
        GateKind kind = gate.getKind();
        String request = gate.getRequest();
        afterCommit(() -> commentBestEffort(personaId, issueKey, "⏹ 게이트 거절(" + kind + "): " + request));
    }

    private Gate loadGate(long gateId) {
        return gateRepository.findById(gateId)
                .orElseThrow(() -> new NotFoundException("게이트를 찾을 수 없습니다: " + gateId));
    }

    /** 게이트가 걸린 run이 이미 다른 경로로 종결됐으면(재조회 시 WAITING_APPROVAL이 아니면) 409 — reload 가드. */
    private Run loadWaitingApprovalRun(long runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("run을 찾을 수 없습니다: " + runId));
        if (run.getStatus() != RunStatus.WAITING_APPROVAL) {
            throw new ConflictException("WAITING_APPROVAL 상태가 아닌 run의 게이트는 결정할 수 없습니다(현재: "
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

    /** {@code RunService.commentBestEffort}와 동일한 패턴 — 상태 전이는 이미 커밋됐으므로 코멘트 실패는 경고로만. */
    private void commentBestEffort(long personaId, String issueKey, String body) {
        try {
            Persona persona = personaRepository.findById(personaId)
                    .orElseThrow(() -> new NotFoundException("run의 페르소나를 찾을 수 없습니다: " + personaId));
            String bearer = tokenService.bearerFor(persona.getMemberId());
            IssueResponse issue = almClient.getByKey(issueKey, bearer);
            almClient.addComment(issue.id(), body, bearer);
        } catch (Exception e) {
            log.warn("게이트 결정 이슈 코멘트 기록 실패(상태는 저장됨): issueKey={} : {}", issueKey, e.getMessage());
        }
    }
}
