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

    private static final Set<RunStatus> CANCELLABLE = EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.WAITING_APPROVAL);
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
    private Instant startedAt;
    private Instant endedAt;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(nullable = false) private Instant updatedAt;

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
        r.status = RunStatus.QUEUED;
        r.attempt = prior.attempt + 1;
        return r;
    }

    public void start(String workspacePath, Long patId) {
        requireStatus(RunStatus.QUEUED, "QUEUED 상태에서만 시작할 수 있습니다");
        this.status = RunStatus.RUNNING;
        this.workspacePath = workspacePath;
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
        requireStatus(CANCELLABLE, "QUEUED·RUNNING·WAITING_APPROVAL 상태에서만 취소할 수 있습니다");
        this.status = RunStatus.CANCELLED;
        this.endedAt = Instant.now();
    }

    public void recordSession(String sessionId) {
        this.sessionId = sessionId;
    }

    private void requireStatus(RunStatus expected, String message) {
        if (this.status != expected) throw new ConflictException(message);
    }

    private void requireStatus(Set<RunStatus> allowed, String message) {
        if (!allowed.contains(this.status)) throw new ConflictException(message);
    }
}
