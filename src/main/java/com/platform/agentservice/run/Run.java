package com.platform.agentservice.run;

import com.platform.common.error.ConflictException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * 에이전트 실행 한 건 — 스펙 D9·§10.5. QUEUED에서 시작해 RUNNING을 거쳐 종단(DONE/CANCELLED)
 * 또는 일시정지·재시도 대기(WAITING_APPROVAL/BLOCKED/FAILED)로 간다. 이 재시도 대기 상태들을
 * 다시 진행시키는 것은 같은 행을 되돌리는 게 아니라 {@link #continuation(Run)}으로 attempt를
 * 올린 새 Run을 만드는 것이다(감사 추적 보존) — FAILED에서의 재시도도 continuation이다.
 */
@Entity
@Table(name = "run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Run {

    /**
     * 취소 가능 상태 — 취소는 "종단이 아닌 run을 닫는다"는 뜻이다.
     * {@code BLOCKED}(fix round 2, P2a T7 재리뷰: 재시도 한도 소진, 사람 확인 대기)와
     * {@code FAILED}(최종 리뷰 I1: 워커가 스스로 {@code report_result(FAILED)}로 종결한
     * 직후 {@code RunService.handleRetryOrBlock}이 곧바로 QUEUED-continuation이나 BLOCKED로
     * 옮기지만, 그 처리 자체가 예외로 실패하는 잔여 케이스에서는 FAILED에 멈출 수 있다 —
     * 그때도 사람이 손 놓지 않도록 닫을 수 있어야 한다) 둘 다 종단이 아니다.
     * {@link RunResumeService#resume}이 BLOCKED·FAILED 둘 다 사람 확인 후 재개할 때
     * continuation을 먼저 만들고 원 run을 {@link #cancelWithNote}로 닫는 데 쓴다
     * ({@link com.platform.agentservice.run.GateService#approve}와 동일 패턴).
     */
    private static final Set<RunStatus> CANCELLABLE = EnumSet.of(
            RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.WAITING_APPROVAL, RunStatus.BLOCKED, RunStatus.FAILED);
    private static final Set<RunStatus> BLOCKABLE = EnumSet.of(RunStatus.RUNNING, RunStatus.FAILED);
    private static final Set<RunStatus> CONTINUABLE = EnumSet.of(RunStatus.WAITING_APPROVAL, RunStatus.BLOCKED, RunStatus.FAILED);

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private RunType type;
    @Column(nullable = false, length = 40) private String issueKey;
    @Column(nullable = false) private Long projectId;
    @Column(nullable = false) private Long personaId;
    @Enumerated(EnumType.STRING) @Column(name = "trigger", nullable = false, length = 20) private RunTrigger trigger;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private RunStatus status;
    @Column(length = 200) private String harnessRef;
    @Column(length = 400) private String workspacePath;
    @Column(length = 80) private String sessionId;
    private Long patId;
    /** 워커가 {@code claude -p --model}에 넘길 페르소나별 모델 지정. 미지정 시 워커 기본값을 쓴다. */
    @Column(length = 60) private String model;
    @Column(nullable = false) private int attempt = 1;
    @Column(columnDefinition = "text") private String error;
    /** USER 트리거 run의 사람 지시문 — 재시도 continuation·반려-fix continuation까지 승계된다(지시 맥락을 잃지 않게). */
    @Column(columnDefinition = "text") private String instruction;
    /** REVIEW run → 검증 대상 TASK run, 반려-fix continuation → 반려한 REVIEW run. 재시도 continuation에는 비워 둔다. */
    private Long parentRunId;
    private Instant startedAt;
    private Instant endedAt;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(nullable = false) private Instant updatedAt;
    /**
     * 낙관적 락(P2a T4 fix round 1, I1) — Dispatcher의 드레인 틱과 execute()의 상태 전이가
     * 같은 run 행을 동시에 갱신하려 하면 여기서 {@code OptimisticLockException}으로 걸린다.
     * {@code execute()}가 QUEUED 확인 직후 최대한 빨리 RUNNING을 커밋하는 것이 1차 방어고,
     * 이 컬럼은 그 사이에도 남는 경합 창을 막는 2차 방어망이다.
     */
    @Version @Column(nullable = false) private long version;

    public static Run queued(RunType type, String issueKey, long projectId, long personaId, RunTrigger trigger,
                              String harnessRef, String model) {
        Run r = new Run();
        r.type = type;
        r.issueKey = issueKey;
        r.projectId = projectId;
        r.personaId = personaId;
        r.trigger = trigger;
        r.harnessRef = harnessRef;
        r.model = model;
        r.status = RunStatus.QUEUED;
        r.attempt = 1;
        return r;
    }

    /**
     * 게이트 승인/차단 해제 또는 실패 재시도 후 "재개"는 같은 행을 되돌리지 않고 새 Run을 만든다
     * (스펙 D9) — WAITING_APPROVAL·BLOCKED·FAILED에서만 이어갈 수 있다. FAILED에서의 재시도도
     * continuation이다(컨트롤러 판정 I1) — 별도 retry() 메서드를 두지 않는다.
     */
    public static Run continuation(Run prior) {
        if (!CONTINUABLE.contains(prior.status)) {
            throw new ConflictException("WAITING_APPROVAL·BLOCKED·FAILED 상태에서만 이어갈 수 있습니다: " + prior.status);
        }
        Run r = new Run();
        r.type = prior.type;
        r.issueKey = prior.issueKey;
        r.projectId = prior.projectId;
        r.personaId = prior.personaId;
        r.trigger = prior.trigger;
        r.harnessRef = prior.harnessRef;
        r.model = prior.model;
        // 재시도·게이트 승인·사람 재개도 같은 USER 요청의 연장이라 지시문을 잃으면 안 된다.
        r.instruction = prior.instruction;
        r.status = RunStatus.QUEUED;
        r.attempt = prior.attempt + 1;
        return r;
    }

    /** 사람이 지시문을 붙여 직접 요청한 TASK run(AGP-42) — 트리거는 항상 USER다. */
    public static Run queuedUser(String issueKey, long projectId, long personaId, String harnessRef,
                                 String model, String instruction) {
        Run r = queued(RunType.TASK, issueKey, projectId, personaId, RunTrigger.USER, harnessRef, model);
        r.instruction = instruction;
        return r;
    }

    /**
     * 완료된 TASK run을 검증하는 REVIEW run(AGP-44). 워커 커밋은 푸시되지 않고 원 워크스페이스에만
     * 남으므로 리뷰어도 같은 워크스페이스를 봐야 한다(D-P2c-1) — workspacePath를 승계한다. REVIEW에서
     * REVIEW를 만들 수 없게 막는 것이 무한루프의 1차 가드다(D-P2c-4).
     */
    public static Run queuedReview(Run parent, long reviewerPersonaId, String model) {
        if (parent.type != RunType.TASK || parent.status != RunStatus.DONE) {
            throw new ConflictException("DONE 상태의 TASK run만 리뷰할 수 있습니다: " + parent.type + "/" + parent.status);
        }
        Run r = new Run();
        r.type = RunType.REVIEW;
        r.issueKey = parent.issueKey;
        r.projectId = parent.projectId;
        r.personaId = reviewerPersonaId;
        r.trigger = parent.trigger;
        r.harnessRef = parent.harnessRef;
        r.model = model;
        r.workspacePath = parent.workspacePath;
        r.parentRunId = parent.id;
        r.status = RunStatus.QUEUED;
        r.attempt = 1;
        return r;
    }

    /**
     * 리뷰 반려 후 원 페르소나가 같은 워크스페이스에서 지적을 고치는 TASK run(D-P2c-2). attempt는
     * {@link #continuation}과 같은 규칙(+1)이라 재시도 한도가 반려 루프에도 그대로 걸린다. 단
     * {@link #continuation}과 달리 워크스페이스를 승계한다 — 재시도는 새로 clone해야 하지만
     * 반려-fix는 앞선 커밋 위에서 이어가야 하기 때문이다.
     */
    public static Run fixContinuation(Run taskRun, long reviewRunId) {
        if (taskRun.type != RunType.TASK || taskRun.status != RunStatus.DONE) {
            throw new ConflictException("DONE 상태의 TASK run만 반려-fix로 이어갈 수 있습니다: "
                    + taskRun.type + "/" + taskRun.status);
        }
        Run r = new Run();
        r.type = RunType.TASK;
        r.issueKey = taskRun.issueKey;
        r.projectId = taskRun.projectId;
        r.personaId = taskRun.personaId;
        r.trigger = taskRun.trigger;
        r.harnessRef = taskRun.harnessRef;
        r.model = taskRun.model;
        r.instruction = taskRun.instruction;
        r.workspacePath = taskRun.workspacePath;
        r.parentRunId = reviewRunId;
        r.status = RunStatus.QUEUED;
        r.attempt = taskRun.attempt + 1;
        return r;
    }

    /**
     * 승계된 실제 워크스페이스 경로(REVIEW·반려-fix)가 있으면 시작 시 넘어온 자리표시값으로 덮지
     * 않는다 — 덮으면 워크스페이스 재사용(D-P2c-1)의 근거가 사라진다.
     */
    public void start(String workspacePath, Long patId) {
        requireStatus(RunStatus.QUEUED, "QUEUED 상태에서만 시작할 수 있습니다");
        this.status = RunStatus.RUNNING;
        if (this.workspacePath == null) {
            this.workspacePath = workspacePath;
        }
        this.patId = patId;
        this.startedAt = Instant.now();
    }

    public void parkForApproval() {
        requireStatus(RunStatus.RUNNING, "RUNNING 상태에서만 승인 대기로 전환할 수 있습니다");
        this.status = RunStatus.WAITING_APPROVAL;
    }

    public void complete() {
        requireStatus(RunStatus.RUNNING, "RUNNING 상태에서만 완료할 수 있습니다");
        this.status = RunStatus.DONE;
        this.endedAt = Instant.now();
    }

    public void fail(String error) {
        requireStatus(RunStatus.RUNNING, "RUNNING 상태에서만 실패 처리할 수 있습니다");
        this.status = RunStatus.FAILED;
        this.error = error;
        this.endedAt = Instant.now();
    }

    public void block(String error) {
        requireStatus(BLOCKABLE, "RUNNING 또는 FAILED 상태에서만 차단할 수 있습니다");
        this.status = RunStatus.BLOCKED;
        this.error = error;
    }

    public void cancel() {
        cancelWithNote(null);
    }

    /**
     * 취소 + 사유 기록(P2a T5) — 게이트 승인/거절로 원 run을 닫을 때 "왜 CANCELLED가
     * 됐는지"(예: 후속 continuation run id)를 {@link #error}에 남기려고 {@link #cancel()}과
     * 별도로 둔다. 가드는 {@link #cancel()}과 동일하다.
     */
    public void cancelWithNote(String note) {
        requireStatus(CANCELLABLE, "QUEUED·RUNNING·WAITING_APPROVAL·BLOCKED·FAILED 상태에서만 취소할 수 있습니다");
        this.status = RunStatus.CANCELLED;
        this.error = note;
        this.endedAt = Instant.now();
    }

    public void recordSession(String sessionId) {
        this.sessionId = sessionId;
    }

    /** 워커가 실제로 쓴 워크스페이스 경로 — 후속 REVIEW·반려-fix run이 이 값을 승계해 재사용한다(D-P2c-1). */
    public void recordWorkspace(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    private void requireStatus(RunStatus expected, String message) {
        if (this.status != expected) throw new ConflictException(message);
    }

    private void requireStatus(Set<RunStatus> allowed, String message) {
        if (!allowed.contains(this.status)) throw new ConflictException(message);
    }
}
