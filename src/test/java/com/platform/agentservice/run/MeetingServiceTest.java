package com.platform.agentservice.run;

import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.CommentResponse;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.ProjectResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.worker.WorkerJob;
import com.platform.agentservice.worker.WorkerProperties;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/** {@link MeetingService} — 회의 소집·참석 규칙·워커 입력·자동 에스컬레이션·회고 cron(P3b). 저장소·ALM은 목업. */
@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final long SPACE_ID = 7L;

    @Mock RunRepository runRepository;
    @Mock PersonaRepository personaRepository;
    @Mock GateRepository gateRepository;
    @Mock AlmClient almClient;
    @Mock TokenService tokenService;
    @Mock ObjectProvider<RunService> runServiceProvider;
    @Mock RunService runService;

    private final Persona planner = persona(1L, "seoyeon", PersonaRole.PLANNER, true);
    private final Persona designer = persona(2L, "dain", PersonaRole.DESIGNER, true);
    private final Persona frontend = persona(3L, "minjun", PersonaRole.FRONTEND, true);
    private final Persona backend = persona(4L, "jiho", PersonaRole.BACKEND, true);
    private final Persona ops = persona(5L, "hyun", PersonaRole.OPS, true);
    private final Persona reviewer = persona(6L, "sora", PersonaRole.REVIEWER, true);
    private final Persona retiredBackend = persona(7L, "old", PersonaRole.BACKEND, false);
    private final Persona manager = persona(8L, "boram", PersonaRole.MANAGER, true);
    private final Persona secondManager = persona(9L, "hana", PersonaRole.MANAGER, true);

    private static Persona persona(long id, String slug, PersonaRole role, boolean active) {
        Persona p = Persona.of(100L + id, slug, role, slug + "-name", "E" + id, null);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "active", active);
        return p;
    }

    @BeforeEach
    void setUp() {
        // 저장소는 id 순으로 돌려준다 — 기본 참석 규칙이 롤 순서로 다시 정렬하는지 보려고 백엔드를 앞에 섞어 둔다.
        lenient().when(personaRepository.findAll(any(Sort.class)))
                .thenReturn(List.of(backend, secondManager, planner, designer, frontend, ops, reviewer, retiredBackend, manager));
        lenient().when(tokenService.bearerFor(anyLong())).thenAnswer(inv -> "Bearer m" + inv.getArgument(0));
        lenient().when(runServiceProvider.getObject()).thenReturn(runService);
        lenient().when(runRepository.save(any(Run.class))).thenAnswer(inv -> {
            Run r = inv.getArgument(0);
            if (r.getId() == null) {
                ReflectionTestUtils.setField(r, "id", 900L);
            }
            return r;
        });
    }

    private MeetingService service(MeetingProperties props) {
        return service(props, Map.of("AGP", "https://example.com/agp.git"));
    }

    private MeetingService service(MeetingProperties props, Map<String, String> repos) {
        WorkerProperties workerProperties = new WorkerProperties("C:\\agent-work", "C:\\bundle", List.of(), "claude", 80, 40,
                "Read", "http://localhost/api/agent/mcp", repos, List.of());
        SchedulerProperties schedulerProperties = new SchedulerProperties(false, 60000L, 2, 1, "jiho", 3,
                "claude-sonnet-5", Map.of("AGP", "claude-opus-5-5"));
        return new MeetingService(runRepository, personaRepository, gateRepository, almClient, tokenService, props,
                schedulerProperties, workerProperties, runServiceProvider);
    }

    private static MeetingProperties props(boolean autoEscalation) {
        return new MeetingProperties(SPACE_ID, null, autoEscalation, true);
    }

    private static IssueResponse issue(String key, long projectId) {
        return new IssueResponse(11L, key, projectId, "에픽 제목", "에픽 본문", "epic", "todo", "high", null,
                1L, null, null, null, null, null, null, List.of(), List.of(), 0L, 1, null, null, null);
    }

    private void stubProject() {
        when(almClient.getProject(eq(PROJECT_ID), anyString())).thenReturn(new ProjectResponse(PROJECT_ID, "AGP", "agent"));
    }

    // ---- createMeeting ----

    @Test
    void planning_meeting_defaults_to_planner_designer_frontend_backend_in_role_order_with_planner_facilitating() {
        stubProject();

        MeetingService.MeetingCreated created = service(props(false))
                .createMeeting(RunType.MEETING, PROJECT_ID, null, "  로그인 개편 착수  ", null);

        assertThat(created.attendees()).containsExactly(planner, designer, frontend, backend);
        Run run = created.run();
        assertThat(run.getType()).isEqualTo(RunType.MEETING);
        assertThat(run.getTrigger()).isEqualTo(RunTrigger.USER);
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(run.getPersonaId()).isEqualTo(planner.getId());
        assertThat(run.getAttendeeIds()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(run.getIssueKey()).isEqualTo("PROJECT-1");
        assertThat(run.hasAgendaIssue()).isFalse();
        assertThat(run.getInstruction()).isEqualTo("로그인 개편 착수");
        assertThat(run.getModel()).isEqualTo("claude-opus-5-5");
        // 참석 규칙·ALM 조회는 진행자 명의다.
        verify(almClient).getProject(PROJECT_ID, "Bearer m101");
        verify(runService, never()).execute(anyLong());
    }

    @Test
    void retro_defaults_to_every_active_working_persona_and_needs_no_agenda() {
        stubProject();

        MeetingService.MeetingCreated created = service(props(false))
                .createMeeting(RunType.RETRO, PROJECT_ID, null, null, null);

        // 매니저는 실무 회고 참석자가 아니라 보고 수신자다(P3c) — "전원"에서 빠진다.
        assertThat(created.attendees()).containsExactly(planner, designer, frontend, backend, ops, reviewer);
        assertThat(created.attendees()).doesNotContain(manager, secondManager);
        assertThat(created.run().getInstruction()).isNull();
    }

    // ---- P3c: 매니저 run ----

    @Test
    void manager_run_seats_the_single_lowest_id_active_manager_and_needs_no_agenda() {
        stubProject();

        MeetingService.MeetingCreated created = service(props(false))
                .createMeeting(RunType.MANAGER, PROJECT_ID, null, null, null);

        assertThat(created.attendees()).containsExactly(manager);
        Run run = created.run();
        assertThat(run.getType()).isEqualTo(RunType.MANAGER);
        assertThat(run.getPersonaId()).isEqualTo(manager.getId());
        assertThat(run.getAttendeeIds()).containsExactly(8L);
        assertThat(run.getIssueKey()).isEqualTo("PROJECT-1");
        assertThat(run.hasAgendaIssue()).isFalse();
        assertThat(run.getInstruction()).isNull();
        verify(almClient).getProject(PROJECT_ID, "Bearer m108");
    }

    @Test
    void manager_run_without_an_active_manager_is_400_and_saves_nothing() {
        when(personaRepository.findAll(any(Sort.class))).thenReturn(List.of(planner, reviewer));

        assertThatThrownBy(() -> service(props(false)).createMeeting(RunType.MANAGER, PROJECT_ID, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MANAGER");
        verify(runRepository, never()).save(any());
    }

    @Test
    void manager_run_explicit_slug_must_be_exactly_one_manager() {
        stubProject();
        when(personaRepository.findBySlug("hana")).thenReturn(Optional.of(secondManager));
        when(personaRepository.findBySlug("boram")).thenReturn(Optional.of(manager));
        when(personaRepository.findBySlug("jiho")).thenReturn(Optional.of(backend));
        MeetingService service = service(props(false));

        assertThat(service.createMeeting(RunType.MANAGER, PROJECT_ID, null, null, List.of("hana")).attendees())
                .containsExactly(secondManager);
        assertThatThrownBy(() -> service.createMeeting(RunType.MANAGER, PROJECT_ID, null, null, List.of("jiho")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createMeeting(RunType.MANAGER, PROJECT_ID, null, null, List.of("boram", "hana")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manager_run_is_blocked_by_an_active_meeting_in_the_same_project() {
        stubProject();
        when(runRepository.existsByProjectIdAndTypeInAndStatusIn(PROJECT_ID, RunType.MEETING_TYPES,
                RunService.ACTIVE_STATUSES)).thenReturn(true);

        assertThatThrownBy(() -> service(props(false)).createMeeting(RunType.MANAGER, PROJECT_ID, null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void build_job_for_manager_carries_recent_runs_project_pending_gates_and_blocked_runs() {
        Run run = meeting(RunType.MANAGER, "PROJECT-1", List.of(8L));
        Run task = Run.queued(RunType.TASK, "AGP-9", PROJECT_ID, 4L, RunTrigger.SCHEDULER, "h", null);
        ReflectionTestUtils.setField(task, "id", 12L);
        when(runRepository.findTop30ByProjectIdAndUpdatedAtGreaterThanEqualOrderByIdDesc(eq(PROJECT_ID), any(Instant.class)))
                .thenReturn(List.of(run, task));

        Run gatedHere = Run.queued(RunType.TASK, "AGP-5", PROJECT_ID, 4L, RunTrigger.SCHEDULER, "h", null);
        ReflectionTestUtils.setField(gatedHere, "id", 20L);
        Run gatedElsewhere = Run.queued(RunType.TASK, "WEB-1", 2L, 4L, RunTrigger.SCHEDULER, "h", null);
        ReflectionTestUtils.setField(gatedElsewhere, "id", 21L);
        Gate here = Gate.request(20L, GateKind.MERGE, "PR #7 머지 승인 요청\n" + "가".repeat(100));
        ReflectionTestUtils.setField(here, "id", 3L);
        ReflectionTestUtils.setField(here, "requestedAt", Instant.now().minusSeconds(95 * 60 + 5));
        Gate elsewhere = Gate.request(21L, GateKind.PLAN, "다른 프로젝트");
        ReflectionTestUtils.setField(elsewhere, "id", 4L);
        ReflectionTestUtils.setField(elsewhere, "requestedAt", Instant.now());
        when(gateRepository.findByDecisionIsNull()).thenReturn(List.of(elsewhere, here));
        when(runRepository.findAllById(List.of(21L, 20L))).thenReturn(List.of(gatedElsewhere, gatedHere));

        Run blocked = Run.queued(RunType.TASK, "AGP-2", PROJECT_ID, 4L, RunTrigger.SCHEDULER, "h", null);
        ReflectionTestUtils.setField(blocked, "id", 15L);
        ReflectionTestUtils.setField(blocked, "attempt", 3);
        when(runRepository.findByStatusInAndProjectIdOrderByIdDesc(Set.of(RunStatus.BLOCKED), PROJECT_ID))
                .thenReturn(List.of(blocked));
        when(personaRepository.findAllById(List.of(8L))).thenReturn(List.of(manager));

        WorkerJob job = service(props(false)).buildJob(run, "Bearer x");

        WorkerJob.MeetingContext ctx = job.meeting();
        assertThat(ctx.attendees()).extracting(WorkerJob.Attendee::role).containsExactly("MANAGER");
        assertThat(ctx.recentRuns()).containsExactly("run 12 · TASK · AGP-9 · QUEUED · 시도 1");
        assertThat(ctx.pendingGates()).hasSize(1);
        String gateLine = ctx.pendingGates().get(0);
        assertThat(gateLine).startsWith("gate 3 · MERGE · run 20 · AGP-5 · 대기 95분 · PR #7 머지 승인 요청 가");
        // 요청 요약은 한 줄·앞 80자 — 순찰 자료지 결재 원문이 아니다.
        assertThat(gateLine).doesNotContain("\n").endsWith("…");
        assertThat(gateLine.substring(gateLine.indexOf("PR #7"))).hasSize(MeetingService.GATE_REQUEST_PREVIEW + 1);
        assertThat(ctx.blockedRuns()).containsExactly("run 15 · TASK · AGP-2 · BLOCKED · 시도 3");
        // 순찰은 이슈를 건드리지 않는다 — 안건 이슈 없는 대표 키.
        verifyNoInteractions(almClient);
    }

    @Test
    void build_job_for_non_manager_meetings_carries_no_patrol_data() {
        Run run = meeting(RunType.RETRO, "PROJECT-1", List.of(1L));
        when(personaRepository.findAllById(List.of(1L))).thenReturn(List.of(planner));

        WorkerJob job = service(props(false)).buildJob(run, "Bearer x");

        assertThat(job.meeting().pendingGates()).isEmpty();
        assertThat(job.meeting().blockedRuns()).isEmpty();
        verifyNoInteractions(gateRepository);
    }

    @Test
    void escalation_defaults_to_personas_who_ran_the_issue_plus_reviewer() {
        stubProject();
        when(almClient.getByKey(eq("agp-9"), anyString())).thenReturn(issue("AGP-9", PROJECT_ID));
        Run task = Run.queued(RunType.TASK, "AGP-9", PROJECT_ID, backend.getId(), RunTrigger.SCHEDULER, "h", null);
        Run oldMeeting = Run.queuedMeeting(RunType.MEETING, "AGP-9", PROJECT_ID, List.of(ops.getId()), RunTrigger.USER,
                "h", null, "x");
        when(runRepository.findTop50ByIssueKeyOrderByIdDesc("AGP-9")).thenReturn(List.of(task, oldMeeting));

        MeetingService.MeetingCreated created = service(props(false))
                .createMeeting(RunType.ESCALATION, PROJECT_ID, "agp-9", null, null);

        // 회의 run의 페르소나(ops)는 "관련 롤"이 아니다.
        assertThat(created.attendees()).containsExactly(backend, reviewer);
        assertThat(created.run().getIssueKey()).isEqualTo("AGP-9");
        assertThat(created.run().hasAgendaIssue()).isTrue();
    }

    @Test
    void escalation_without_related_runs_seats_planner_with_reviewer() {
        stubProject();

        MeetingService.MeetingCreated created = service(props(false))
                .createMeeting(RunType.ESCALATION, PROJECT_ID, null, "배포가 계속 실패함", null);

        assertThat(created.attendees()).containsExactly(planner, reviewer);
    }

    @Test
    void explicit_persona_slugs_keep_their_order_and_first_slug_facilitates() {
        stubProject();
        when(personaRepository.findBySlug("sora")).thenReturn(Optional.of(reviewer));
        when(personaRepository.findBySlug("jiho")).thenReturn(Optional.of(backend));

        MeetingService.MeetingCreated created = service(props(false))
                .createMeeting(RunType.MEETING, PROJECT_ID, null, "리뷰 규약 정비", List.of("sora", " jiho ", "sora", ""));

        assertThat(created.attendees()).containsExactly(reviewer, backend);
        assertThat(created.run().getPersonaId()).isEqualTo(reviewer.getId());
    }

    @Test
    void unknown_slug_is_404_and_inactive_slug_is_400_before_touching_alm() {
        when(personaRepository.findBySlug("ghost")).thenReturn(Optional.empty());
        when(personaRepository.findBySlug("old")).thenReturn(Optional.of(retiredBackend));
        MeetingService service = service(props(false));

        assertThatThrownBy(() -> service.createMeeting(RunType.MEETING, PROJECT_ID, null, "x", List.of("ghost")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.createMeeting(RunType.MEETING, PROJECT_ID, null, "x", List.of("old")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(almClient);
        verify(runRepository, never()).save(any());
    }

    @Test
    void missing_meeting_space_is_400() {
        assertThatThrownBy(() -> service(new MeetingProperties(null, null, false, true))
                .createMeeting(RunType.MEETING, PROJECT_ID, null, "x", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("space-id");
        verify(runRepository, never()).save(any());
    }

    @Test
    void non_meeting_type_is_400() {
        assertThatThrownBy(() -> service(props(false)).createMeeting(RunType.TASK, PROJECT_ID, null, "x", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(props(false)).createMeeting(RunType.REVIEW, PROJECT_ID, null, "x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planning_meeting_without_any_agenda_is_400() {
        assertThatThrownBy(() -> service(props(false)).createMeeting(RunType.MEETING, PROJECT_ID, " ", " ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agenda_issue_is_checked_in_alm_and_canonical_key_is_stored() {
        stubProject();
        when(almClient.getByKey(eq("agp-3"), anyString())).thenReturn(issue("AGP-3", PROJECT_ID));

        Run run = service(props(false)).createMeeting(RunType.MEETING, PROJECT_ID, " agp-3 ", null, null).run();

        assertThat(run.getIssueKey()).isEqualTo("AGP-3");
        assertThat(run.getInstruction()).isNull();
    }

    @Test
    void missing_agenda_issue_propagates_alm_error_and_saves_nothing() {
        stubProject();
        // AGP-25: alm 404는 NotFoundException(→ 소집 API 404)으로 온다 — 예전엔 409였다.
        when(almClient.getByKey(eq("AGP-404"), anyString())).thenThrow(new NotFoundException("이슈 없음"));

        assertThatThrownBy(() -> service(props(false)).createMeeting(RunType.MEETING, PROJECT_ID, "AGP-404", null, null))
                .isInstanceOf(NotFoundException.class);
        verify(runRepository, never()).save(any());
    }

    @Test
    void agenda_issue_from_another_project_is_400() {
        stubProject();
        when(almClient.getByKey(eq("OTH-1"), anyString())).thenReturn(issue("OTH-1", 99L));

        assertThatThrownBy(() -> service(props(false)).createMeeting(RunType.MEETING, PROJECT_ID, "OTH-1", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(runRepository, never()).save(any());
    }

    @Test
    void second_concurrent_meeting_in_same_project_is_409() {
        stubProject();
        when(runRepository.existsByProjectIdAndTypeInAndStatusIn(PROJECT_ID, RunType.MEETING_TYPES,
                RunService.ACTIVE_STATUSES)).thenReturn(true);

        assertThatThrownBy(() -> service(props(false)).createMeeting(RunType.RETRO, PROJECT_ID, null, null, null))
                .isInstanceOf(ConflictException.class);
        verify(runRepository, never()).save(any());
    }

    // ---- buildJob ----

    private Run meeting(RunType type, String issueKey, List<Long> attendees) {
        Run r = Run.queuedMeeting(type, issueKey, PROJECT_ID, attendees, RunTrigger.USER, "h", null, "안건");
        ReflectionTestUtils.setField(r, "id", 50L);
        return r;
    }

    @Test
    void build_job_reads_agenda_issue_and_comments_without_claiming_and_keeps_attendee_order() {
        Run run = meeting(RunType.MEETING, "AGP-3", List.of(4L, 1L));
        when(almClient.getByKey("AGP-3", "Bearer x")).thenReturn(issue("AGP-3", PROJECT_ID));
        List<CommentResponse> comments = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            comments.add(new CommentResponse(i, 11L, 1L, "c" + i, null, null));
        }
        when(almClient.comments(11L, "Bearer x")).thenReturn(comments);
        when(personaRepository.findAllById(List.of(4L, 1L))).thenReturn(List.of(planner, backend));

        WorkerJob job = service(props(false)).buildJob(run, "Bearer x");

        assertThat(job.repoUrl()).isNull();
        assertThat(job.issueTitle()).isEqualTo("에픽 제목");
        assertThat(job.recentComments()).hasSize(10).startsWith("c3").endsWith("c12");
        assertThat(job.instruction()).isEqualTo("안건");
        WorkerJob.MeetingContext ctx = job.meeting();
        assertThat(ctx.spaceId()).isEqualTo(SPACE_ID);
        assertThat(ctx.projectId()).isEqualTo(PROJECT_ID);
        assertThat(ctx.autoIssue()).isTrue();
        assertThat(ctx.attendees()).extracting(WorkerJob.Attendee::slug).containsExactly("jiho", "seoyeon");
        assertThat(ctx.recentRuns()).isEmpty();
        assertThat(ctx.approvedPlan()).isNull();
        verify(almClient, never()).update(anyLong(), any(), anyString());
    }

    @Test
    void build_job_without_agenda_issue_never_calls_alm() {
        Run run = meeting(RunType.MEETING, "PROJECT-1", List.of(1L));
        when(personaRepository.findAllById(List.of(1L))).thenReturn(List.of(planner));

        WorkerJob job = service(props(false)).buildJob(run, "Bearer x");

        assertThat(job.issueTitle()).isNull();
        assertThat(job.recentComments()).isEmpty();
        verifyNoInteractions(almClient);
    }

    @Test
    void build_job_for_retro_summarizes_recent_runs_except_itself() {
        Run run = meeting(RunType.RETRO, "PROJECT-1", List.of(1L));
        Run task = Run.queued(RunType.TASK, "AGP-9", PROJECT_ID, 4L, RunTrigger.SCHEDULER, "h", null);
        ReflectionTestUtils.setField(task, "id", 12L);
        when(runRepository.findTop30ByProjectIdAndUpdatedAtGreaterThanEqualOrderByIdDesc(eq(PROJECT_ID), any(Instant.class)))
                .thenReturn(List.of(run, task));
        when(personaRepository.findAllById(List.of(1L))).thenReturn(List.of(planner));

        WorkerJob job = service(props(false)).buildJob(run, "Bearer x");

        assertThat(job.meeting().recentRuns()).containsExactly("run 12 · TASK · AGP-9 · QUEUED · 시도 1");
    }

    @Test
    void build_job_after_plan_gate_approval_carries_the_approved_plan_from_the_ancestor_chain() {
        // 50(게이트 승인) → 51(승인 continuation, 실패) → 52(재시도): 52가 조상 사슬에서 계획을 찾는다.
        Run gated = meeting(RunType.MEETING, "PROJECT-1", List.of(1L));
        gated.start("pending", null);
        gated.parkForApproval();
        Run approved = Run.continuation(gated);
        ReflectionTestUtils.setField(approved, "id", 51L);
        approved.start("pending", null);
        approved.fail("boom");
        Run retry = Run.continuation(approved);
        ReflectionTestUtils.setField(retry, "id", 52L);
        assertThat(retry.getParentRunId()).isEqualTo(51L);
        assertThat(retry.getAttendeeIds()).containsExactly(1L);

        when(gateRepository.findByRunIdAndKindAndDecisionOrderByIdAsc(51L, GateKind.PLAN, GateDecision.APPROVE))
                .thenReturn(List.of());
        when(runRepository.findById(51L)).thenReturn(Optional.of(approved));
        Gate gate = Gate.request(50L, GateKind.PLAN, "회의록 pageId=501\n제안 이슈: 로그인 API");
        gate.approve(1L);
        when(gateRepository.findByRunIdAndKindAndDecisionOrderByIdAsc(50L, GateKind.PLAN, GateDecision.APPROVE))
                .thenReturn(List.of(gate));
        when(personaRepository.findAllById(List.of(1L))).thenReturn(List.of(planner));

        WorkerJob job = service(props(false)).buildJob(retry, "Bearer x");

        assertThat(job.meeting().approvedPlan()).isEqualTo("회의록 pageId=501\n제안 이슈: 로그인 API");
    }

    @Test
    void build_job_fails_when_meeting_space_was_unset_after_queueing() {
        Run run = meeting(RunType.MEETING, "PROJECT-1", List.of(1L));

        assertThatThrownBy(() -> service(new MeetingProperties(null, null, false, true)).buildJob(run, "Bearer x"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- onBlocked (자동 에스컬레이션) ----

    private Run blockedTask(RunTrigger trigger) {
        Run r = Run.queued(RunType.TASK, "AGP-9", PROJECT_ID, backend.getId(), trigger, "h", null);
        ReflectionTestUtils.setField(r, "id", 30L);
        ReflectionTestUtils.setField(r, "attempt", 3);
        r.start("pending", null);
        r.fail("테스트 실패: NPE in LoginService");
        r.block("사고형 실패 — 즉시 중단: boom");
        return r;
    }

    @Test
    void auto_escalation_off_does_nothing() {
        service(props(false)).onBlocked(blockedTask(RunTrigger.SCHEDULER), "사고형 실패 — 즉시 중단: boom");

        verifyNoInteractions(runRepository, runService, almClient);
    }

    @Test
    void auto_escalation_creates_and_submits_escalation_meeting_inheriting_issue_project_and_trigger() {
        Run blocked = blockedTask(RunTrigger.SCHEDULER);
        when(runRepository.findTop50ByIssueKeyOrderByIdDesc("AGP-9")).thenReturn(List.of(blocked));
        when(almClient.getByKey(eq("AGP-9"), anyString())).thenReturn(issue("AGP-9", PROJECT_ID));

        service(props(true)).onBlocked(blocked, "사고형 실패 — 즉시 중단: boom");

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        Run escalation = saved.getValue();
        assertThat(escalation.getType()).isEqualTo(RunType.ESCALATION);
        assertThat(escalation.getIssueKey()).isEqualTo("AGP-9");
        assertThat(escalation.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(escalation.getTrigger()).isEqualTo(RunTrigger.SCHEDULER);
        assertThat(escalation.getAttendeeIds()).containsExactly(backend.getId(), reviewer.getId());
        assertThat(escalation.getModel()).isEqualTo("claude-opus-5-5");
        assertThat(escalation.getInstruction())
                .startsWith("AGP-9 자동 진행 중단 — 원인 분석과 사람에게 물을 질문 목록\n")
                .contains("원 run: 30(TASK)")
                .contains("차단 사유: 사고형 실패 — 즉시 중단: boom")
                .contains("NPE in LoginService");
        verify(runService).execute(900L);
        verify(almClient).addComment(eq(11L), org.mockito.ArgumentMatchers.contains("자동 에스컬레이션 회의 run 900"), anyString());
    }

    @Test
    void auto_escalation_never_escalates_a_meeting_run_to_prevent_recursion() {
        Run escalation = Run.queuedMeeting(RunType.ESCALATION, "AGP-9", PROJECT_ID, List.of(backend.getId()),
                RunTrigger.SCHEDULER, "h", null, "x");
        ReflectionTestUtils.setField(escalation, "id", 31L);
        escalation.start("pending", null);
        escalation.fail("x");
        escalation.block("사고형 실패 — 즉시 중단: boom");

        service(props(true)).onBlocked(escalation, "사고형 실패 — 즉시 중단: boom");

        verifyNoInteractions(runRepository, runService, almClient);
    }

    @Test
    void auto_escalation_skips_when_issue_already_has_an_active_escalation() {
        when(runRepository.existsByIssueKeyAndTypeAndStatusIn("AGP-9", RunType.ESCALATION, RunService.ACTIVE_STATUSES))
                .thenReturn(true);

        service(props(true)).onBlocked(blockedTask(RunTrigger.USER), "사고형 실패 — 즉시 중단: boom");

        verify(runRepository, never()).save(any());
        verifyNoInteractions(runService);
    }

    @Test
    void auto_escalation_skips_without_meeting_space() {
        service(new MeetingProperties(null, null, true, true)).onBlocked(blockedTask(RunTrigger.USER), "r");

        verifyNoInteractions(runRepository, runService);
    }

    @Test
    void auto_escalation_submission_rejection_keeps_run_queued_without_throwing() {
        Run blocked = blockedTask(RunTrigger.SCHEDULER);
        when(runRepository.findTop50ByIssueKeyOrderByIdDesc("AGP-9")).thenReturn(List.of(blocked));
        doThrow(new TaskRejectedException("full")).when(runService).execute(900L);

        service(props(true)).onBlocked(blocked, "r");

        verify(runRepository).save(any(Run.class));
    }

    // ---- runRetros ----

    @Test
    void retro_cron_creates_one_retro_per_mapped_project_and_skips_busy_or_unknown_projects() {
        Map<String, String> repos = new LinkedHashMap<>();
        repos.put("agp", "https://example.com/agp.git");
        repos.put("WEB", "https://example.com/web.git");
        repos.put("GONE", "https://example.com/gone.git");
        when(almClient.listProjects("Bearer m101")).thenReturn(List.of(
                new ProjectResponse(1L, "AGP", "agent"), new ProjectResponse(2L, "WEB", "web")));
        lenient().when(runRepository.existsByProjectIdAndTypeInAndStatusIn(eq(2L), eq(RunType.MEETING_TYPES), any()))
                .thenReturn(true);

        service(props(false), repos).runRetros();

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository, times(1)).save(saved.capture());
        Run retro = saved.getValue();
        assertThat(retro.getType()).isEqualTo(RunType.RETRO);
        assertThat(retro.getTrigger()).isEqualTo(RunTrigger.SCHEDULER);
        assertThat(retro.getProjectId()).isEqualTo(1L);
        assertThat(retro.getIssueKey()).isEqualTo("PROJECT-1");
        assertThat(retro.getInstruction()).isEqualTo(MeetingService.RETRO_AGENDA);
        assertThat(retro.getAttendeeIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        verify(runService).execute(900L);
    }

    @Test
    void retro_cron_does_nothing_without_space_or_repo_mapping() {
        service(new MeetingProperties(null, "0 0 18 * * *", false, true)).runRetros();
        service(props(false), Map.of()).runRetros();

        verifyNoInteractions(almClient, runService);
        verify(runRepository, never()).save(any());
    }

    @Test
    void manager_cron_creates_one_manager_run_per_mapped_project_sharing_the_meeting_guard() {
        Map<String, String> repos = new LinkedHashMap<>();
        repos.put("agp", "https://example.com/agp.git");
        repos.put("WEB", "https://example.com/web.git");
        when(almClient.listProjects("Bearer m108")).thenReturn(List.of(
                new ProjectResponse(1L, "AGP", "agent"), new ProjectResponse(2L, "WEB", "web")));
        // WEB에는 실무 회의가 돌고 있다 — 매니저 순찰도 같은 "회의 1건" 가드에 걸린다.
        lenient().when(runRepository.existsByProjectIdAndTypeInAndStatusIn(eq(2L), eq(RunType.MEETING_TYPES), any()))
                .thenReturn(true);

        service(props(false), repos).runManagerRounds();

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository, times(1)).save(saved.capture());
        Run round = saved.getValue();
        assertThat(round.getType()).isEqualTo(RunType.MANAGER);
        assertThat(round.getTrigger()).isEqualTo(RunTrigger.SCHEDULER);
        assertThat(round.getProjectId()).isEqualTo(1L);
        assertThat(round.getIssueKey()).isEqualTo("PROJECT-1");
        assertThat(round.getInstruction()).isEqualTo(MeetingService.MANAGER_AGENDA);
        assertThat(round.getAttendeeIds()).containsExactly(8L);
        assertThat(round.getModel()).isEqualTo("claude-opus-5-5");
        verify(runService).execute(900L);
    }

    @Test
    void manager_cron_skips_the_whole_tick_without_a_manager_persona() {
        when(personaRepository.findAll(any(Sort.class))).thenReturn(List.of(planner, backend, reviewer));

        service(props(false)).runManagerRounds();

        verifyNoInteractions(almClient, runService);
        verify(runRepository, never()).save(any());
    }

    @Test
    void default_attendees_exclude_inactive_personas() {
        List<Persona> all = service(props(false)).defaultAttendees(RunType.RETRO, Set.of());

        assertThat(all).doesNotContain(retiredBackend);
    }
}
