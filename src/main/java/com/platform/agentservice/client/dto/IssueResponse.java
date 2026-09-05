package com.platform.agentservice.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * alm-backend {@code IssueResponse}와 필드명을 그대로 맞춘다(S10). {@code resolution}은
 * alm 쪽에서 열거형이지만, 여기서는 문자열로만 다룬다(그대로 왕복시키기만 하면 되므로).
 */
public record IssueResponse(
        long id,
        String key,
        long projectId,
        String title,
        String description,
        String type,
        String status,
        String priority,
        Long assigneeId,
        long reporterId,
        Long parentId,
        Long sprintId,
        LocalDate dueDate,
        BigDecimal estimateHours,
        String resolution,
        Long fixVersionId,
        List<String> labels,
        List<Long> componentIds,
        long order,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt
) {
}
