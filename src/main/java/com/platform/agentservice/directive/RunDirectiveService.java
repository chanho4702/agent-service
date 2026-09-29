package com.platform.agentservice.directive;

import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.directive.dto.RunDirectiveResponse;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunRepository;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.tools.ToolInputGuard;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 실행 중 지시(P4b D-P4b-5, AGP-67). 사람이 RUNNING run에 지시를 남기면 그 run의 run 토큰으로 오는 다음 MCP 도구 결과 끝에
 * 실려 전달된다(전달 지점은 {@code tools.DirectiveDelivery} — 모든 도구가 지나는 {@link ToolInputGuard} 안).
 *
 * <p>RUNNING이 아닌 run은 409다 — QUEUED면 지시문(instruction)에 몰래 합치지 않는다(사람이 "실행 중 지시"로 보낸 것이 "처음 지시"로
 * 바뀌면 의미가 달라진다). 대기 중인 run에는 취소 후 지시문과 함께 다시 요청하거나 이슈 코멘트로 남긴다.
 */
@Service
public class RunDirectiveService {

    static final String AUDIT_CREATE = "run.directive";

    private final RunDirectiveRepository repository;
    private final RunRepository runRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public RunDirectiveService(RunDirectiveRepository repository, RunRepository runRepository, AuditService auditService) {
        this(repository, runRepository, auditService, Clock.systemUTC());
    }

    RunDirectiveService(RunDirectiveRepository repository, RunRepository runRepository, AuditService auditService,
                        Clock clock) {
        this.repository = repository;
        this.runRepository = runRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * 지시 저장. 검증 순서: 본문(400) → run 존재(404) → RUNNING(409). 감사는 서버 기록(SYSTEM, 페르소나 null — 사무실 말풍선에
     * 사람 지시 흔적이 모두에게 뜨지 않게), summary에 본문을 싣지 않는다.
     */
    @Transactional
    public RunDirectiveResponse create(long runId, String text, long authorMemberId) {
        String checked = checkText(text);
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("run을 찾을 수 없습니다: " + runId));
        if (run.getStatus() != RunStatus.RUNNING) {
            throw new ConflictException("실행 중(RUNNING)인 run에만 지시할 수 있습니다(현재: " + run.getStatus()
                    + ") — 대기 중이면 취소 후 지시문과 함께 다시 요청하거나 이슈 코멘트로 남기세요");
        }
        RunDirective saved = repository.save(RunDirective.of(runId, checked, authorMemberId));
        auditService.record(null, authorMemberId, AUDIT_CREATE, "directive created run=" + runId + " id=" + saved.getId(),
                AuditStatus.OK);
        return RunDirectiveResponse.of(saved, true);
    }

    /** 목록(오래된 것 먼저). {@code withText=false}면 본문을 걷어낸다(관리자가 아닌 조회자 — 사무실 가림 규칙과 같은 수준). */
    @Transactional(readOnly = true)
    public List<RunDirectiveResponse> list(long runId, boolean withText) {
        if (!runRepository.existsById(runId)) {
            throw new NotFoundException("run을 찾을 수 없습니다: " + runId);
        }
        return repository.findByRunIdOrderByIdAsc(runId).stream()
                .map(d -> RunDirectiveResponse.of(d, withText))
                .toList();
    }

    /**
     * 그 run의 미전달 지시를 원자적으로 가져가며 전달 처리한다(오래된 것 먼저). 동시에 도는 두 도구 호출은 서로소 집합을 받는다 —
     * 후보마다 조건부 UPDATE({@link RunDirectiveRepository#markDelivered})를 쳐서 1행을 얻은 것만 담는다. run이 RUNNING이 아니면 빈 목록.
     */
    @Transactional
    public List<RunDirective> claimUndelivered(long runId) {
        List<RunDirective> candidates = repository.findByRunIdAndDeliveredAtIsNullOrderByIdAsc(runId);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<RunDirective> won = new ArrayList<>();
        for (RunDirective candidate : candidates) {
            if (repository.markDelivered(candidate.getId(), now, RunStatus.RUNNING) == 1) {
                won.add(candidate);
            }
        }
        return won;
    }

    /** run id별 미전달 개수(사무실 currentRun). 없는 run은 맵에 없다(= 0). */
    @Transactional(readOnly = true)
    public Map<Long, Integer> pendingCounts(Collection<Long> runIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (runIds == null || runIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : repository.countPendingByRunIds(runIds)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return counts;
    }

    /** 1~2000자·공백만 불가 + 입력 인코딩 규약(깨진 서로게이트·제어문자·U+FFFD — MCP 도구와 같은 판정). 오류에 본문을 싣지 않는다. */
    static String checkText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text가 필요합니다");
        }
        String trimmed = text.strip();
        if (trimmed.length() > RunDirective.TEXT_MAX) {
            throw new IllegalArgumentException("text는 " + RunDirective.TEXT_MAX + "자 이하여야 합니다");
        }
        String reason = ToolInputGuard.invalidReason(trimmed);
        if (reason != null) {
            throw new IllegalArgumentException("입력 인코딩 거부 — text: " + reason);
        }
        return trimmed;
    }
}
