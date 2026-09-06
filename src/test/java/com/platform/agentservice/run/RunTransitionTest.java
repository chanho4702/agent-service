package com.platform.agentservice.run;

import com.platform.common.error.ConflictException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 순수 단위 테스트 — DB 없이 Run/Gate 상태 전이 합법·불법 케이스를 검증한다(스펙 D9). */
class RunTransitionTest {

    private Run queued() {
        return Run.queued(RunType.TASK, "AGP-4", 1L, 2L, RunTrigger.USER, "harness://local", "claude-opus-5");
    }

    // ---- 합법 전이 ----

    @Test
    void start_movesQueuedToRunning() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        assertThat(r.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(r.getWorkspacePath()).isEqualTo("/work/AGP-4");
        assertThat(r.getPatId()).isEqualTo(9L);
        assertThat(r.getStartedAt()).isNotNull();
    }

    @Test
    void parkForApproval_movesRunningToWaitingApproval() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.parkForApproval();
        assertThat(r.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
    }

    @Test
    void complete_movesRunningToDoneAndSetsEndedAt() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.complete();
        assertThat(r.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(r.getEndedAt()).isNotNull();
    }

    @Test
    void fail_movesRunningToFailedWithError() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.fail("timeout");
        assertThat(r.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(r.getError()).isEqualTo("timeout");
        assertThat(r.getEndedAt()).isNotNull();
    }

    @Test
    void block_movesRunningToBlocked() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.block("사람 개입 필요");
        assertThat(r.getStatus()).isEqualTo(RunStatus.BLOCKED);
    }

    @Test
    void block_movesFailedToBlocked() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.fail("boom");
        r.block("재시도 한계 초과");
        assertThat(r.getStatus()).isEqualTo(RunStatus.BLOCKED);
    }

    @Test
    void cancel_movesQueuedToCancelled() {
        Run r = queued();
        r.cancel();
        assertThat(r.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(r.getEndedAt()).isNotNull();
    }

    @Test
    void cancel_movesRunningToCancelled() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.cancel();
        assertThat(r.getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void cancel_movesWaitingApprovalToCancelled() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.parkForApproval();
        r.cancel();
        assertThat(r.getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void cancel_movesBlockedToCancelled() {
        // fix round 2(P2a T7 재리뷰): 사람 확인 후 재개(RunResumeService.resume)가 원 run을
        // 이 경로로 닫는다 — BLOCKED는 종단이 아니므로 취소 가능해야 한다.
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.fail("boom");
        r.block("3회 실패 — 사람 확인 필요");
        r.cancel();
        assertThat(r.getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void continuation_fromWaitingApproval_createsNewQueuedRunWithIncrementedAttempt() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.parkForApproval();

        Run next = Run.continuation(r);

        assertThat(next.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(next.getAttempt()).isEqualTo(2);
        assertThat(next.getIssueKey()).isEqualTo(r.getIssueKey());
        assertThat(next.getProjectId()).isEqualTo(r.getProjectId());
        assertThat(next.getPersonaId()).isEqualTo(r.getPersonaId());
        assertThat(next.getType()).isEqualTo(r.getType());
        assertThat(next.getTrigger()).isEqualTo(r.getTrigger());
        assertThat(next.getModel()).isEqualTo(r.getModel());
    }

    @Test
    void continuation_fromBlocked_createsNewQueuedRun() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.block("사람 개입 필요");

        Run next = Run.continuation(r);

        assertThat(next.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(next.getAttempt()).isEqualTo(2);
    }

    @Test
    void continuation_fromFailed_createsNewQueuedRun() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.fail("timeout");

        Run next = Run.continuation(r);

        assertThat(next.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(next.getAttempt()).isEqualTo(2);
        assertThat(next.getModel()).isEqualTo(r.getModel());
    }

    @Test
    void recordSession_setsSessionId() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.recordSession("sess-123");
        assertThat(r.getSessionId()).isEqualTo("sess-123");
    }

    // ---- 게이트 합법 전이 ----

    @Test
    void gate_approve_setsDecision() {
        Gate g = Gate.request(1L, GateKind.MERGE, "머지해도 될까요?");
        g.approve(42L);
        assertThat(g.getDecision()).isEqualTo(GateDecision.APPROVE);
        assertThat(g.getDecidedBy()).isEqualTo(42L);
        assertThat(g.getDecidedAt()).isNotNull();
    }

    @Test
    void gate_reject_setsDecision() {
        Gate g = Gate.request(1L, GateKind.ESCALATION, "계속 진행할까요?");
        g.reject(42L);
        assertThat(g.getDecision()).isEqualTo(GateDecision.REJECT);
    }

    // ---- 불법 전이: continuation 경계 (I1 — FAILED 재시도는 continuation, RUNNING/DONE은 불법) ----

    @Test
    void continuation_fromRunning_isIllegal() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        assertThatThrownBy(() -> Run.continuation(r)).isInstanceOf(ConflictException.class);
    }

    @Test
    void continuation_fromDone_isIllegal() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.complete();
        assertThatThrownBy(() -> Run.continuation(r)).isInstanceOf(ConflictException.class);
    }

    // ---- 불법 전이 (최소 4건) ----

    @Test
    void complete_fromQueued_isIllegal() {
        Run r = queued();
        assertThatThrownBy(r::complete).isInstanceOf(ConflictException.class);
    }

    @Test
    void start_fromWaitingApproval_isIllegal() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.parkForApproval();
        assertThatThrownBy(() -> r.start("/work/AGP-4", 9L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void cancel_fromDone_isIllegal() {
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.complete();
        assertThatThrownBy(r::cancel).isInstanceOf(ConflictException.class);
    }

    @Test
    void cancel_fromFailed_isIllegal() {
        // fix round 2(P2a T7 재리뷰): BLOCKED는 취소 가능해졌지만 FAILED는 여전히 아니다 —
        // FAILED에서 "닫는" 합법 경로는 재시도(continuation) 또는 block()뿐이다.
        Run r = queued();
        r.start("/work/AGP-4", 9L);
        r.fail("boom");
        assertThatThrownBy(r::cancel).isInstanceOf(ConflictException.class);
    }

    @Test
    void continuation_fromQueued_isIllegal() {
        Run r = queued();
        assertThatThrownBy(() -> Run.continuation(r)).isInstanceOf(ConflictException.class);
    }

    @Test
    void gate_doubleApprove_isIllegal() {
        Gate g = Gate.request(1L, GateKind.PLAN, "계획대로 진행할까요?");
        g.approve(42L);
        assertThatThrownBy(() -> g.approve(42L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void gate_rejectThenReject_isIllegal() {
        Gate g = Gate.request(1L, GateKind.MERGE, "머지해도 될까요?");
        g.reject(42L);
        assertThatThrownBy(() -> g.reject(42L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void gate_approveThenReject_isIllegal() {
        Gate g = Gate.request(1L, GateKind.ESCALATION, "계속 진행할까요?");
        g.approve(42L);
        assertThatThrownBy(() -> g.reject(42L)).isInstanceOf(ConflictException.class);
    }
}
