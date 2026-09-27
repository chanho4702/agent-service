package com.platform.agentservice.office.dto;

import com.platform.agentservice.audit.AuditOrigin;
import com.platform.agentservice.audit.AuditStatus;

import java.time.Instant;

/**
 * 감사 한 건의 공개용 뷰 — summary는 {@code AuditSummaryRedactor}를 거친 값이다(본문 제거). {@code origin}은 실행 출처
 * (AGP-63, V12 이전 행은 null), {@code runId}는 WORKER 호출의 run id(그 밖 null).
 */
public record AuditEntry(
        long id,
        String tool,
        AuditStatus status,
        String summary,
        Instant createdAt,
        AuditOrigin origin,
        Long runId
) {
    public AuditEntry(long id, String tool, AuditStatus status, String summary, Instant createdAt) {
        this(id, tool, status, summary, createdAt, null, null);
    }
}
