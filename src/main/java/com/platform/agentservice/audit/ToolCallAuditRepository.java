package com.platform.agentservice.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallAuditRepository extends JpaRepository<ToolCallAudit, Long> {
}
