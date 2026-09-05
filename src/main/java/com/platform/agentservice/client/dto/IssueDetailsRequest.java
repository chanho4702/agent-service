package com.platform.agentservice.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * alm-backend {@code IssueDetailsRequest}의 V2 확장 필드(S10). PUT 요청에서 이 객체
 * 자체를 {@code null}로 보내면 alm-backend가 기존 값을 전부 보존한다 — full-replace 도구
 * (claim_issue/update_issue_status)는 이 성질을 이용해 details 없이 title/type/status/
 * priority/assigneeId/expectedVersion만 채운다.
 */
public record IssueDetailsRequest(
        Long parentId,
        Long sprintId,
        LocalDate dueDate,
        BigDecimal estimateHours,
        String resolution,
        Long fixVersionId,
        List<String> labels,
        List<Long> componentIds
) {
}
