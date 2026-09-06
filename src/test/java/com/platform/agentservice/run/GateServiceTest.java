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
 * {@link GateService} — 순수 Mockito 단위테스트(P2a T5). 스프링 프록시 없이 직접 new하므로
 * 활성 트랜잭션이 없다 — {@code afterCommit} 액션(execute 킥·코멘트)이 즉시(동기) 실행되어
 * 별도 커밋 시뮬레이션 없이 바로 단언할 수 있다({@link GateService} javadoc 참고).
 */
@ExtendWith(MockitoExtension.class)
class GateServiceTest {

    private static final long PERSONA_ID = 5L;
    private static final long PERSONA_MEMBER_ID = 42L;
    private static final String BEARER = "Bearer persona-token";
    private static final String ISSUE_KEY = "AGP-9";
    private static final long DECIDER_ID = 1L;

    @Mock GateRepository gateRepository;
    @Mock RunRepository runRepository;
    @Mock RunService runService;
    @Mock PersonaRepository personaRepository;
    @Mock TokenService tokenService;
    @Mock AlmClient almClient;

    private GateService gateService;

    @BeforeEach
    void setUp() {
        gateService = new GateService(gateRepository, runRepository, runService, personaRepository, tokenService, almClient);
    }

    private Run waitingRun(long id) {
        Run run = Run.queued(RunType.TASK, ISSUE_KEY, 1L, PERSONA_ID, RunTrigger.USER, "harness://default", null);
        ReflectionTestUtils.setField(run, "id", id);
        run.start("/work", 1L);
        run.parkForApproval();
        return run;
    }

    private Gate gate(long id, long runId, GateKind kind) {
        Gate gate = Gate.request(runId, kind, "요청 내용");
        ReflectionTestUtils.setField(gate, "id", id);
        return gate;
    }

    private void stubComment() {
        Persona persona = Persona.of(PERSONA_MEMBER_ID, "jiho", PersonaRole.BACKEND, "지호", "🔧", null);
        ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.of(persona));
        when(tokenService.bearerFor(PERSONA_MEMBER_ID)).thenReturn(BEARER);
        IssueResponse issue = new IssueResponse(1L, ISSUE_KEY, 1L, "제목", "본문", "task", "inprogress", "high",
                null, 1L, null, null, null, null, null, null, List.of(), List.of(), 0L, 1, null, null, null);
        when(almClient.getByKey(ISSUE_KEY, BEARER)).thenReturn(issue);
        when(almClient.addComment(eq(1L), anyString(), eq(BEARER)))
                .thenReturn(new CommentResponse(9L, 1L, PERSONA_MEMBER_ID, "body", null, null));
    }

    // ---- approve ----

    @Test
    void approve_creates_continuation_cancels_original_kicks_execute_and_comments() {
        Run original = waitingRun(10L);
        Gate gate = gate(100L, 10L, GateKind.MERGE);
        when(gateRepository.findById(100L)).thenReturn(Optional.of(gate));
        when(runRepository.findById(10L)).thenReturn(Optional.of(original));
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> {
            Run r = inv.getArgument(0);
            if (r.getId() == null) {
                ReflectionTestUtils.setField(r, "id", 11L);
            }
            return r;
        });
        stubComment();

        gateService.approve(100L, DECIDER_ID);

        assertThat(gate.getDecision()).isEqualTo(GateDecision.APPROVE);
        assertThat(gate.getDecidedBy()).isEqualTo(DECIDER_ID);
        assertThat(original.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(original.getError()).contains("게이트 승인").contains("11");

        ArgumentCaptor<Run> savedCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(savedCaptor.capture());
        Run continuationRun = savedCaptor.getValue();
        assertThat(continuationRun.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(continuationRun.getAttempt()).isEqualTo(2);
        assertThat(continuationRun.getIssueKey()).isEqualTo(ISSUE_KEY);

        verify(runService).execute(11L);
        verify(almClient).addComment(eq(1L), contains("게이트 승인"), eq(BEARER));
    }

    @Test
    void approve_double_decide_throws_conflict() {
        Run original = waitingRun(10L);
        Gate gate = gate(100L, 10L, GateKind.MERGE);
        gate.approve(9L); // 이미 결정됨
        when(gateRepository.findById(100L)).thenReturn(Optional.of(gate));
        when(runRepository.findById(10L)).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> gateService.approve(100L, DECIDER_ID))
                .isInstanceOf(ConflictException.class);

        verify(runRepository, never()).save(any());
        verify(runService, never()).execute(anyLong());
    }

    @Test
    void approve_run_already_terminal_throws_conflict_reload_guard() {
        Run original = waitingRun(10L);
        original.cancel(); // 다른 경로로 이미 종결됨 -> 더 이상 WAITING_APPROVAL이 아니다
        Gate gate = gate(100L, 10L, GateKind.MERGE);
        when(gateRepository.findById(100L)).thenReturn(Optional.of(gate));
        when(runRepository.findById(10L)).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> gateService.approve(100L, DECIDER_ID))
                .isInstanceOf(ConflictException.class);

        assertThat(gate.getDecision()).isNull(); // 결정은 기록되지 않는다
        verify(runService, never()).execute(anyLong());
    }

    @Test
    void approve_missing_gate_throws_not_found() {
        when(gateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateService.approve(999L, DECIDER_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void approve_missing_run_throws_not_found() {
        Gate gate = gate(100L, 10L, GateKind.MERGE);
        when(gateRepository.findById(100L)).thenReturn(Optional.of(gate));
        when(runRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateService.approve(100L, DECIDER_ID))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- reject ----

    @Test
    void reject_cancels_run_and_comments_without_continuation() {
        Run original = waitingRun(10L);
        Gate gate = gate(100L, 10L, GateKind.ESCALATION);
        when(gateRepository.findById(100L)).thenReturn(Optional.of(gate));
        when(runRepository.findById(10L)).thenReturn(Optional.of(original));
        stubComment();

        gateService.reject(100L, DECIDER_ID);

        assertThat(gate.getDecision()).isEqualTo(GateDecision.REJECT);
        assertThat(gate.getDecidedBy()).isEqualTo(DECIDER_ID);
        assertThat(original.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(original.getError()).contains("게이트 거절");

        verify(runRepository, never()).save(any());
        verify(runService, never()).execute(anyLong());
        verify(almClient).addComment(eq(1L), contains("게이트 거절"), eq(BEARER));
    }

    @Test
    void reject_double_decide_throws_conflict() {
        Run original = waitingRun(10L);
        Gate gate = gate(100L, 10L, GateKind.PLAN);
        gate.reject(9L);
        when(gateRepository.findById(100L)).thenReturn(Optional.of(gate));
        when(runRepository.findById(10L)).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> gateService.reject(100L, DECIDER_ID))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void reject_run_already_terminal_throws_conflict_reload_guard() {
        Run original = waitingRun(10L);
        original.cancel();
        Gate gate = gate(100L, 10L, GateKind.PLAN);
        when(gateRepository.findById(100L)).thenReturn(Optional.of(gate));
        when(runRepository.findById(10L)).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> gateService.reject(100L, DECIDER_ID))
                .isInstanceOf(ConflictException.class);

        assertThat(gate.getDecision()).isNull();
    }
}
