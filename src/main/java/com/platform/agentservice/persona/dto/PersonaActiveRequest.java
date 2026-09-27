package com.platform.agentservice.persona.dto;

import jakarta.validation.constraints.NotNull;

/** {@code PATCH /api/agent/personas/{id}/active} 본문 — {@code {"active": true|false}}. */
public record PersonaActiveRequest(@NotNull(message = "active 값이 필요합니다") Boolean active) {
}
