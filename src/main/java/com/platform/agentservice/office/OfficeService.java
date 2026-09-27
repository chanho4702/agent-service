package com.platform.agentservice.office;

import com.platform.agentservice.audit.ToolCallAudit;
import com.platform.agentservice.audit.ToolCallAuditRepository;
import com.platform.agentservice.budget.BudgetService;
import com.platform.agentservice.budget.PersonaCost;
import com.platform.agentservice.budget.UsageLedgerRepository;
import com.platform.agentservice.budget.dto.BudgetStatusResponse;
import com.platform.agentservice.chat.ChatAvailability;
import com.platform.agentservice.office.dto.AuditEntry;
import com.platform.agentservice.office.dto.OfficeResponse;
import com.platform.agentservice.office.dto.PersonaActivityResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.run.Gate;
import com.platform.agentservice.run.GateRepository;
import com.platform.agentservice.run.MeetingProperties;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunService;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunType;
import com.platform.agentservice.run.dto.RunSummaryResponse;
import com.platform.common.error.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 사무실 감독 집계(P3a D-P3a-1) — 읽기 전용. 페르소나 수가 적다(≤20)는 전제로 페르소나 목록을 기준 축으로 두고,
 * run·감사·비용은 각각 한 번씩만 조회해 페르소나 id로 붙인다(페르소나별 쿼리 없음).
 *
 * <p>projectId 필터는 run 축(현재 run·최근 run·게이트·비용)과 페르소나 소속(P3f — 그 프로젝트 + 공용)에 걸린다.
 * 감사 로그에는 프로젝트 축이 없어 말풍선(lastActivity)은 페르소나 전체 활동 기준이다.
 */
@Service
@Transactional(readOnly = true)
public class OfficeService {

    /** 말풍선은 "지금 하는 일"이어야 한다 — 오래된 발화가 떠 있으면 일하는 중으로 오해된다. */
    static final Duration BUBBLE_WINDOW = Duration.ofMinutes(5);
    /** "오늘"은 사용자 달력 기준이어야 한다 — UTC 자정이면 한국 오전 9시에 비용이 0으로 리셋돼 보인다. */
    static final ZoneId OFFICE_ZONE = ZoneId.of("Asia/Seoul");
    /** BLOCKED는 활성(사람 재개 대기)이면서 종결 목록에도 보여야 한다 — 멈춘 run이 게시판에서 사라지면 안 된다. */
    static final Set<RunStatus> FINISHED_STATUSES =
            EnumSet.of(RunStatus.DONE, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.BLOCKED);
    static final int PENDING_GATE_LIMIT = 5;
    static final int GATE_SUMMARY_MAX = 200;

    private final PersonaRepository personaRepository;
    private final RunRepository runRepository;
    private final GateRepository gateRepository;
    private final ToolCallAuditRepository auditRepository;
    private final UsageLedgerRepository ledgerRepository;
    private final BudgetService budgetService;
    private final MeetingProperties meetingProperties;
    private final ChatAvailability chatAvailability;
    private final Clock clock;

    @Autowired
    public OfficeService(PersonaRepository personaRepository, RunRepository runRepository,
                         GateRepository gateRepository, ToolCallAuditRepository auditRepository,
                         UsageLedgerRepository ledgerRepository, BudgetService budgetService,
                         MeetingProperties meetingProperties, ChatAvailability chatAvailability) {
        this(personaRepository, runRepository, gateRepository, auditRepository, ledgerRepository, budgetService,
                meetingProperties, chatAvailability, Clock.systemUTC());
    }

    /** 테스트 전용 — 5분 창·오늘 경계를 고정 시각으로 검증한다. */
    OfficeService(PersonaRepository personaRepository, RunRepository runRepository,
                  GateRepository gateRepository, ToolCallAuditRepository auditRepository,
                  UsageLedgerRepository ledgerRepository, BudgetService budgetService,
                  MeetingProperties meetingProperties, ChatAvailability chatAvailability, Clock clock) {
        this.personaRepository = personaRepository;
        this.runRepository = runRepository;
        this.gateRepository = gateRepository;
        this.auditRepository = auditRepository;
        this.ledgerRepository = ledgerRepository;
        this.budgetService = budgetService;
        this.meetingProperties = meetingProperties;
        this.chatAvailability = chatAvailability;
        this.clock = clock;
    }

