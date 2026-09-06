package com.platform.agentservice.pat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/agent/tokens} 요청. {@code expiresInDays}가 없으면 만료 없음.
 *
 * <p>{@code label} 상한은 agentdb {@code pat_token.label VARCHAR(120)}과 같다 — 요청 경계에서 400으로
 * 끊어 DB 500을 막는다(M4, AGP-24). {@code personaSlug}는 조회 키일 뿐 저장되지 않아 폭을 묶지 않는다
 * (없는 슬러그는 서비스가 404로 처리한다).
 */
public record PatCreateRequest(
        @NotBlank @Size(max = 120, message = "label은 120자 이하여야 합니다") String label,
        @NotBlank String personaSlug,
        @Positive Integer expiresInDays) {
}
