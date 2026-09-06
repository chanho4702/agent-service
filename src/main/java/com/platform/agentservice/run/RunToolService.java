package com.platform.agentservice.run;

import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code tools/RunTools}가 쓰는 run 상태 전이 오케스트레이션(P2a T2) — 도구 클래스를
 * 얇게 유지하려고 DB 검증·전이·저장만 여기 모은다(ALM 이슈 코멘트 작성 등 네트워크 호출은
 * 도구 쪽 책임). 소유·상태 검증({@link #loadAndAuthorize})은 세 메서드가 공유한다:
 * 호출자 페르소나가 run의 페르소나와 같아야 하고(다르면 {@link ForbiddenException}),
 * run이 요구 상태여야 한다(아니면 {@link ConflictException}).
 *
 * <p>트랜잭션 경계는 메서드 단위다 — {@code Run}/{@code Gate}는 이 트랜잭션 안에서
 * 조회된 관리(managed) 엔티티이므로 필드 변경은 커밋 시 더티체킹으로 자동 반영된다
 * (기존 {@code PatService.revoke} 패턴과 동일 — 명시적 save 호출 불필요, 신규 insert인
 * {@code Gate}만 명시적으로 save한다).
 */
@Service
@RequiredArgsConstructor
public class RunToolService {

    private final RunRepository runRepository;
    private final GateRepository gateRepository;

    public record GateRequestResult(long gateId, String issueKey) {
    }

    public record RunResultOutcome(String issueKey) {
    }

    /** {@code report_progress} 검증 — 상태 변경 없음(읽기 전용). */
    @Transactional(readOnly = true)
    public String requireRunningIssueKey(long runId, long callerPersonaId) {
        return loadAndAuthorize(runId, callerPersonaId, RunStatus.RUNNING).getIssueKey();
    }

    /** {@code request_gate} — Gate 생성 + run→WAITING_APPROVAL. */
    @Transactional
    public GateRequestResult requestGate(long runId, long callerPersonaId, GateKind kind, String request) {
        Run run = loadAndAuthorize(runId, callerPersonaId, RunStatus.RUNNING);
        Gate gate = gateRepository.save(Gate.request(runId, kind, request));
        run.parkForApproval();
        return new GateRequestResult(gate.getId(), run.getIssueKey());
    }

    /** {@code report_result} status=DONE. */
    @Transactional
    public RunResultOutcome completeRun(long runId, long callerPersonaId) {
        Run run = loadAndAuthorize(runId, callerPersonaId, RunStatus.RUNNING);
        run.complete();
        return new RunResultOutcome(run.getIssueKey());
    }

    /** {@code report_result} status=FAILED. */
    @Transactional
    public RunResultOutcome failRun(long runId, long callerPersonaId, String error) {
        Run run = loadAndAuthorize(runId, callerPersonaId, RunStatus.RUNNING);
        run.fail(error);
        return new RunResultOutcome(run.getIssueKey());
    }

    /** {@code report_result} status=BLOCKED. */
    @Transactional
    public RunResultOutcome blockRun(long runId, long callerPersonaId, String error) {
        Run run = loadAndAuthorize(runId, callerPersonaId, RunStatus.RUNNING);
        run.block(error);
        return new RunResultOutcome(run.getIssueKey());
    }

    private Run loadAndAuthorize(long runId, long callerPersonaId, RunStatus requiredStatus) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("run을 찾을 수 없습니다: " + runId));
        if (run.getPersonaId() != callerPersonaId) {
            throw new ForbiddenException("이 run은 호출자의 페르소나가 아닙니다: run=" + runId);
        }
        if (run.getStatus() != requiredStatus) {
            throw new ConflictException(
                    requiredStatus + " 상태에서만 호출할 수 있습니다(현재: " + run.getStatus() + "): run=" + runId);
        }
        return run;
    }
}
