package com.platform.agentservice.run;

import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RunResumeService} — {@link GateServiceTest}와 정확히 같은 패턴의 순수 Mockito
 * 단위테스트다(fix round 2, P2a T7 재리뷰). 스프링 프록시 없이 직접 new하므로 활성
 * 트랜잭션이 없다 — {@code afterCommit} 액션(execute 킥·코멘트)이 즉시(동기) 실행된다.
 */
@ExtendWith(MockitoExtension.class)
class RunResumeServiceTest {

    private static final long PERSONA_ID = 5L;
    private static final long PERSONA_MEMBER_ID = 42L;
    private static final String BEARER = "Bearer persona-token";
    private static final String ISSUE_KEY = "AGP-9";

    @Mock RunRepository runRepository;
    @Mock RunService runService;
    @Mock PersonaRepository personaRepository;
    @Mock TokenService tokenService;
    @Mock AlmClient almClient;

    private RunResumeService runResumeService;

    @BeforeEach
    void setUp() {
        runResumeService = new RunResumeService(runRepository, runService, personaRepository, tokenService, almClient);
    }

    private Run blockedRun(long id) {
        Run run = Run.queued(RunType.TASK, ISSUE_KEY, 1L, PERSONA_ID, RunTrigger.SCHEDULER, "harness://default", null);
        ReflectionTestUtils.setField(run, "id", id);
        run.start("/work", 1L);
        run.fail("boom");
        run.block("3회 실패 — 사람 확인 필요");
        return run;
    }

    private Run failedRun(long id) {
        Run run = Run.queued(RunType.TASK, ISSUE_KEY, 1L, PERSONA_ID, RunTrigger.SCHEDULER, "harness://default", null);
        ReflectionTestUtils.setField(run, "id", id);
        run.start("/work", 1L);
        run.fail("boom");
        return run;
    }

    private void stubComment() {
        Persona persona = Persona.of(PERSONA_MEMBER_ID, "jiho", PersonaRole.BACKEND, "지호", "🔧", null);
        ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.of(persona));
        when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);
        IssueResponse issue = new IssueResponse(1L, ISSUE_KEY, 1L, "제목", "본문", "task", "blocked-ish", "high",
                null, 1L, null, null, null, null, null, null, List.of(), List.of(), 0L, 1, null, null, null);
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(issue);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));
    }

    @Test
    void resume_creates_continuation_cancels_original_kicks_execute_and_comments() {
        Run original = blockedRun(10L);
        when(runRepository.findById(10L)).thenReturn(Optional.of(original));
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> {
            Run r = inv.getArgument(0);
            if (r.getId() == null) {
                ReflectionTestUtils.setField(r, "id", 11L);
            }
            return r;
        });
        stubComment();

        runResumeService.resume(10L);

        assertThat(original.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(original.getError()).contains("사람 확인 후 재개").contains("11");

        ArgumentCaptor<Run> savedCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(savedCaptor.capture());
        Run continuationRun = savedCaptor.getValue();
        assertThat(continuationRun.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(continuationRun.getAttempt()).isEqualTo(2); // 원 run(attempt=1)의 continuation
        assertThat(continuationRun.getIssueKey()).isEqualTo(ISSUE_KEY);

        verify(runService).execute(11L);
        verify(almClient).addComment(eq(1L), contains("사람 확인 후 재개"), eq(BEARER));
    }

    /**
     * 최종 리뷰 I1: BLOCKED뿐 아니라 FAILED도 재개 대상으로 넓혔지만, 그 외 상태(RUNNING 등)는
     * 여전히 409다 — RESUMABLE = {BLOCKED, FAILED}만 통과한다.
     */
    @Test
    void resume_non_resumable_run_throws_conflict() {
        Run running = Run.queued(RunType.TASK, ISSUE_KEY, 1L, PERSONA_ID, RunTrigger.SCHEDULER, "harness://default", null);
        ReflectionTestUtils.setField(running, "id", 10L);
        running.start("/work", 1L); // RUNNING — BLOCKED도 FAILED도 아니다

        when(runRepository.findById(10L)).thenReturn(Optional.of(running));

        assertThatThrownBy(() -> runResumeService.resume(10L)).isInstanceOf(ConflictException.class);

        verify(runRepository, never()).save(any());
        verify(runService, never()).execute(anyLong());
    }

    /**
     * 최종 리뷰 I1: 워커가 스스로 report_result(FAILED)로 종결한 뒤 {@code
     * RunService.handleRetryOrBlock}이 예외로 실패해 run이 FAILED에 멈춘 잔여 케이스를
     * 흉내낸다 — 이 경로도 게이트 없이 사람이 재개할 수 있어야 한다(BLOCKED와 동일 패턴).
     */
    @Test
    void resume_from_failed_run_creates_continuation_and_cancels_original() {
        Run original = failedRun(20L);
        when(runRepository.findById(20L)).thenReturn(Optional.of(original));
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> {
            Run r = inv.getArgument(0);
            if (r.getId() == null) {
                ReflectionTestUtils.setField(r, "id", 21L);
            }
            return r;
        });
        stubComment();

        runResumeService.resume(20L);

        assertThat(original.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(original.getError()).contains("사람 확인 후 재개").contains("21");

        ArgumentCaptor<Run> savedCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(savedCaptor.capture());
        Run continuationRun = savedCaptor.getValue();
        assertThat(continuationRun.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(continuationRun.getAttempt()).isEqualTo(2);

        verify(runService).execute(21L);
        verify(almClient).addComment(eq(1L), contains("사람 확인 후 재개"), eq(BEARER));
    }

    @Test
    void resume_missing_run_throws_not_found() {
        when(runRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runResumeService.resume(999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void resume_comment_failure_still_completes_state_transition_and_execute_kick() {
        Run original = blockedRun(10L);
        when(runRepository.findById(10L)).thenReturn(Optional.of(original));
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> {
            Run r = inv.getArgument(0);
            if (r.getId() == null) {
                ReflectionTestUtils.setField(r, "id", 11L);
            }
            return r;
        });
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.empty()); // 코멘트 시도가 실패하게 만든다

        runResumeService.resume(10L);

        assertThat(original.getStatus()).isEqualTo(RunStatus.CANCELLED);
        verify(runService).execute(11L); // 코멘트 실패와 무관하게 실행 킥은 된다
    }
}