    public OfficeResponse office(Long projectId) {
        Instant now = clock.instant();
        Instant todayStart = todayStart(now);

        Map<Long, Run> currentRuns = new HashMap<>();
        List<Run> activeRuns = projectId == null
                ? runRepository.findByStatusInOrderByIdDesc(RunService.ACTIVE_STATUSES)
                : runRepository.findByStatusInAndProjectIdOrderByIdDesc(RunService.ACTIVE_STATUSES, projectId);
        activeRuns.forEach(r -> currentRuns.putIfAbsent(r.getPersonaId(), r));

        Map<Long, AuditEntry> bubbles = auditRepository.findLatestPerPersonaSince(now.minus(BUBBLE_WINDOW)).stream()
                .collect(Collectors.toMap(ToolCallAudit::getPersonaId, OfficeService::toEntry, (a, b) -> a));

        List<PersonaCost> costRows = projectId == null
                ? ledgerRepository.sumCostByPersonaSince(todayStart)
                : ledgerRepository.sumCostByPersonaSinceInProject(todayStart, projectId);
        Map<Long, BigDecimal> costs = costRows.stream()
                .collect(Collectors.toMap(PersonaCost::personaId, PersonaCost::costUsd, BigDecimal::add));

        // projectId 필터(P3f, D-P3f-3): 그 프로젝트 소속 + 전사 공용. 다른 프로젝트 소속이라도 이 프로젝트에서 도는 run이 있으면
        // 보여 준다 — 일하는 중인 캐릭터가 사무실에서 사라지면 감독 화면이 거짓말을 한다.
        List<OfficeResponse.OfficePersona> personas = personaRepository.findAll(Sort.by("id")).stream()
                .filter(p -> projectId == null || p.getProjectId() == null || projectId.equals(p.getProjectId())
                        || currentRuns.containsKey(p.getId()))
                .map(p -> new OfficeResponse.OfficePersona(p.getId(), p.getSlug(), p.getName(), p.getEmoji(),
                        p.getRole(), p.isActive(), toCurrent(currentRuns.get(p.getId())), bubbles.get(p.getId()),
                        costs.getOrDefault(p.getId(), BigDecimal.ZERO), p.getAvatarConfig()))
                .toList();

        List<Run> finished = projectId == null
                ? runRepository.findTop10ByStatusInOrderByUpdatedAtDescIdDesc(FINISHED_STATUSES)
                : runRepository.findTop10ByStatusInAndProjectIdOrderByUpdatedAtDescIdDesc(FINISHED_STATUSES, projectId);

        List<OfficeResponse.PendingGate> gates = pendingGates(projectId);

        BudgetService.BudgetSnapshot snapshot = budgetService.snapshot();
        BudgetStatusResponse budget = new BudgetStatusResponse(snapshot.monthlyCapUsd(),
                snapshot.platformMonthToDateUsd(), snapshot.killSwitch());

        List<Run> posts = projectId == null
                ? runRepository.findTop5ByTypeInAndStatusAndOutputPageIdIsNotNullOrderByEndedAtDescIdDesc(
                        RunType.MEETING_TYPES, RunStatus.DONE)
                : runRepository.findTop5ByTypeInAndStatusAndProjectIdAndOutputPageIdIsNotNullOrderByEndedAtDescIdDesc(
                        RunType.MEETING_TYPES, RunStatus.DONE, projectId);

        Optional<Run> meeting = projectId == null
                ? runRepository.findFirstByTypeInAndStatusOrderByIdDesc(RunType.MEETING_TYPES, RunStatus.RUNNING)
                : runRepository.findFirstByTypeInAndStatusAndProjectIdOrderByIdDesc(
                        RunType.MEETING_TYPES, RunStatus.RUNNING, projectId);

        Long spaceId = meetingProperties.hasSpace() ? meetingProperties.spaceId() : null;
        return new OfficeResponse(personas, finished.stream().map(RunSummaryResponse::of).toList(),
                gates.size(), gates.stream().limit(PENDING_GATE_LIMIT).toList(), budget, now,
                posts.stream().map(r -> toBoardPost(r, spaceId)).toList(),
                meeting.map(OfficeService::toActiveMeeting).orElse(null),
                new OfficeResponse.Features(chatAvailability.available(projectId)));
    }

