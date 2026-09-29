package com.platform.agentservice.directive.dto;

import com.platform.agentservice.directive.RunDirective;

import java.time.Instant;

/**
 * {@code POST/GET /api/agent/runs/{id}/directives}(AGP-67). {@code deliveredAt}이 null이면 아직 워커에게 전달되지 않았다.
 * 목록은 인증 사용자 누구나 보지만 본문은 사람이 쓴 지시라 run 프로젝트 관리자에게만 싣는다 — 그 밖의 조회자는 {@code text=null},
 * {@code textRedacted=true}(사무실 감사 가림과 같은 수준: 메타데이터만).
 */
public record RunDirectiveResponse(
        long id,
        long runId,
        String text,
        boolean textRedacted,
        long authorMemberId,
        Instant createdAt,
        Instant deliveredAt
) {
    public static RunDirectiveResponse of(RunDirective d, boolean withText) {
        return new RunDirectiveResponse(d.getId(), d.getRunId(), withText ? d.getText() : null, !withText,
                d.getAuthorMemberId(), d.getCreatedAt(), d.getDeliveredAt());
    }
}
