package com.platform.agentservice.run.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/agent/runs} 요청(AGP-42) — 사람이 지시문을 붙여 run을 직접 요청한다.
 *
 * <p>{@code issueKey}·{@code model} 상한은 agentdb {@code run.issue_key VARCHAR(40)}·{@code run.model
 * VARCHAR(60)}과 같다 — 요청 경계에서 400으로 끊어 DB 500을 막는다(M4, {@code RequestDtoColumnWidthTest}).
 * {@code instruction}은 TEXT 컬럼이라 DB 폭이 없지만 워커 프롬프트에 통째로 실리므로 애플리케이션 상한을 둔다.
 * {@code personaSlug}는 조회 키일 뿐 저장되지 않는다(없는 슬러그는 서비스가 404로 처리한다).
 */
public record UserRunCreateRequest(
        @NotBlank @Size(max = 40, message = "issueKey는 40자 이하여야 합니다") String issueKey,
        @Size(max = 4000, message = "instruction은 4000자 이하여야 합니다") String instruction,
        @Size(max = 60, message = "model은 60자 이하여야 합니다") String model,
        String personaSlug) {
}
