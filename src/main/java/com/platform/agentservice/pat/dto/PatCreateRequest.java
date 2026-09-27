package com.platform.agentservice.pat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/agent/tokens} 요청. 만료 정책(D-P4-3b): {@code expiresInDays}가 없으면 90일, 주면 1~365일. {@code noExpiry=true}는
 * 무기한(전역 관리자만 — 아니면 403, 기간과 함께 주면 400).
 *
 * <p>{@code label} 상한은 agentdb {@code pat_token.label VARCHAR(120)}과 같다 — 요청 경계에서 400으로 끊어 DB 500을 막는다(M4, AGP-24).
 * {@code personaSlug}는 조회 키일 뿐 저장되지 않아 폭을 묶지 않는다(없는 슬러그는 서비스가 404로 처리한다).
 */
public record PatCreateRequest(
        @NotBlank @Size(max = 120, message = "label은 120자 이하여야 합니다") String label,
        @NotBlank String personaSlug,
        @Min(value = 1, message = "expiresInDays는 1~365 사이여야 합니다")
        @Max(value = 365, message = "expiresInDays는 1~365 사이여야 합니다") Integer expiresInDays,
        Boolean noExpiry) {

    /** 무기한 플래그 없는 요청(run 토큰 발급·기존 호출부). */
    public PatCreateRequest(String label, String personaSlug, Integer expiresInDays) {
        this(label, personaSlug, expiresInDays, null);
    }
}
