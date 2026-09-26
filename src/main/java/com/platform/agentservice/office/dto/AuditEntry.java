package com.platform.agentservice.office.dto;

import com.platform.agentservice.audit.AuditStatus;

import java.time.Instant;

/** 감사 한 건의 공개용 뷰 — summary는 {@code AuditSummaryRedactor}를 거친 값이다(본문 제거). */
public record AuditEntry(
        long id,
        String tool,
        AuditStatus status,
        String summary,
        Instant createdAt
) {
}
