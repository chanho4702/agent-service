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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 회의 run 4종(P3b, AGP-15 — 스펙 §6)의 소집·프롬프트 재료·자동 트리거. 회의 자체는 별도 엔진이 아니라 기존 워커
 * 파이프라인의 run이다(D-P3b-1) — 이 서비스는 run을 만들고 {@link RunService#execute}에 넘길 재료만 만든다.
 *
 * <p><b>참석 규칙(D-P3b-4)</b>: {@code personaSlugs}를 주면 그 순서 그대로(첫 번째가 진행자), 생략하면 활성 페르소나 중
 * 계획=PLANNER·DESIGNER·FRONTEND·BACKEND, 회고=전원, 에스컬레이션=그 이슈에 run을 돌린 페르소나(관련 롤)+REVIEWER를
 * 롤 순서(PLANNER→…→REVIEWER)·id 순으로 세운다. 진행자는 run 소유 페르소나라 run 토큰·기록 명의가 된다.
 *
 * <p><b>순환 의존</b>: {@link RunService}가 빌드·에스컬레이션 훅으로 이 서비스를 부르고, 이 서비스는 새 run 실행을
 * {@link RunService#execute}(@Async)로 제출한다 — {@link ReviewService}와 같은 이유로 {@link ObjectProvider}로 지연 조회한다.
 */
@Slf4j
@Service
public class MeetingService {

    static final Set<PersonaRole> PLANNING_ROLES =
            EnumSet.of(PersonaRole.PLANNER, PersonaRole.DESIGNER, PersonaRole.FRONTEND, PersonaRole.BACKEND);
    static final Duration RETRO_WINDOW = Duration.ofHours(24);
    static final String RETRO_AGENDA = "정기 회고 — 최근 24시간 run 결과(완료·실패·반려·차단·게이트)를 돌아보고 개선 이슈를 도출한다.";
    private static final int RECENT_COMMENTS_LIMIT = 10;
    private static final int ESCALATION_ERROR_TAIL = 1000;
    private static final int ANCESTOR_WALK_LIMIT = 20;
    private static final Comparator<Persona> ROLE_ORDER =
            Comparator.comparing(Persona::getRole).thenComparing(Persona::getId);

    private final RunRepository runRepository;
    private final PersonaRepository personaRepository;
    private final GateRepository gateRepository;
    private final AlmClient almClient;
    private final TokenService tokenService;
    private final MeetingProperties meetingProperties;
    private final SchedulerProperties schedulerProperties;
    private final WorkerProperties workerProperties;
    private final ObjectProvider<RunService> runServiceProvider;

    public MeetingService(RunRepository runRepository, PersonaRepository personaRepository, GateRepository gateRepository,
                          AlmClient almClient, TokenService tokenService, MeetingProperties meetingProperties,
                          SchedulerProperties schedulerProperties, WorkerProperties workerProperties,
                          ObjectProvider<RunService> runServiceProvider) {
        this.runRepository = runRepository;
        this.personaRepository = personaRepository;
        this.gateRepository = gateRepository;
        this.almClient = almClient;
        this.tokenService = tokenService;
        this.meetingProperties = meetingProperties;
        this.schedulerProperties = schedulerProperties;
        this.workerProperties = workerProperties;
        this.runServiceProvider = runServiceProvider;
    }

    /** 소집 결과 — 저장된 run과 해석된 참석자(진행자 먼저). */
    public record MeetingCreated(Run run, List<Persona> attendees) {
    }

    /**
     * {@code POST /api/agent/meetings} — 회의 run을 QUEUED로 만든다. 실행 제출은 호출자(컨트롤러) 몫이다
     * ({@link RunService#createUserRun}과 같은 이유 — 같은 빈 안 {@code @Async} 자기호출 회피).
     */
    public MeetingCreated createMeeting(RunType type, long projectId, String agendaIssueKey, String agenda,
                                        List<String> personaSlugs) {
        if (type == null || !type.isMeeting()) {
            throw new IllegalArgumentException("회의 종류는 MEETING·RETRO·ESCALATION 중 하나여야 합니다: " + type);
        }
        if (!meetingProperties.hasSpace()) {
            throw new IllegalArgumentException("회의록 스페이스가 설정되지 않았습니다(platform.agent.meetings.space-id)");
        }
        String issueKeyInput = isBlank(agendaIssueKey) ? null : agendaIssueKey.trim();
        String instruction = isBlank(agenda) ? null : agenda.trim();
        if (type != RunType.RETRO && issueKeyInput == null && instruction == null) {
            // 회고는 최근 run 자체가 안건이지만, 계획·에스컬레이션은 무엇을 논의할지 없으면 빈 회의가 예산만 쓴다.
            throw new IllegalArgumentException(type + " 회의에는 agendaIssueKey 또는 agenda가 필요합니다");
        }

        List<Persona> attendees = resolveAttendees(type, personaSlugs,
                issueKeyInput == null ? Set.of() : relatedPersonaIds(issueKeyInput.toUpperCase()));
        Persona facilitator = attendees.get(0);
        String bearer = tokenService.bearerFor(facilitator.getMemberId());

        ProjectResponse project = almClient.getProject(projectId, bearer);
        String issueKey;
        if (issueKeyInput != null) {
            IssueResponse issue = almClient.getByKey(issueKeyInput, bearer);
            if (issue.projectId() != projectId) {
                throw new IllegalArgumentException("안건 이슈 " + issue.key() + "는 프로젝트 " + projectId + " 소속이 아닙니다");
            }
            issueKey = issue.key();
        } else {
            issueKey = Run.projectIssueKey(projectId);
        }
        if (hasActiveMeeting(projectId)) {
            throw new ConflictException("이 프로젝트에 이미 진행 중인 회의 run이 있습니다: projectId=" + projectId);
        }

        Run run = runRepository.save(Run.queuedMeeting(type, issueKey, projectId, ids(attendees), RunTrigger.USER,
                RunService.DEFAULT_HARNESS_REF, schedulerProperties.modelFor(project.key()), instruction));
        log.info("회의 run={} 소집({}, projectId={}, issueKey={}, 참석 {}명)", run.getId(), type, projectId, issueKey,
                attendees.size());
        return new MeetingCreated(run, attendees);
    }

    /**
     * 회의 run의 워커 입력(D-P3b-5). claim하지 않는다 — 회의는 이슈 작업이 아니다. 안건 이슈가 있으면 본문·최근 코멘트만
     * 읽는다. 회의록 스페이스가 실행 시점에 비어 있으면 회의록을 남길 곳이 없으므로 실패시킨다.
     */
    public WorkerJob buildJob(Run run, String bearer) {
        if (!meetingProperties.hasSpace()) {
            throw new IllegalStateException("회의록 스페이스가 설정되지 않았습니다(platform.agent.meetings.space-id)");
        }
        String title = null;
        String body = null;
        List<String> recentComments = List.of();
        if (run.hasAgendaIssue()) {
            IssueResponse issue = almClient.getByKey(run.getIssueKey(), bearer);
            title = issue.title();
            body = issue.description();
            List<CommentResponse> comments = almClient.comments(issue.id(), bearer);
            recentComments = comments.stream()
                    .skip(Math.max(0, comments.size() - RECENT_COMMENTS_LIMIT))
                    .map(CommentResponse::body)
                    .toList();
        }
        List<String> recentRuns = run.getType() == RunType.RETRO ? recentRunLines(run) : List.of();
        WorkerJob.MeetingContext meeting = new WorkerJob.MeetingContext(run.getProjectId(), meetingProperties.spaceId(),
                meetingProperties.autoIssue(), attendeesOf(run), recentRuns, approvedPlan(run));
        return new WorkerJob(null, title, body, recentComments, run.getInstruction(), meeting);
    }

    /**
     * BLOCKED 승격 직후 자동 에스컬레이션(D-P3b-4③). 호출자는 이미 BLOCKED를 커밋했다 — 여기서 새면 그 run 처리 흐름을
     * 망치므로 호출자가 감싼다. 원 run이 회의 run이면 만들지 않는다(에스컬레이션이 막혀 또 에스컬레이션을 낳는 재귀 방지).
     */
    public void onBlocked(Run blockedRun, String reason) {
        if (!meetingProperties.autoEscalation() || blockedRun.getType().isMeeting()) {
            return;
        }
        if (!meetingProperties.hasSpace()) {
            log.warn("run={} 자동 에스컬레이션 건너뜀 — 회의록 스페이스 미설정", blockedRun.getId());
            return;
        }
        String issueKey = blockedRun.getIssueKey();
        if (runRepository.existsByIssueKeyAndTypeAndStatusIn(issueKey, RunType.ESCALATION, RunService.ACTIVE_STATUSES)) {
            log.info("run={} 자동 에스컬레이션 건너뜀 — {}에 진행 중인 에스컬레이션이 있습니다", blockedRun.getId(), issueKey);
            return;
        }
        List<Persona> attendees = defaultAttendees(RunType.ESCALATION, relatedPersonaIds(issueKey));
        if (attendees.isEmpty()) {
            log.warn("run={} 자동 에스컬레이션 건너뜀 — 참석할 활성 페르소나가 없습니다", blockedRun.getId());
            return;
        }
        String instruction = issueKey + " " + schedulerProperties.retryMaxAttempts()
                + "회 실패/반려 — 원인 분석과 사람에게 물을 질문 목록\n"
                + "원 run: " + blockedRun.getId() + "(" + blockedRun.getType() + ")\n"
                + "차단 사유: " + reason + "\n"
                + "실패 기록(끝부분):\n" + tail(blockedRun.getError());
        Run escalation = runRepository.save(Run.queuedMeeting(RunType.ESCALATION, issueKey, blockedRun.getProjectId(),
                ids(attendees), blockedRun.getTrigger(), RunService.DEFAULT_HARNESS_REF,
                schedulerProperties.modelFor(RunService.projectKeyOf(issueKey)), instruction));
        log.info("run={} BLOCKED → 자동 에스컬레이션 회의 run={} 생성", blockedRun.getId(), escalation.getId());
        submit(escalation.getId());
        commentBestEffort(attendees.get(0), issueKey, "🆘 자동 에스컬레이션 회의 run " + escalation.getId() + " 소집 — 참석: "
                + attendees.stream().map(Persona::getName).collect(Collectors.joining(", ")));
    }

    /**
     * 회고 주기 실행(D-P3b-4②) — 스케줄러 repos 매핑의 프로젝트마다 RETRO run 하나. 프로젝트 키→id는 진행자 명의로
     * ALM 프로젝트 목록을 한 번 읽어 푼다. 프로젝트 하나의 실패가 다른 프로젝트 회고를 막지 않게 건별로 삼킨다.
     */
    public void runRetros() {
        if (!meetingProperties.hasSpace()) {
            log.warn("회고 cron 건너뜀 — 회의록 스페이스 미설정(platform.agent.meetings.space-id)");
            return;
        }
        Map<String, String> repos = workerProperties.repos();
        if (repos == null || repos.isEmpty()) {
            log.info("회고 cron 건너뜀 — 리포 매핑(platform.agent.worker.repos)에 프로젝트가 없습니다");
            return;
        }
        List<Persona> attendees = defaultAttendees(RunType.RETRO, Set.of());
        if (attendees.isEmpty()) {
            log.warn("회고 cron 건너뜀 — 활성 페르소나가 없습니다");
            return;
        }
        String bearer = tokenService.bearerFor(attendees.get(0).getMemberId());
        List<ProjectResponse> projects = almClient.listProjects(bearer);
        for (String key : repos.keySet()) {
            try {
                ProjectResponse project = projects.stream()
                        .filter(p -> p.key() != null && p.key().equalsIgnoreCase(key))
                        .findFirst().orElse(null);
                if (project == null) {
                    log.warn("회고 cron — 프로젝트 키 {}를 ALM에서 찾을 수 없어 건너뜁니다", key);
                    continue;
                }
                if (hasActiveMeeting(project.id())) {
                    log.info("회고 cron — 프로젝트 {}에 진행 중인 회의가 있어 건너뜁니다", project.key());
                    continue;
                }
                Run retro = runRepository.save(Run.queuedMeeting(RunType.RETRO, Run.projectIssueKey(project.id()),
                        project.id(), ids(attendees), RunTrigger.SCHEDULER, RunService.DEFAULT_HARNESS_REF,
                        schedulerProperties.modelFor(project.key()), RETRO_AGENDA));
                log.info("회고 cron — 프로젝트 {} 회고 run={} 생성", project.key(), retro.getId());
                submit(retro.getId());
            } catch (Exception e) {
                log.warn("회고 cron — 프로젝트 {} 회고 생성 실패, 다음 프로젝트로 계속합니다: {}", key, e.getMessage());
            }
        }
    }

    // ---- 내부 ----

    boolean hasActiveMeeting(long projectId) {
        return runRepository.existsByProjectIdAndTypeInAndStatusIn(projectId, RunType.MEETING_TYPES,
                RunService.ACTIVE_STATUSES);
    }

    /** 지정 슬러그는 순서 그대로(첫 번째 = 진행자) — 없는 슬러그는 404, 비활성은 400. 생략하면 롤 기본 규칙. */
    List<Persona> resolveAttendees(RunType type, List<String> personaSlugs, Set<Long> relatedPersonaIds) {
        List<String> slugs = personaSlugs == null ? List.of()
                : personaSlugs.stream().filter(s -> !isBlank(s)).map(String::trim).distinct().toList();
        if (!slugs.isEmpty()) {
            List<Persona> picked = new ArrayList<>();
            for (String slug : slugs) {
                Persona persona = personaRepository.findBySlug(slug)
                        .orElseThrow(() -> new NotFoundException("페르소나를 찾을 수 없습니다: " + slug));
                if (!persona.isActive()) {
                    throw new IllegalArgumentException("비활성 페르소나는 회의에 참석할 수 없습니다: " + slug);
                }
                picked.add(persona);
            }
            return picked;
        }
        List<Persona> defaults = defaultAttendees(type, relatedPersonaIds);
        if (defaults.isEmpty()) {
            throw new IllegalArgumentException(type + " 회의에 참석할 활성 페르소나가 없습니다");
        }
        return defaults;
    }

    /**
     * 롤 기본 참석 규칙. 에스컬레이션의 "관련 롤"은 그 이슈에 run을 돌린 페르소나(작업자·리뷰어)다 — 아무도 없으면(사람이
     * 이슈 없이 소집 등) 기획이 대신 앉는다. REVIEWER는 항상 참석한다.
     */
    List<Persona> defaultAttendees(RunType type, Set<Long> relatedPersonaIds) {
        List<Persona> active = personaRepository.findAll(Sort.by("id")).stream().filter(Persona::isActive).toList();
        List<Persona> picked = switch (type) {
            case MEETING -> active.stream().filter(p -> PLANNING_ROLES.contains(p.getRole())).toList();
            case RETRO -> active;
            case ESCALATION -> {
                List<Persona> related = active.stream().filter(p -> relatedPersonaIds.contains(p.getId())).toList();
                List<Persona> base = related.isEmpty()
                        ? active.stream().filter(p -> p.getRole() == PersonaRole.PLANNER).toList()
                        : related;
                Set<Persona> union = new LinkedHashSet<>(base);
                active.stream().filter(p -> p.getRole() == PersonaRole.REVIEWER).forEach(union::add);
                yield List.copyOf(union);
            }
            default -> throw new IllegalArgumentException("회의 run 종류가 아닙니다: " + type);
        };
        return picked.stream().sorted(ROLE_ORDER).toList();
    }

    /** 그 이슈에서 run을 돌린 페르소나(회의 run 제외) — 에스컬레이션 "관련 롤". */
    private Set<Long> relatedPersonaIds(String issueKey) {
        return runRepository.findTop50ByIssueKeyOrderByIdDesc(issueKey).stream()
                .filter(r -> !r.getType().isMeeting())
                .map(Run::getPersonaId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 저장된 참석자 순서를 지킨다. 그사이 삭제된 페르소나는 빼고, 전부 사라졌으면 진행자(run 소유)만 남긴다. */
    private List<WorkerJob.Attendee> attendeesOf(Run run) {
        List<Long> ids = run.getAttendeeIds().isEmpty() ? List.of(run.getPersonaId()) : run.getAttendeeIds();
        Map<Long, Persona> byId = personaRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Persona::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<WorkerJob.Attendee> attendees = ids.stream().map(byId::get).filter(Objects::nonNull)
                .map(MeetingService::toAttendee).toList();
        if (!attendees.isEmpty()) {
            return attendees;
        }
        Persona owner = personaRepository.findById(run.getPersonaId())
                .orElseThrow(() -> new NotFoundException("회의 진행자 페르소나를 찾을 수 없습니다: " + run.getPersonaId()));
        return List.of(toAttendee(owner));
    }

    private static WorkerJob.Attendee toAttendee(Persona p) {
        return new WorkerJob.Attendee(p.getSlug(), p.getName(), p.getRole().name(), p.getEmoji(), p.getVoicePrompt());
    }

    /** 회고 자료 — 워커에는 run을 조회하는 도구가 없어서 서버가 요약을 실어 준다. 자기 자신(회의 run)은 뺀다. */
    private List<String> recentRunLines(Run run) {
        return runRepository.findTop30ByProjectIdAndUpdatedAtGreaterThanEqualOrderByIdDesc(
                        run.getProjectId(), Instant.now().minus(RETRO_WINDOW)).stream()
                .filter(r -> !r.getId().equals(run.getId()))
                .map(r -> "run " + r.getId() + " · " + r.getType() + " · " + r.getIssueKey() + " · " + r.getStatus()
                        + " · 시도 " + r.getAttempt())
                .toList();
    }

    /**
     * PLAN 게이트 승인 뒤 이어받은 run이면 승인된 요청문. 회의 continuation은 직전 run을 부모로 잇는다({@link Run#continuation}) —
     * 승인 run의 재시도도 계획을 잃지 않게 조상 사슬을 거슬러 찾는다.
     */
    private String approvedPlan(Run run) {
        Long parentId = run.getParentRunId();
        for (int hop = 0; parentId != null && hop < ANCESTOR_WALK_LIMIT; hop++) {
            List<Gate> approved = gateRepository.findByRunIdAndKindAndDecisionOrderByIdAsc(parentId, GateKind.PLAN, GateDecision.APPROVE);
            if (!approved.isEmpty()) {
                return approved.get(approved.size() - 1).getRequest();
            }
            parentId = runRepository.findById(parentId).map(Run::getParentRunId).orElse(null);
        }
        return null;
    }

    private void submit(long runId) {
        try {
            runServiceProvider.getObject().execute(runId);
        } catch (TaskRejectedException e) {
            log.warn("회의 run 실행 제출이 거부돼 QUEUED로 남깁니다 — 스케줄러가 켜져 있으면 드레인 틱이 집어갑니다: id={} error={}",
                    runId, e.getMessage());
        }
    }

    private void commentBestEffort(Persona persona, String issueKey, String body) {
        try {
            String bearer = tokenService.bearerFor(persona.getMemberId());
            IssueResponse issue = almClient.getByKey(issueKey, bearer);
            almClient.addComment(issue.id(), body, bearer);
        } catch (Exception e) {
            log.warn("회의 소집 이슈 코멘트 기록 실패(run은 저장됨): issueKey={} : {}", issueKey, e.getMessage());
        }
    }

    private static List<Long> ids(List<Persona> personas) {
        return personas.stream().map(Persona::getId).toList();
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "(없음)";
        }
        if (text.length() <= ESCALATION_ERROR_TAIL) {
            return text;
        }
        int start = text.length() - ESCALATION_ERROR_TAIL;
        if (Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        return "…" + text.substring(start);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
