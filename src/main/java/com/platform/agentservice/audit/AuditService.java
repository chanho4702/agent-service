package com.platform.agentservice.audit;

import org.springframework.stereotype.Service;

/** {@code tool_call_audit} 행 기록. summary는 500자 넘으면 잘라서 저장한다(컬럼 제약과 동일). */
@Service
public class AuditService {

    private static final int SUMMARY_MAX_LENGTH = 500;

    private final ToolCallAuditRepository repository;

    public AuditService(ToolCallAuditRepository repository) {
        this.repository = repository;
    }

    /** PAT 없는 서버 내부 기록(chat.say·credential.* 등) — 출처 SYSTEM. */
    public void record(Long personaId, Long actorMemberId, String tool, String summary, AuditStatus status) {
        record(personaId, actorMemberId, tool, summary, status, AuditOrigin.SYSTEM, null);
    }

    public void record(Long personaId, Long actorMemberId, String tool, String summary, AuditStatus status,
                       AuditOrigin origin, Long runId) {
        repository.save(ToolCallAudit.of(personaId, actorMemberId, tool, truncate(summary), status, origin, runId));
    }

    private String truncate(String summary) {
        if (summary == null || summary.length() <= SUMMARY_MAX_LENGTH) {
            return summary;
        }
        return summary.substring(0, SUMMARY_MAX_LENGTH);
    }
}
