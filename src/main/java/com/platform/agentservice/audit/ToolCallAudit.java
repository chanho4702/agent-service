package com.platform.agentservice.audit;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "tool_call_audit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ToolCallAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long personaId;
    @Column(nullable = false) private Long actorMemberId;
    @Column(nullable = false, length = 60) private String tool;
    @Column(length = 500) private String summary;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private AuditStatus status;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;

    public static ToolCallAudit of(Long personaId, Long actorMemberId, String tool, String summary, AuditStatus status) {
        ToolCallAudit a = new ToolCallAudit();
        a.personaId = personaId;
        a.actorMemberId = actorMemberId;
        a.tool = tool;
        a.summary = summary;
        a.status = status;
        return a;
    }
}
