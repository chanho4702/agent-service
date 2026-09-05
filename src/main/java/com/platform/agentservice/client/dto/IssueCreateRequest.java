package com.platform.agentservice.client.dto;

import java.util.List;

/** {@code POST /api/alm/projects/{projectId}/issues} 요청 body — alm-backend와 동일 shape(S10). */
public record IssueCreateRequest(
        String title,
        String description,
        String type,
        String status,
        String priority,
        Long assigneeId,
        IssueDetailsRequest details,
        List<Long> mentionedUserIds
) {
}
