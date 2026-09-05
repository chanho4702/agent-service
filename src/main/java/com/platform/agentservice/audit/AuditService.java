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

    public void record(Long personaId, Long actorMemberId, String tool, String summary, AuditStatus status) {
        repository.save(ToolCallAudit.of(personaId, actorMemberId, tool, truncate(summary), status));
    }

    private String truncate(String summary) {
        if (summary == null || summary.length() <= SUMMARY_MAX_LENGTH) {
            return summary;
        }
        return summary.substring(0, SUMMARY_MAX_LENGTH);
    }
}
