package com.platform.agentservice.run.dto;

import com.platform.agentservice.execution.ExecutionSite;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;

import java.time.Instant;

/**
 * {@code GET /api/agent/runs}·USER run 201·사무실 API의 run 요약(P2a T4). type·trigger·parentRunId는
 * P3a에서 뒤에 덧붙였다 — 필드 추가만이라 기존 소비자는 그대로 읽힌다. executionSite·runnerId(P4a)도 같은 방식으로 덧붙였다
 * (runnerId는 QUEUED면 고정 러너, RUNNING 이후면 배정 러너, 인프로세스 run은 null).
 */
public record RunSummaryResponse(
        long id,
        String issueKey,
        RunStatus status,
        long personaId,
        int attempt,
        String model,
        Instant startedAt,
        Instant endedAt,
        RunType type,
        RunTrigger trigger,
        Long parentRunId,
        ExecutionSite executionSite,
        Long runnerId
) {
    /** 실행 위치 필드 없는 생성(기존 호출부·테스트) — SERVER·러너 없음. */
    public RunSummaryResponse(long id, String issueKey, RunStatus status, long personaId, int attempt, String model,
                              Instant startedAt, Instant endedAt, RunType type, RunTrigger trigger, Long parentRunId) {
        this(id, issueKey, status, personaId, attempt, model, startedAt, endedAt, type, trigger, parentRunId,
                ExecutionSite.SERVER, null);
    }

    public static RunSummaryResponse of(Run run) {
        return new RunSummaryResponse(run.getId(), run.getIssueKey(), run.getStatus(), run.getPersonaId(),
                run.getAttempt(), run.getModel(), run.getStartedAt(), run.getEndedAt(),
                run.getType(), run.getTrigger(), run.getParentRunId(), run.getExecutionSite(), run.getRunnerId());
    }
}
