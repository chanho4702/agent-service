package com.platform.agentservice.runner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/agent/runners} — LOCAL 러너 토큰 발급. {@code projectId}가 null이면 플랫폼 전역 러너(전역 관리자만).
 * {@code name} 상한은 {@code runner.name VARCHAR(80)}과 같다.
 */
public record RunnerCreateRequest(
        @NotBlank(message = "name은 필수입니다") @Size(max = 80, message = "name은 80자 이하여야 합니다") String name,
        Long projectId) {
}