    /** 참석자 컬럼이 비면(회의 계열인데 명단 없이 만들어진 행) 진행자 1인 회의로 본다 — 회의실이 빈 방이 되면 안 된다. */
    private static OfficeResponse.ActiveMeeting toActiveMeeting(Run run) {
        List<Long> attendees = run.getAttendeeIds();
        if (attendees.isEmpty()) {
            attendees = List.of(run.getPersonaId());
        }
        return new OfficeResponse.ActiveMeeting(run.getId(), run.getType(), run.getStatus(), run.getIssueKey(),
                run.getProjectId(), run.getPersonaId(), attendees, run.getStartedAt());
    }

    private static OfficeResponse.BoardPost toBoardPost(Run run, Long spaceId) {
        return new OfficeResponse.BoardPost(run.getId(), run.getType(), run.getIssueKey(), run.getProjectId(),
                run.getOutputPageId(), spaceId, run.getEndedAt());
    }

    public PersonaActivityResponse activity(long personaId) {
        if (!personaRepository.existsById(personaId)) {
            throw new NotFoundException("페르소나를 찾을 수 없습니다: " + personaId);
        }
        Instant todayStart = todayStart(clock.instant());
        List<RunSummaryResponse> runs = runRepository.findTop20ByPersonaIdOrderByIdDesc(personaId).stream()
                .map(RunSummaryResponse::of).toList();
        List<AuditEntry> audits = auditRepository
                .findTop50ByPersonaIdAndCreatedAtGreaterThanEqualOrderByIdDesc(personaId, todayStart).stream()
                .map(OfficeService::toEntry).toList();
        BigDecimal cost = ledgerRepository.sumCostForPersonaSince(personaId, todayStart).orElse(BigDecimal.ZERO);
        return new PersonaActivityResponse(personaId, runs, audits, cost);
    }

    /**
     * 게이트에는 프로젝트 축이 없어 run을 한 번에 불러 붙인다. 미결 게이트는 사람이 쌓아 두지 않는 한 소수라
     * 전부 읽고 나서 필터·자른다(개수는 필터 후 기준). 요청문은 가리지 않는다 — 기존 {@code GET /api/agent/gates}와
     * 같은 노출 수준을 유지하는 의도적 결정이다({@link OfficeResponse.PendingGate} 참고).
     */
    private List<OfficeResponse.PendingGate> pendingGates(Long projectId) {
        List<Gate> pending = gateRepository.findByDecisionIsNull();
        if (pending.isEmpty()) {
            return List.of();
        }
        Map<Long, Run> runsById = runRepository.findAllById(pending.stream().map(Gate::getRunId).distinct().toList())
                .stream().collect(Collectors.toMap(Run::getId, Function.identity()));
        return pending.stream()
                .filter(g -> runsById.containsKey(g.getRunId()))
                .filter(g -> projectId == null || projectId.equals(runsById.get(g.getRunId()).getProjectId()))
                .sorted(Comparator.comparing(Gate::getRequestedAt).thenComparing(Gate::getId).reversed())
                .map(g -> {
                    Run run = runsById.get(g.getRunId());
                    return new OfficeResponse.PendingGate(g.getId(), g.getRunId(), run.getIssueKey(),
                            run.getPersonaId(), g.getKind(), abbreviate(g.getRequest()), g.getRequestedAt());
                })
                .toList();
    }

    private static OfficeResponse.CurrentRun toCurrent(Run run) {
        if (run == null) {
            return null;
        }
        return new OfficeResponse.CurrentRun(run.getId(), run.getStatus(), run.getIssueKey(), run.getType(),
                run.getTrigger(), run.getAttempt(), run.getModel(), run.getStartedAt());
    }

    private static AuditEntry toEntry(ToolCallAudit a) {
        return new AuditEntry(a.getId(), a.getTool(), a.getStatus(),
                AuditSummaryRedactor.redact(a.getTool(), a.getSummary()), a.getCreatedAt());
    }

    private static Instant todayStart(Instant now) {
        return LocalDate.ofInstant(now, OFFICE_ZONE).atStartOfDay(OFFICE_ZONE).toInstant();
    }

    /** 서로게이트 쌍 한가운데서 자르면 JSON에 깨진 문자가 나간다. */
    static String abbreviate(String s) {
        if (s == null || s.length() <= GATE_SUMMARY_MAX) {
            return s;
        }
        int end = GATE_SUMMARY_MAX;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end) + "…";
    }
}
