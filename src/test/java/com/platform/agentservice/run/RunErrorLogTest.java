package com.platform.agentservice.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AGP-53 — {@link Run#getError()}가 덮어쓰기가 아니라 누적되고, 상한에서 최초 원인을 보존하는지 본다. */
class RunErrorLogTest {

    private Run running() {
        Run r = Run.queued(RunType.TASK, "AGP-53", 1L, 2L, RunTrigger.SCHEDULER, "harness://local", null);
        r.start("/work/AGP-53", 9L);
        return r;
    }

    @Test
    void first_error_is_stored_as_is() {
        Run r = running();
        r.fail("시간 초과");
        assertThat(r.getError()).isEqualTo("시간 초과");
    }

    @Test
    void fail_then_block_keeps_the_cause_first_and_the_block_reason_after_a_separator() {
        Run r = running();
        r.fail("시간 초과");
        r.block("사고형 실패 — 즉시 중단: boom");

        assertThat(r.getError()).startsWith("시간 초과\n--- [");
        assertThat(r.getError()).endsWith("] ---\n사고형 실패 — 즉시 중단: boom");
    }

    @Test
    void cancel_note_after_block_preserves_the_block_reason() {
        Run r = running();
        r.block("사람 개입 필요");
        r.cancelWithNote("사람 확인 후 재개 — 후속 run 11로 재개");

        assertThat(r.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(r.getError()).startsWith("사람 개입 필요");
        assertThat(r.getError()).endsWith("사람 확인 후 재개 — 후속 run 11로 재개");
    }

    @Test
    void null_or_blank_note_keeps_the_existing_error() {
        Run r = running();
        r.block("사람 개입 필요");
        r.cancel();
        assertThat(r.getError()).isEqualTo("사람 개입 필요");

        Run other = running();
        other.fail("boom");
        other.block("   ");
        assertThat(other.getError()).isEqualTo("boom");
    }

    @Test
    void null_note_on_a_run_without_error_leaves_it_null() {
        Run r = Run.queued(RunType.TASK, "AGP-53", 1L, 2L, RunTrigger.SCHEDULER, "harness://local", null);
        r.cancel();
        assertThat(r.getError()).isNull();
    }

    @Test
    void overflow_keeps_the_original_cause_and_the_head_of_the_latest_entry() {
        Run r = running();
        String cause = "원인:" + "a".repeat(Run.ERROR_HEAD_KEEP + 500);
        r.fail(cause);
        r.block("최신 사유:" + "b".repeat(5000));

        String error = r.getError();
        assertThat(error).hasSize(Run.ERROR_MAX_LENGTH);
        assertThat(error).startsWith(cause.substring(0, Run.ERROR_HEAD_KEEP) + Run.ERROR_TRUNCATED + "\n--- [");
        assertThat(error).contains("] ---\n최신 사유:b");
        assertThat(error).endsWith(Run.ERROR_TRUNCATED);
    }

    @Test
    void overflow_with_short_cause_keeps_the_whole_cause() {
        Run r = running();
        r.fail("짧은 원인");
        r.block("c".repeat(Run.ERROR_MAX_LENGTH * 2));

        assertThat(r.getError()).hasSize(Run.ERROR_MAX_LENGTH);
        assertThat(r.getError()).startsWith("짧은 원인\n--- [");
        assertThat(r.getError()).endsWith(Run.ERROR_TRUNCATED);
    }

    @Test
    void single_oversized_first_error_is_capped_too() {
        Run r = running();
        r.fail("d".repeat(Run.ERROR_MAX_LENGTH + 1));
        assertThat(r.getError()).hasSize(Run.ERROR_MAX_LENGTH).endsWith(Run.ERROR_TRUNCATED);
    }

    @Test
    void cut_does_not_split_a_surrogate_pair() {
        String existing = "x".repeat(Run.ERROR_HEAD_KEEP - 1) + "😀" + "y".repeat(3000);
        String bounded = Run.boundedErrorLog(existing, "\n--- [t] ---\n" + "z".repeat(3000));

        // 6000자 경계가 이모지(서로게이트 쌍) 한가운데라 그 앞에서 자른다 — 홀로 남은 상위 서로게이트가 없어야 한다.
        assertThat(bounded).startsWith("x".repeat(Run.ERROR_HEAD_KEEP - 1) + Run.ERROR_TRUNCATED);
    }
}
