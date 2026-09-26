package com.platform.agentservice.run;

import com.platform.common.error.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2c 계보 팩토리 — USER run·REVIEW run·반려-fix continuation이 무엇을 승계하고 무엇을 새로
 * 정하는지, 그리고 워크스페이스 재사용(D-P2c-1)의 근거인 실경로가 시작 시 지워지지 않는지를 본다.
 */
class RunLineageTest {

    private static final String WORKSPACE = "C:\\agent-work\\run-10";

    /** 실경로가 기록된 채 DONE으로 끝난 TASK run — 리뷰/반려-fix의 부모. */
    private Run doneTask(RunTrigger trigger, String instruction, int attempt) {
        Run r = trigger == RunTrigger.USER
                ? Run.queuedUser("AGP-4", 1L, 2L, "harness://default", "claude-opus-5", instruction)
                : Run.queued(RunType.TASK, "AGP-4", 1L, 2L, trigger, "harness://default", "claude-opus-5");
        ReflectionTestUtils.setField(r, "id", 10L);
        ReflectionTestUtils.setField(r, "attempt", attempt);
        r.start("pending", 9L);
        r.recordWorkspace(WORKSPACE);
        r.complete();
        return r;
    }

    // ---- queuedUser ----

    @Test
    void queuedUser_is_a_user_triggered_task_carrying_the_instruction() {
        Run r = Run.queuedUser("AGP-4", 1L, 2L, "harness://default", "claude-sonnet-5", "로그인 버그부터 고쳐");

        assertThat(r.getType()).isEqualTo(RunType.TASK);
        assertThat(r.getTrigger()).isEqualTo(RunTrigger.USER);
        assertThat(r.getInstruction()).isEqualTo("로그인 버그부터 고쳐");
        assertThat(r.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(r.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(r.getAttempt()).isEqualTo(1);
        assertThat(r.getParentRunId()).isNull();
        assertThat(r.getWorkspacePath()).isNull();
    }

    @Test
    void existing_queued_factory_leaves_instruction_and_parent_empty() {
        Run r = Run.queued(RunType.TASK, "AGP-4", 1L, 2L, RunTrigger.SCHEDULER, "harness://default", null);

        assertThat(r.getInstruction()).isNull();
        assertThat(r.getParentRunId()).isNull();
    }

    @Test
    void retry_continuation_of_a_user_run_keeps_the_instruction() {
        Run r = Run.queuedUser("AGP-4", 1L, 2L, "harness://default", null, "로그인 버그부터 고쳐");
        ReflectionTestUtils.setField(r, "id", 10L);
        r.start("pending", 9L);
        r.fail("boom");

        Run next = Run.continuation(r);

        assertThat(next.getTrigger()).isEqualTo(RunTrigger.USER);
        assertThat(next.getInstruction()).isEqualTo("로그인 버그부터 고쳐");
        assertThat(next.getAttempt()).isEqualTo(2);
    }

    // ---- queuedReview ----

    @Test
    void queuedReview_inherits_issue_trigger_harness_and_workspace_and_links_parent() {
        Run parent = doneTask(RunTrigger.SCHEDULER, null, 2);

        Run review = Run.queuedReview(parent, 77L, "claude-fable-5-1");

        assertThat(review.getType()).isEqualTo(RunType.REVIEW);
        assertThat(review.getIssueKey()).isEqualTo("AGP-4");
        assertThat(review.getProjectId()).isEqualTo(1L);
        assertThat(review.getPersonaId()).isEqualTo(77L);
        assertThat(review.getTrigger()).isEqualTo(RunTrigger.SCHEDULER);
        assertThat(review.getHarnessRef()).isEqualTo("harness://default");
        assertThat(review.getModel()).isEqualTo("claude-fable-5-1");
        assertThat(review.getWorkspacePath()).isEqualTo(WORKSPACE);
        assertThat(review.getParentRunId()).isEqualTo(10L);
        assertThat(review.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(review.getAttempt()).isEqualTo(1); // 부모 attempt와 무관 — 리뷰는 자기 시도 횟수를 따로 센다
    }

    @Test
    void queuedReview_inherits_user_trigger_from_parent() {
        Run parent = doneTask(RunTrigger.USER, "지시", 1);

        assertThat(Run.queuedReview(parent, 77L, null).getTrigger()).isEqualTo(RunTrigger.USER);
    }

    /** 리뷰어가 USER 지시를 모르면 지시대로 한 변경을 반려하고 반려-fix가 지시를 따라 되돌리는 루프가 예산을 태운다. */
    @Test
    void queuedReview_inherits_the_user_instruction_so_the_reviewer_judges_against_it() {
        Run parent = doneTask(RunTrigger.USER, "로그인 버그부터 고쳐", 1);

        assertThat(Run.queuedReview(parent, 77L, null).getInstruction()).isEqualTo("로그인 버그부터 고쳐");
        assertThat(Run.queuedReview(doneTask(RunTrigger.SCHEDULER, null, 1), 77L, null).getInstruction()).isNull();
    }

    @Test
    void review_retry_continuation_keeps_the_instruction() {
        Run review = Run.queuedReview(doneTask(RunTrigger.USER, "지시", 1), 77L, null);
        ReflectionTestUtils.setField(review, "id", 11L);
        review.start("pending", 9L);
        review.fail("시간 초과");

        assertThat(Run.continuation(review).getInstruction()).isEqualTo("지시");
    }

    @Test
    void queuedReview_rejects_review_parent_so_reviews_cannot_chain() {
        Run review = Run.queuedReview(doneTask(RunTrigger.SCHEDULER, null, 1), 77L, null);
        ReflectionTestUtils.setField(review, "id", 11L);
        review.start("pending", 9L);
        review.complete();

        assertThatThrownBy(() -> Run.queuedReview(review, 77L, null)).isInstanceOf(ConflictException.class);
    }

    @Test
    void queuedReview_rejects_task_that_is_not_done() {
        Run running = Run.queued(RunType.TASK, "AGP-4", 1L, 2L, RunTrigger.SCHEDULER, "harness://default", null);
        running.start("pending", 9L);

        assertThatThrownBy(() -> Run.queuedReview(running, 77L, null)).isInstanceOf(ConflictException.class);
    }

    // ---- fixContinuation ----

    @Test
    void fixContinuation_keeps_persona_instruction_and_workspace_bumps_attempt_and_links_review() {
        Run task = doneTask(RunTrigger.USER, "로그인 버그부터 고쳐", 2);

        Run fix = Run.fixContinuation(task, 11L);

        assertThat(fix.getType()).isEqualTo(RunType.TASK);
        assertThat(fix.getIssueKey()).isEqualTo("AGP-4");
        assertThat(fix.getProjectId()).isEqualTo(1L);
        assertThat(fix.getPersonaId()).isEqualTo(2L);
        assertThat(fix.getTrigger()).isEqualTo(RunTrigger.USER);
        assertThat(fix.getHarnessRef()).isEqualTo("harness://default");
        assertThat(fix.getModel()).isEqualTo("claude-opus-5");
        assertThat(fix.getInstruction()).isEqualTo("로그인 버그부터 고쳐");
        assertThat(fix.getWorkspacePath()).isEqualTo(WORKSPACE);
        assertThat(fix.getParentRunId()).isEqualTo(11L);
        assertThat(fix.getAttempt()).isEqualTo(3);
        assertThat(fix.getStatus()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void fixContinuation_rejects_review_run_and_unfinished_task() {
        Run task = doneTask(RunTrigger.SCHEDULER, null, 1);
        Run review = Run.queuedReview(task, 77L, null);
        review.start("pending", 9L);
        review.complete();
        assertThatThrownBy(() -> Run.fixContinuation(review, 11L)).isInstanceOf(ConflictException.class);

        Run running = Run.queued(RunType.TASK, "AGP-4", 1L, 2L, RunTrigger.SCHEDULER, "harness://default", null);
        running.start("pending", 9L);
        assertThatThrownBy(() -> Run.fixContinuation(running, 11L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void retry_continuation_still_starts_from_a_fresh_workspace() {
        Run failed = Run.queued(RunType.TASK, "AGP-4", 1L, 2L, RunTrigger.SCHEDULER, "harness://default", null);
        failed.start("pending", 9L);
        failed.recordWorkspace(WORKSPACE);
        failed.fail("boom");

        Run retry = Run.continuation(failed);

        assertThat(retry.getWorkspacePath()).isNull();
        assertThat(retry.getParentRunId()).isNull();
    }

    // ---- continuation: 계보 run의 재시도는 워크스페이스를 승계한다(P2c T3) ----

    @Test
    void review_retry_continuation_inherits_workspace_and_parent_task() {
        Run review = Run.queuedReview(doneTask(RunTrigger.SCHEDULER, null, 1), 77L, "claude-fable-5-1");
        ReflectionTestUtils.setField(review, "id", 11L);
        review.start("pending", 9L);
        review.fail("시간 초과");

        Run retry = Run.continuation(review);

        assertThat(retry.getType()).isEqualTo(RunType.REVIEW);
        assertThat(retry.getPersonaId()).isEqualTo(77L);
        assertThat(retry.getModel()).isEqualTo("claude-fable-5-1");
        assertThat(retry.getWorkspacePath()).isEqualTo(WORKSPACE);
        // 재시도 후 반려돼도 원 TASK를 찾을 수 있어야 반려-fix가 이어진다.
        assertThat(retry.getParentRunId()).isEqualTo(10L);
        assertThat(retry.getAttempt()).isEqualTo(2);
    }

    @Test
    void fix_run_retry_continuation_keeps_building_on_the_same_workspace() {
        Run fix = Run.fixContinuation(doneTask(RunTrigger.SCHEDULER, null, 1), 11L);
        ReflectionTestUtils.setField(fix, "id", 12L);
        fix.start("pending", 9L);
        fix.fail("boom");

        Run retry = Run.continuation(fix);

        assertThat(retry.getType()).isEqualTo(RunType.TASK);
        assertThat(retry.getWorkspacePath()).isEqualTo(WORKSPACE);
        assertThat(retry.getParentRunId()).isEqualTo(11L);
        assertThat(retry.getAttempt()).isEqualTo(3);
    }

    @Test
    void workspace_lineage_is_reviews_and_parented_tasks_only() {
        Run plain = Run.queued(RunType.TASK, "AGP-4", 1L, 2L, RunTrigger.SCHEDULER, "harness://default", null);
        Run task = doneTask(RunTrigger.SCHEDULER, null, 1);

        assertThat(plain.isWorkspaceLineage()).isFalse();
        assertThat(Run.queuedReview(task, 77L, null).isWorkspaceLineage()).isTrue();
        assertThat(Run.fixContinuation(task, 11L).isWorkspaceLineage()).isTrue();
    }

    // ---- 계보 체인 전체(최종 리뷰 I3 — 격리 프로브로 검증한 체인 영구화) ----

    /**
     * TASK → REVIEW 반려 → fix(att2) → REVIEW 게이트 승인 continuation → 반려 → fix2(att3) → 인프라 실패 BLOCKED
     * → 사람 재개(att4) → REVIEW. 각 고리가 워크스페이스·계보(parentRunId)·attempt 카운터·USER 지시를 끊김 없이 잇는지 본다.
     */
    @Test
    void full_lineage_chain_keeps_workspace_parent_attempt_and_instruction_across_every_link() {
        long[] seq = {100};
        java.util.function.UnaryOperator<Run> persist = r -> {
            ReflectionTestUtils.setField(r, "id", seq[0]++);
            return r;
        };
        String instruction = "로그인 버그부터 고쳐";

        Run task = persist.apply(Run.queuedUser("AGP-4", 1L, 2L, "harness://default", "claude-opus-5", instruction));
        task.start("pending", 9L);
        task.recordWorkspace(WORKSPACE);
        task.complete();

        // 1차 리뷰 — 반려
        Run review1 = persist.apply(Run.queuedReview(task, 77L, null));
        assertThat(review1.getInstruction()).isEqualTo(instruction);
        assertThat(review1.getParentRunId()).isEqualTo(task.getId());
        review1.start("pending", 9L);
        assertThat(review1.getWorkspacePath()).isEqualTo(WORKSPACE);
        review1.fail("반려");

        // 반려-fix 1 — 원 TASK 카운터를 이어 att2
        Run fix1 = persist.apply(Run.fixContinuation(task, review1.getId()));
        assertThat(fix1.getAttempt()).isEqualTo(2);
        assertThat(fix1.getParentRunId()).isEqualTo(review1.getId());
        assertThat(fix1.getInstruction()).isEqualTo(instruction);
        fix1.start("pending", 9L);
        assertThat(fix1.getWorkspacePath()).isEqualTo(WORKSPACE);
        fix1.recordWorkspace(WORKSPACE);
        fix1.complete();

        // 2차 리뷰 — 게이트 요청 → 승인 continuation이 REVIEW 계보를 잇는다
        Run review2 = persist.apply(Run.queuedReview(fix1, 77L, null));
        assertThat(review2.getParentRunId()).isEqualTo(fix1.getId());
        assertThat(review2.getInstruction()).isEqualTo(instruction);
        review2.start("pending", 9L);
        review2.parkForApproval();
        Run review2b = persist.apply(Run.continuation(review2));
        review2.cancelWithNote("게이트 승인 — continuation으로 이어감");
        assertThat(review2b.getType()).isEqualTo(RunType.REVIEW);
        assertThat(review2b.getParentRunId()).isEqualTo(fix1.getId());
        assertThat(review2b.getWorkspacePath()).isEqualTo(WORKSPACE);
        assertThat(review2b.getInstruction()).isEqualTo(instruction);
        review2b.start("pending", 9L);
        review2b.fail("반려");

        // 반려-fix 2 — 게이트 continuation(review2b)의 parentRunId로 찾은 fix1에서 이어 att3
        Run fix2 = persist.apply(Run.fixContinuation(fix1, review2b.getId()));
        assertThat(fix2.getAttempt()).isEqualTo(3);
        assertThat(fix2.getInstruction()).isEqualTo(instruction);
        fix2.start("pending", 9L);
        fix2.fail("인프라 실패");
        fix2.block("3회 실패 — 사람 확인 필요");

        // 사람 재개 — BLOCKED fix의 continuation도 같은 워크스페이스에서 이어 att4
        Run fix2b = persist.apply(Run.continuation(fix2));
        assertThat(fix2b.getType()).isEqualTo(RunType.TASK);
        assertThat(fix2b.isWorkspaceLineage()).isTrue();
        assertThat(fix2b.getWorkspacePath()).isEqualTo(WORKSPACE);
        assertThat(fix2b.getParentRunId()).isEqualTo(review2b.getId());
        assertThat(fix2b.getAttempt()).isEqualTo(4);
        assertThat(fix2b.getInstruction()).isEqualTo(instruction);
        fix2b.start("pending", 9L);
        fix2b.complete();

        // 재개된 fix의 DONE에도 리뷰가 뜨고, REVIEW도 지시를 안다
        Run review3 = persist.apply(Run.queuedReview(fix2b, 77L, null));
        assertThat(review3.getType()).isEqualTo(RunType.REVIEW);
        assertThat(review3.getParentRunId()).isEqualTo(fix2b.getId());
        assertThat(review3.getWorkspacePath()).isEqualTo(WORKSPACE);
        assertThat(review3.getInstruction()).isEqualTo(instruction);
        assertThat(review3.getAttempt()).isEqualTo(1);
    }

    // ---- start / recordWorkspace ----

    @Test
    void start_does_not_overwrite_an_inherited_workspace_with_the_placeholder() {
        Run review = Run.queuedReview(doneTask(RunTrigger.SCHEDULER, null, 1), 77L, null);

        review.start("pending", 9L);

        assertThat(review.getWorkspacePath()).isEqualTo(WORKSPACE);
    }

    @Test
    void recordWorkspace_replaces_the_placeholder_with_the_real_path() {
        Run r = Run.queued(RunType.TASK, "AGP-4", 1L, 2L, RunTrigger.SCHEDULER, "harness://default", null);
        r.start("pending", 9L);

        r.recordWorkspace(WORKSPACE);

        assertThat(r.getWorkspacePath()).isEqualTo(WORKSPACE);
    }
}
