package com.platform.agentservice.run;

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
import org.springframework.beans.factory.ObjectProvider;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
                schedulerProperties, runServiceProvider);
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
        verify(runService).execute(99L);
        verify(almClient).addComment(eq(1L), contains("검증 run 99"), eq(WORKER_BEARER));
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
        verify(runService).execute(99L);
        verify(almClient).addComment(eq(1L), contains("리뷰 반려 — 수정 run 99"), eq(REVIEWER_BEARER));
        assertThat(review.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void rejection_past_attempt_limit_blocks_review_run_and_creates_no_fix() {
        Run task = doneTask(WORKSPACE, 3);
        Run review = failedReview(task);
        when(runRepository.findById(10L)).thenReturn(Optional.of(task));

        service(enabledWith("sora")).onReviewRejected(review);

        assertThat(review.getStatus()).isEqualTo(RunStatus.BLOCKED);
        assertThat(review.getError()).contains("3회 소진");
        verify(runRepository).save(review);
        verify(runService, never()).execute(anyLong());
        verify(almClient).addComment(eq(1L), contains("⛔ 리뷰 반려"), eq(REVIEWER_BEARER));
        assertThat(task.getStatus()).isEqualTo(RunStatus.DONE);
    }

    @Test
    void rejection_without_findable_parent_blocks_review_run() {
        Run review = failedReview(doneTask(WORKSPACE, 1));
        when(runRepository.findById(10L)).thenReturn(Optional.empty());

        service(enabledWith("sora")).onReviewRejected(review);

        assertThat(review.getStatus()).isEqualTo(RunStatus.BLOCKED);
        verify(runService, never()).execute(anyLong());
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
}
