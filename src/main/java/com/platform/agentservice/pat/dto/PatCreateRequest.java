package com.platform.agentservice.pat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** {@code POST /api/agent/tokens} 요청. {@code expiresInDays}가 없으면 만료 없음. */
public record PatCreateRequest(
        @NotBlank String label,
        @NotBlank String personaSlug,
        @Positive Integer expiresInDays) {
}
