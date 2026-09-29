package com.platform.agentservice.run;

import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.audit.ToolCallAuditRepository;
import com.platform.agentservice.client.AlmClient;
import com.platform.agentservice.client.TokenService;
import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.WorklogRequest;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.common.error.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;

/**
 * run 종결 워크로그(P4b D-P4b-2, AGP-60) — 작업 시간 기록을 프롬프트 의무화 대신 서버가 남긴다. {@link RunService}의 결과 반영
 * 합류점(인프로세스·러너 결과 모두 {@code applyOutcome})이 종결 직후 부른다.
 *
 * <ul>
 *   <li>대상: TASK·REVIEW run이 DONE·FAILED·BLOCKED로 끝났을 때 + 회의 계열은 안건 이슈가 있을 때만({@link Run#hasAgendaIssue()}).</li>
 *   <li>시간: {@code ceil((endedAt ?? now) − startedAt)}분, 최소 1분 → 시간 단위(ALM {@code NUMERIC(8,2)}, 0.01h 올림 — 1분도 0이 되지 않게).</li>
 *   <li>명의: run 페르소나(REVIEW는 리뷰어, 회의는 진행자), 코멘트 {@code AI run #<id>(<type>) 자동 기록}, 작업일은 Asia/Seoul 기준.</li>
 *   <li>건너뜀: 워커가 그 run에서 이미 {@code log_work}를 성공적으로 불렀으면(감사 {@code tool=log_work, run_id, OK}) — 이중 기록 방지.</li>
 *   <li>best-effort: 실패는 warn 로그뿐(run 처리·리뷰·알림을 막지 않는다).</li>
 * </ul>
 */
@Slf4j
@Service
public class RunWorklogService {

    static final ZoneId WORK_ZONE = ZoneId.of("Asia/Seoul");
    static final Set<RunStatus> ENDED = EnumSet.of(RunStatus.DONE, RunStatus.FAILED, RunStatus.BLOCKED);
    static final String LOG_WORK_TOOL = "log_work";

    private final WorklogProperties properties;
    private final PersonaRepository personaRepository;
    private final TokenService tokenService;
    private final AlmClient almClient;
    private final ToolCallAuditRepository auditRepository;
    private final Clock clock;

    @Autowired
    public RunWorklogService(WorklogProperties properties, PersonaRepository personaRepository, TokenService tokenService,
                             AlmClient almClient, ToolCallAuditRepository auditRepository) {
        this(properties, personaRepository, tokenService, almClient, auditRepository, Clock.systemUTC());
    }

    RunWorklogService(WorklogProperties properties, PersonaRepository personaRepository, TokenService tokenService,
                      AlmClient almClient, ToolCallAuditRepository auditRepository, Clock clock) {
        this.properties = properties;
        this.personaRepository = personaRepository;
        this.tokenService = tokenService;
        this.almClient = almClient;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    /** 종결된 run이면 워크로그를 남긴다. 던지지 않는다. */
    public void onRunEnded(Run run) {
        if (run == null || !properties.auto() || !inScope(run) || run.getStartedAt() == null) {
            return;
        }
        try {
            if (auditRepository.existsByToolAndRunIdAndStatus(LOG_WORK_TOOL, run.getId(), AuditStatus.OK)) {
                log.debug("run={} 워커가 이미 log_work를 불러 자동 워크로그를 건너뜁니다", run.getId());
                return;
            }
            Instant end = run.getEndedAt() != null ? run.getEndedAt() : clock.instant();
            long minutes = minutes(run.getStartedAt(), end);
            Persona persona = personaRepository.findById(run.getPersonaId())
                    .orElseThrow(() -> new NotFoundException("run의 페르소나를 찾을 수 없습니다: " + run.getPersonaId()));
            String bearer = tokenService.bearerFor(persona.getMemberId());
            IssueResponse issue = almClient.getByKey(run.getIssueKey(), bearer);
            almClient.addWorklog(issue.id(), new WorklogRequest(hours(minutes), comment(run), LocalDate.ofInstant(end, WORK_ZONE)),
                    bearer);
            log.info("run={} 자동 워크로그 {}분 기록({})", run.getId(), minutes, run.getIssueKey());
        } catch (Exception e) {
            log.warn("run={} 자동 워크로그 기록 실패 — 건너뜁니다: {}", run.getId(), e.getMessage());
        }
    }

    static boolean inScope(Run run) {
        if (!ENDED.contains(run.getStatus())) {
            return false;
        }
        RunType type = run.getType();
        if (type == RunType.TASK || type == RunType.REVIEW) {
            return true;
        }
        return type.isMeeting() && run.hasAgendaIssue();
    }

    /** 분 단위 올림, 최소 1분(시각이 뒤집혀도 1분). */
    static long minutes(Instant start, Instant end) {
        Duration elapsed = Duration.between(start, end);
        if (elapsed.isNegative() || elapsed.isZero()) {
            return 1;
        }
        long whole = elapsed.toMinutes();
        return elapsed.minusMinutes(whole).isZero() ? whole : whole + 1;
    }

    static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.CEILING);
    }

    static String comment(Run run) {
        return "AI run #" + run.getId() + "(" + run.getType() + ") 자동 기록";
    }
}
