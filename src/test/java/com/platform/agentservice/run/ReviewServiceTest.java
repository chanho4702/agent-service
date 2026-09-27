package com.platform.agentservice.run;

import com.platform.agentservice.alert.AlertKind;
import com.platform.agentservice.alert.AlertService;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReviewService}(P2c T3, AGP-44) — TASK DONE → REVIEW 생성, REVIEW 반려 → 반려-fix, 그리고 둘 다
 * 이어갈 수 없을 때의 fail-closed(경고·BLOCKED)를 본다. 두 종결 경로가 이 서비스에 합류하는지는
 * {@code RunServiceTest}가 본다.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    private static final long WORKER_PERSONA_ID = 5L;
    private static final long WORKER_MEMBER_ID = 42L;
    private static final long REVIEWER_PERSONA_ID = 7L;
    private static final long REVIEWER_MEMBER_ID = 44L;
    private static final String WORKER_BEARER = "Bearer worker";
    private static final String REVIEWER_BEARER = "Bearer reviewer";
    private static final String ISSUE_KEY = "AGP-9";
    private static final String WORKSPACE = "C:\\agent-work\\run-10";

    @Mock RunRepository runRepository;
    @Mock PersonaRepository personaRepository;
    @Mock TokenService tokenService;
    @Mock AlmClient almClient;
    @Mock MeetingService meetingService;
    @Mock AlertService alertService;
    @Mock ObjectProvider<RunService> runServiceProvider;
    @Mock RunService runService;

    private Persona worker;
    private Persona reviewer;

    @BeforeEach
    void setUp() {
        worker = persona(WORKER_PERSONA_ID, WORKER_MEMBER_ID, "jiho");
        reviewer = persona(REVIEWER_PERSONA_ID, REVIEWER_MEMBER_ID, "sora");
        lenient().when(personaRepository.findById(WORKER_PERSONA_ID)).thenReturn(Optional.of(worker));
        lenient().when(personaRepository.findById(REVIEWER_PERSONA_ID)).thenReturn(Optional.of(reviewer));
        lenient().when(tokenService.bearerFor(WORKER_MEMBER_ID)).thenReturn(WORKER_BEARER);
        lenient().when(tokenService.bearerFor(REVIEWER_MEMBER_ID)).thenReturn(REVIEWER_BEARER);
        lenient().when(almClient.getByKey(eq(ISSUE_KEY), anyString())).thenReturn(issue());
        lenient().when(almClient.addComment(anyLong(), anyString(), anyString()))
                .thenReturn(new CommentResponse(1L, 1L, WORKER_MEMBER_ID, "b", null, null));
        lenient().when(runServiceProvider.getObject()).thenReturn(runService);
        lenient().when(runRepository.save(any(Run.class))).thenAnswer(inv -> {
            Run saved = inv.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", 99L);
            }
            return saved;
        });
    }

    private ReviewService service(ReviewProperties reviewProperties) {
        return service(reviewProperties, new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3));
    }

    private ReviewService service(ReviewProperties reviewProperties, SchedulerProperties schedulerProperties) {
        return new ReviewService(runRepository, personaRepository, tokenService, almClient, reviewProperties,
                schedulerProperties, meetingService, alertService, runServiceProvider);
    }

    private static ReviewProperties enabledWith(String slug) {
        return new ReviewProperties(true, slug, null);
    }

    private static Persona persona(long id, long memberId, String slug) {
        Persona p = Persona.of(memberId, slug, PersonaRole.BACKEND, slug, null, null);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private static IssueResponse issue() {
        return new IssueResponse(1L, ISSUE_KEY, 1L, "제목", "본문", "task", "inprogress", "high", WORKER_MEMBER_ID,
                1L, null, null, null, null, null, null, List.of(), List.of(), 0L, 1, null, null, null);
    }

    private static Run doneTask(String workspace, int attempt) {
        Run r = Run.queuedUser(ISSUE_KEY, 1L, WORKER_PERSONA_ID, "harness://default", "claude-opus-5", "지시문");
        ReflectionTestUtils.setField(r, "id", 10L);
        ReflectionTestUtils.setField(r, "attempt", attempt);
        r.start("pending", null);
        if (workspace != null) {
            r.recordWorkspace(workspace);
        } else {
            ReflectionTestUtils.setField(r, "workspacePath", null);
        }
        r.complete();
        return r;
    }

    private static Run doneTask(String workspace, int attempt, int rejectCount) {
        Run r = doneTask(workspace, attempt);
        ReflectionTestUtils.setField(r, "rejectCount", rejectCount);
        return r;
    }

    private static Run failedReview(Run task) {
        Run review = Run.queuedReview(task, REVIEWER_PERSONA_ID, null);
        ReflectionTestUtils.setField(review, "id", 11L);
        review.start("pending", null);
        review.fail("반려: 테스트 누락");
        return review;
    }

    // ---- onRunDone: TASK DONE → REVIEW ----

    @Test
    void task_done_creates_review_run_for_reviewer_on_the_same_workspace_and_submits_it() {
        when(personaRepository.findBySlug("sora")).thenReturn(Optional.of(reviewer));
        Run task = doneTask(WORKSPACE, 1);

        service(enabledWith("sora")).onRunDone(task);

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        Run review = saved.getValue();
        assertThat(review.getType()).isEqualTo(RunType.REVIEW);
        assertThat(review.getPersonaId()).isEqualTo(REVIEWER_PERSONA_ID);
        assertThat(review.getWorkspacePath()).isEqualTo(WORKSPACE);
        assertThat(review.getParentRunId()).isEqualTo(10L);
        assertThat(review.getStatus()).isEqualTo(RunStatus.QUEUED);
        // 리뷰어가 USER 지시를 모르면 지시대로 한 변경을 반려 → fix가 지시를 따라 다시 → 예산 소진 루프(최종 리뷰 I1).
        assertThat(review.getInstruction()).isEqualTo("지시문");
        verify(runService).execute(99L);
        verify(almClient).addComment(eq(1L), contains("검증 run 99"), eq(WORKER_BEARER));
    }

    @Test
    void rejected_review_submission_is_swallowed_because_the_run_is_already_queued() {
        when(personaRepository.findBySlug("sora")).thenReturn(Optional.of(reviewer));
        doThrow(new TaskRejectedException("pool full")).when(runService).execute(99L);

        service(enabledWith("sora")).onRunDone(doneTask(WORKSPACE, 1));

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.QUEUED);
        verify(almClient).addComment(eq(1L), contains("검증 run 99"), eq(WORKER_BEARER));
    }

    @Test
    void rejected_fix_submission_leaves_the_review_failed_not_blocked() {
        Run task = doneTask(WORKSPACE, 1);
        Run review = failedReview(task);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));
        doThrow(new TaskRejectedException("pool full")).when(runService).execute(99L);

        service(enabledWith("sora")).onReviewRejected(review);

        assertThat(review.getStatus()).isEqualTo(RunStatus.FAILED);
        verify(almClient).addComment(eq(1L), contains("리뷰 반려 — 수정 run 99"), eq(REVIEWER_BEARER));
    }

    @Test
    void review_model_setting_wins_over_scheduler_policy() {
        when(personaRepository.findBySlug("sora")).thenReturn(Optional.of(reviewer));
        SchedulerProperties policy = new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3,
                "claude-global", Map.of("AGP", "claude-project"));

        service(new ReviewProperties(true, "sora", " claude-fable-5-1 "), policy).onRunDone(doneTask(WORKSPACE, 1));

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getModel()).isEqualTo("claude-fable-5-1");
    }

    @Test
    void review_model_falls_back_to_project_then_global_policy_not_the_parent_user_model() {
        when(personaRepository.findBySlug("sora")).thenReturn(Optional.of(reviewer));
        SchedulerProperties policy = new SchedulerProperties(true, 60000L, 2, 1, "jiho", 3,
                "claude-global", Map.of("agp", "claude-project"));

        service(new ReviewProperties(true, "sora", "  "), policy).onRunDone(doneTask(WORKSPACE, 1));

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getModel()).isEqualTo("claude-project");
    }

    @Test
    void review_done_creates_nothing_so_reviews_cannot_loop() {
        Run task = doneTask(WORKSPACE, 1);
        Run review = Run.queuedReview(task, REVIEWER_PERSONA_ID, null);
        ReflectionTestUtils.setField(review, "id", 11L);
        review.start("pending", null);
        review.complete();

        service(enabledWith("sora")).onRunDone(review);

        verify(runRepository, never()).save(any());
        verify(runService, never()).execute(anyLong());
        verify(almClient, never()).addComment(anyLong(), anyString(), anyString());
    }

    @Test
    void disabled_review_skips_silently_legacy_behaviour() {
        service(new ReviewProperties(false, "sora", null)).onRunDone(doneTask(WORKSPACE, 1));

        verify(runRepository, never()).save(any());
        verify(runService, never()).execute(anyLong());
        verify(almClient, never()).addComment(anyLong(), anyString(), anyString());
    }

    // ---- onRunDone: 설정 결함 → fail-closed 경고 ----

    private void assertUnverifiedWarning() {
        verify(runRepository, never()).save(any());
        verify(runService, never()).execute(anyLong());
        verify(almClient).addComment(eq(1L), contains("⚠️ 검증 run을 만들 수 없음"), eq(WORKER_BEARER));
        verify(almClient).addComment(eq(1L), contains("미확정(inprogress)"), eq(WORKER_BEARER));
    }

    @Test
    void missing_reviewer_persona_leaves_issue_unverified_with_warning() {
        when(personaRepository.findBySlug("ghost")).thenReturn(Optional.empty());

        service(enabledWith("ghost")).onRunDone(doneTask(WORKSPACE, 1));

        assertUnverifiedWarning();
    }

    @Test
    void unset_reviewer_slug_leaves_issue_unverified_with_warning() {
        service(enabledWith(" ")).onRunDone(doneTask(WORKSPACE, 1));

        assertUnverifiedWarning();
        verify(personaRepository, never()).findBySlug(any());
    }

    @Test
    void inactive_reviewer_persona_counts_as_missing() {
        ReflectionTestUtils.setField(reviewer, "active", false);
        when(personaRepository.findBySlug("sora")).thenReturn(Optional.of(reviewer));

        service(enabledWith("sora")).onRunDone(doneTask(WORKSPACE, 1));

        assertUnverifiedWarning();
    }

    @Test
    void reviewer_equal_to_worker_is_not_verification() {
        when(personaRepository.findBySlug("jiho")).thenReturn(Optional.of(worker));

        service(enabledWith("jiho")).onRunDone(doneTask(WORKSPACE, 1));

        assertUnverifiedWarning();
    }

    @Test
    void pending_workspace_leaves_issue_unverified_with_warning() {
        service(enabledWith("sora")).onRunDone(doneTask("pending", 1));

        assertUnverifiedWarning();
    }

    @Test
    void null_workspace_leaves_issue_unverified_with_warning() {
        service(enabledWith("sora")).onRunDone(doneTask(null, 1));

        assertUnverifiedWarning();
    }

    // ---- onReviewRejected: 반려 → 반려-fix ----

    @Test
    void rejection_creates_fix_run_for_original_persona_on_same_workspace_with_instruction() {
        Run task = doneTask(WORKSPACE, 1);
        Run review = failedReview(task);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));

        service(enabledWith("sora")).onReviewRejected(review);

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        Run fix = saved.getValue();
        assertThat(fix.getType()).isEqualTo(RunType.TASK);
        assertThat(fix.getPersonaId()).isEqualTo(WORKER_PERSONA_ID);
        assertThat(fix.getWorkspacePath()).isEqualTo(WORKSPACE);
        assertThat(fix.getInstruction()).isEqualTo("지시문");
        assertThat(fix.getParentRunId()).isEqualTo(11L);
        assertThat(fix.getAttempt()).isEqualTo(2);
        assertThat(fix.getRejectCount()).isEqualTo(1);
        verify(runService).execute(99L);
        verify(almClient).addComment(eq(1L), eq("🔁 리뷰 반려 — 수정 run 99 시작(반려 1/2), 위 지적사항을 반영합니다."),
                eq(REVIEWER_BEARER));
        assertThat(review.getStatus()).isEqualTo(RunStatus.FAILED);
        // 기본 임계 = 한도(2) — 첫 반려는 알림 대상이 아니다.
        verify(alertService, never()).notifyRejectThreshold(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any());
        verify(alertService, never()).notifyBlocked(any(), anyString(), any());
    }

    /**
     * P2c~P3c는 반려-fix의 attempt가 {@code SCHEDULER_RETRY_MAX_ATTEMPTS}(3)를 넘으면 BLOCKED였다. P3d부터 attempt는 계보 순번일
     * 뿐이라 attempt=3인 원 TASK도 반려 예산(reject_count 0 → 1)이 남아 있으면 fix가 뜬다.
     */
    @Test
    void rejection_is_no_longer_limited_by_the_attempt_counter_only_by_the_reject_budget() {
        Run task = doneTask(WORKSPACE, 3, 0);
        Run review = failedReview(task);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));

        service(enabledWith("sora")).onReviewRejected(review);

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getAttempt()).isEqualTo(4);
        assertThat(saved.getValue().getRejectCount()).isEqualTo(1);
        assertThat(review.getStatus()).isEqualTo(RunStatus.FAILED);
        verify(runService).execute(99L);
    }

    @Test
    void rejection_past_the_reject_max_blocks_review_run_alerts_once_and_creates_no_fix() {
        Run task = doneTask(WORKSPACE, 3, 2);
        Run review = failedReview(task);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));

        service(enabledWith("sora")).onReviewRejected(review);

        assertThat(review.getStatus()).isEqualTo(RunStatus.BLOCKED);
        assertThat(review.getError()).contains("반려 한도 2회 소진 — 사람 확인 필요");
        verify(runRepository).save(review);
        verify(runService, never()).execute(anyLong());
        verify(almClient).addComment(eq(1L), eq("⛔ 반려 한도 2회 소진 — 사람 확인 필요"), eq(REVIEWER_BEARER));
        verify(alertService, times(1)).notifyBlocked(review, "반려 한도 2회 소진 — 사람 확인 필요", AlertKind.REJECT_LIMIT);
        // 한도 소진은 BLOCKED 알림 한 통으로 끝난다 — 임계 알림이 겹쳐 나가지 않는다.
        verify(alertService, never()).notifyRejectThreshold(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any());
        assertThat(task.getStatus()).isEqualTo(RunStatus.DONE);
    }

    @Test
    void reject_max_zero_sends_the_very_first_rejection_to_a_human() {
        Run task = doneTask(WORKSPACE, 1, 0);
        Run review = failedReview(task);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));

        service(new ReviewProperties(true, "sora", null, 0, null)).onReviewRejected(review);

        assertThat(review.getStatus()).isEqualTo(RunStatus.BLOCKED);
        assertThat(review.getError()).contains("반려 한도 0회 소진");
        verify(runService, never()).execute(anyLong());
    }

    /**
     * 계보 전체: TASK(반려 0) → 반려 → fix1(1) → 반려 → fix2(2, 기본 임계=한도 2 도달 알림, 진행 계속) → 반려 → 한도 초과 BLOCKED.
     * 카운터는 "원 TASK" 즉 직전 fix의 값에서 +1되므로, 각 단계 REVIEW의 부모가 직전 fix run이어야 이어진다.
     */
    @Test
    void reject_counter_lineage_counts_up_across_fix_runs_then_blocks_past_the_max() {
        ReviewService service = service(enabledWith("sora"));
        Run task = doneTask(WORKSPACE, 1, 0);

        // 1차 반려 → fix1(reject 1)
        Run review1 = failedReview(task);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));
        service.onReviewRejected(review1);
        Run fix1 = lastSaved();
        assertThat(fix1.getRejectCount()).isEqualTo(1);
        verify(alertService, never()).notifyRejectThreshold(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any());

        // fix1 완료 → 2차 리뷰 반려 → fix2(reject 2, 임계 도달 — 알림은 보내고 fix는 뜬다)
        Run fix1Done = complete(fix1, 12L);
        Run review2 = failedReviewOf(fix1Done, 13L);
        assertThat(review2.getRejectCount()).isEqualTo(1); // REVIEW도 직전 값을 싣는다(감독·알림 표시용)
        when(runRepository.findById(12L)).thenReturn(Optional.of(fix1Done));
        service.onReviewRejected(review2);
        Run fix2 = lastSaved();
        assertThat(fix2.getRejectCount()).isEqualTo(2);
        assertThat(fix2.getStatus()).isEqualTo(RunStatus.QUEUED);
        verify(alertService, times(1)).notifyRejectThreshold(review2, 2, 2, fix2.getId());
        verify(almClient).addComment(eq(1L), contains("⚠️ 반려 알림 임계 도달"), eq(REVIEWER_BEARER));

        // fix2 완료 → 3차 리뷰 반려 → 3 > 2 → BLOCKED
        Run fix2Done = complete(fix2, 14L);
        Run review3 = failedReviewOf(fix2Done, 15L);
        when(runRepository.findById(14L)).thenReturn(Optional.of(fix2Done));
        service.onReviewRejected(review3);
        assertThat(review3.getStatus()).isEqualTo(RunStatus.BLOCKED);
        verify(alertService, times(1)).notifyBlocked(review3, "반려 한도 2회 소진 — 사람 확인 필요", AlertKind.REJECT_LIMIT);
        verify(alertService, times(1)).notifyRejectThreshold(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void alert_threshold_below_the_max_only_alerts_and_the_fix_still_runs() {
        Run task = doneTask(WORKSPACE, 1, 0);
        Run review = failedReview(task);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));

        service(new ReviewProperties(true, "sora", null, 3, 1)).onReviewRejected(review);

        Run fix = lastSaved();
        assertThat(fix.getRejectCount()).isEqualTo(1);
        verify(runService).execute(99L);
        verify(alertService, times(1)).notifyRejectThreshold(review, 1, 3, 99L);
        verify(alertService, never()).notifyBlocked(any(), anyString(), any());
        assertThat(review.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void alert_threshold_defaults_to_the_max() {
        assertThat(new ReviewProperties(true, "sora", null, 4, null).effectiveRejectAlertAt()).isEqualTo(4);
        assertThat(new ReviewProperties(true, "sora", null).effectiveRejectAlertAt())
                .isEqualTo(ReviewProperties.DEFAULT_REJECT_MAX);
        assertThat(new ReviewProperties(true, "sora", null, 3, 0).effectiveRejectAlertAt()).isEqualTo(3);
        assertThat(new ReviewProperties(true, "sora", null, -1, null).rejectMax()).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void alert_threshold_above_the_max_is_warned_at_startup(CapturedOutput output) {
        service(new ReviewProperties(true, "sora", null, 2, 5));

        assertThat(output.getOut()).contains("reject-alert-at(5)이 reject-max(2)보다 큽니다");
    }

    private Run lastSaved() {
        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        List<Run> all = saved.getAllValues();
        return all.get(all.size() - 1);
    }

    /** 저장소가 새 run에 99를 매기므로 계보 단계마다 고유 id로 바꿔 둔 뒤 DONE까지 돌린다. */
    private static Run complete(Run queued, long id) {
        ReflectionTestUtils.setField(queued, "id", id);
        queued.start("pending", null);
        queued.complete();
        return queued;
    }

    private static Run failedReviewOf(Run task, long id) {
        Run review = Run.queuedReview(task, REVIEWER_PERSONA_ID, null);
        ReflectionTestUtils.setField(review, "id", id);
        review.start("pending", null);
        review.fail("반려: 또 누락");
        return review;
    }

    @Test
    void rejection_without_findable_parent_blocks_review_run() {
        Run review = failedReview(doneTask(WORKSPACE, 1));
        when(runRepository.findById(10L)).thenReturn(Optional.empty());

        service(enabledWith("sora")).onReviewRejected(review);

        assertThat(review.getStatus()).isEqualTo(RunStatus.BLOCKED);
        verify(runService, never()).execute(anyLong());
        verify(alertService, times(1)).notifyBlocked(eq(review), contains("이어갈 원 작업 run을 찾을 수 없음"),
                eq(AlertKind.LINEAGE_BROKEN));
    }

    @Test
    void rejection_when_parent_workspace_is_gone_blocks_instead_of_fixing_on_a_fresh_clone() {
        Run task = doneTask(WORKSPACE, 1);
        Run review = failedReview(task);
        task.recordWorkspace("pending");
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));

        service(enabledWith("sora")).onReviewRejected(review);

        assertThat(review.getStatus()).isEqualTo(RunStatus.BLOCKED);
        verify(runService, never()).execute(anyLong());
    }

    @Test
    void rejection_hook_ignores_task_runs() {
        Run task = doneTask(WORKSPACE, 1);

        service(enabledWith("sora")).onReviewRejected(task);

        verify(runRepository, never()).save(any());
        verify(runRepository, never()).findById(anyLong());
    }

    // ---- P3b: 회의 run과 자동 에스컬레이션 ----

    @Test
    void meeting_run_done_never_creates_a_review_because_meetings_have_no_code_output() {
        for (RunType type : RunType.MEETING_TYPES) {
            Run meeting = Run.queuedMeeting(type, ISSUE_KEY, 1L, List.of(WORKER_PERSONA_ID), RunTrigger.USER,
                    "harness://default", null, "안건");
            ReflectionTestUtils.setField(meeting, "id", 20L);
            meeting.start("pending", null);
            meeting.recordWorkspace(WORKSPACE);
            meeting.recordOutputPage(501L);
            meeting.complete();

            service(enabledWith("sora")).onRunDone(meeting);
        }

        verify(runRepository, never()).save(any());
        verify(runService, never()).execute(anyLong());
        verify(almClient, never()).addComment(anyLong(), anyString(), anyString());
    }

    @Test
    void rejection_past_reject_max_hands_the_blocked_review_to_auto_escalation() {
        Run task = doneTask(WORKSPACE, 1, 2);
        Run review = failedReview(task);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));

        service(enabledWith("sora")).onReviewRejected(review);

        verify(meetingService).onBlocked(review, "반려 한도 2회 소진 — 사람 확인 필요");
    }

    @Test
    void rejection_that_can_continue_or_blocks_for_other_reasons_does_not_escalate() {
        Run task = doneTask(WORKSPACE, 1);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));
        service(enabledWith("sora")).onReviewRejected(failedReview(task));

        Run orphan = failedReview(doneTask(WORKSPACE, 1));
        when(runRepository.findById(10L)).thenReturn(Optional.empty());
        service(enabledWith("sora")).onReviewRejected(orphan);

        verify(meetingService, never()).onBlocked(any(), anyString());
    }

    @Test
    void auto_escalation_failure_after_rejection_is_swallowed() {
        Run task = doneTask(WORKSPACE, 1, 2);
        Run review = failedReview(task);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(meetingService).onBlocked(any(), anyString());

        service(enabledWith("sora")).onReviewRejected(review);

        assertThat(review.getStatus()).isEqualTo(RunStatus.BLOCKED);
    }
}
