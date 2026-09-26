package com.platform.agentservice.client.dto;

import java.util.List;

/**
 * {@code PUT /api/alm/issues/{issueId}} 요청 body — full replace(S10). {@code title/type/
 * status/priority/expectedVersion}은 alm-backend가 {@code @NotBlank}/{@code @NotNull}로
 * 강제하므로 항상 현재 값을 채워 보내야 한다. {@code assigneeId}는 null이면 담당자
 * 해제로 처리되므로(명시적 해제 시맨틱), 유지하려면 현재 값을 그대로 넣어야 한다.
 * {@code details}를 null로 두면 parentId/sprintId/dueDate/estimateHours/resolution/
 * fixVersionId/labels/componentIds는 alm-backend가 기존 값을 그대로 보존한다.
 *
 * <p>현재 값으로 채운 요청은 {@link #preserving}으로만 만든다 — 필드 보존 규칙을 한 곳에 둬야
 * 도구마다 매핑이 갈라져 조용히 값을 지우는 회귀를 막을 수 있다.
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

    /**
     * 현재 이슈를 그대로 되쓰는 요청(아무것도 안 바꾸는 PUT). {@code mentionedUserIds}는 저장
     * 필드가 아니라 알림 트리거라 null이어야 한다 — 채우면 되쓰기마다 멘션 알림이 다시 나간다.
     */
    public static IssueUpdateRequest preserving(IssueResponse current) {
        return new IssueUpdateRequest(
                current.title(), current.description(), current.type(), current.status(),
                current.priority(), current.assigneeId(), null, current.version(), null);
    }

    public IssueUpdateRequest withTitle(String value) {
        return new IssueUpdateRequest(value, description, type, status, priority, assigneeId, details, expectedVersion, mentionedUserIds);
    }

    public IssueUpdateRequest withDescription(String value) {
        return new IssueUpdateRequest(title, value, type, status, priority, assigneeId, details, expectedVersion, mentionedUserIds);
    }

    public IssueUpdateRequest withStatus(String value) {
        return new IssueUpdateRequest(title, description, type, value, priority, assigneeId, details, expectedVersion, mentionedUserIds);
    }

    public IssueUpdateRequest withPriority(String value) {
        return new IssueUpdateRequest(title, description, type, status, value, assigneeId, details, expectedVersion, mentionedUserIds);
    }

    public IssueUpdateRequest withAssigneeId(Long value) {
        return new IssueUpdateRequest(title, description, type, status, priority, value, details, expectedVersion, mentionedUserIds);
    }
}
