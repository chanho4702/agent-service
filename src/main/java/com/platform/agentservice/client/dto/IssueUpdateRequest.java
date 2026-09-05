package com.platform.agentservice.client.dto;

import java.util.List;

/**
 * {@code PUT /api/alm/issues/{issueId}} 요청 body — full replace(S10). {@code title/type/
 * status/priority/expectedVersion}은 alm-backend가 {@code @NotBlank}/{@code @NotNull}로
 * 강제하므로 항상 현재 값을 채워 보내야 한다. {@code assigneeId}는 null이면 담당자
 * 해제로 처리되므로(명시적 해제 시맨틱), 유지하려면 현재 값을 그대로 넣어야 한다.
 * {@code details}를 null로 두면 parentId/sprintId/dueDate/estimateHours/resolution/
 * fixVersionId/labels/componentIds는 alm-backend가 기존 값을 그대로 보존한다.
 */
public record IssueUpdateRequest(
        String title,
        String description,
        String type,
        String status,
        String priority,
        Long assigneeId,
        IssueDetailsRequest details,
        Integer expectedVersion,
        List<Long> mentionedUserIds
) {
}
