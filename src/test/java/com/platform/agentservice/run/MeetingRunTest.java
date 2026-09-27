package com.platform.agentservice.run;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 회의 run 팩토리·대표 키·continuation 승계(P3b). */
class MeetingRunTest {

    @Test
    void queued_meeting_makes_first_attendee_the_owner_and_keeps_attendee_order() {
        Run r = Run.queuedMeeting(RunType.RETRO, "PROJECT-3", 3L, List.of(6L, 1L, 4L), RunTrigger.SCHEDULER,
                "harness://default", "claude-sonnet-5", "정기 회고");

        assertThat(r.getType()).isEqualTo(RunType.RETRO);
        assertThat(r.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(r.getPersonaId()).isEqualTo(6L);
        assertThat(r.getAttendeeIds()).containsExactly(6L, 1L, 4L);
        assertThat(r.getAttendeePersonaIds()).isEqualTo("6,1,4");
        assertThat(r.getInstruction()).isEqualTo("정기 회고");
        assertThat(r.getAttempt()).isEqualTo(1);
        assertThat(r.getParentRunId()).isNull();
        assertThat(r.isWorkspaceLineage()).isFalse();
    }

    @Test
    void queued_meeting_rejects_non_meeting_types_and_empty_attendees() {
        assertThatThrownBy(() -> Run.queuedMeeting(RunType.TASK, "AGP-1", 1L, List.of(1L), RunTrigger.USER, "h", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Run.queuedMeeting(RunType.MEETING, "AGP-1", 1L, List.of(), RunTrigger.USER, "h", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void project_issue_key_marks_a_meeting_without_agenda_issue() {
        assertThat(Run.projectIssueKey(3L)).isEqualTo("PROJECT-3");
        Run noAgenda = Run.queuedMeeting(RunType.MEETING, "PROJECT-3", 3L, List.of(1L), RunTrigger.USER, "h", null, "x");
        Run withAgenda = Run.queuedMeeting(RunType.MEETING, "AGP-3", 3L, List.of(1L), RunTrigger.USER, "h", null, "x");
        // 대표 키 모양이어도 TASK는 실제 이슈다(ALM 프로젝트 키가 PROJECT일 수 있다).
        Run task = Run.queued(RunType.TASK, "PROJECT-3", 3L, 1L, RunTrigger.USER, "h", null);

        assertThat(noAgenda.hasAgendaIssue()).isFalse();
        assertThat(withAgenda.hasAgendaIssue()).isTrue();
        assertThat(task.hasAgendaIssue()).isTrue();
    }

    @Test
    void meeting_continuation_keeps_attendees_and_links_to_the_prior_run_but_not_its_output_page() {
        Run prior = Run.queuedMeeting(RunType.MEETING, "PROJECT-3", 3L, List.of(1L, 4L), RunTrigger.USER, "h", "m", "안건");
        ReflectionTestUtils.setField(prior, "id", 50L);
        prior.start("C:\\agent-work\\run-50", null);
        prior.recordOutputPage(501L);
        prior.parkForApproval();

        Run next = Run.continuation(prior);

        assertThat(next.getType()).isEqualTo(RunType.MEETING);
        assertThat(next.getAttendeeIds()).containsExactly(1L, 4L);
        assertThat(next.getParentRunId()).isEqualTo(50L);
        assertThat(next.getInstruction()).isEqualTo("안건");
        assertThat(next.getModel()).isEqualTo("m");
        assertThat(next.getAttempt()).isEqualTo(2);
        assertThat(next.getOutputPageId()).isNull();
        // 회의는 워크스페이스를 승계하지 않는다 — 코드가 없어 새 빈 워크스페이스면 된다.
        assertThat(next.getWorkspacePath()).isNull();
        assertThat(next.isWorkspaceLineage()).isFalse();
    }

    @Test
    void task_continuation_is_unchanged_and_has_no_attendees() {
        Run task = Run.queued(RunType.TASK, "AGP-1", 1L, 2L, RunTrigger.SCHEDULER, "h", null);
        ReflectionTestUtils.setField(task, "id", 10L);
        task.start("pending", null);
        task.fail("x");

        Run next = Run.continuation(task);

        assertThat(next.getParentRunId()).isNull();
        assertThat(next.getAttendeeIds()).isEmpty();
    }

    @Test
    void run_type_meeting_set_is_exactly_the_three_meeting_kinds() {
        assertThat(RunType.MEETING_TYPES).containsExactlyInAnyOrder(RunType.MEETING, RunType.RETRO, RunType.ESCALATION);
        assertThat(RunType.TASK.isMeeting()).isFalse();
        assertThat(RunType.REVIEW.isMeeting()).isFalse();
    }
}
