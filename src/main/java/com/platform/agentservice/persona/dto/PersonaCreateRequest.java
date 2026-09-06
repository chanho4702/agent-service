package com.platform.agentservice.persona.dto;

import com.platform.agentservice.persona.PersonaRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /api/agent/personas} 요청.
 *
 * <p>길이 상한은 agentdb {@code persona} 컬럼폭(V1: slug 40 · name 80 · emoji 16)과 같다 — 초과 입력을
 * DB까지 흘려 500(DataIntegrityViolation)으로 터뜨리지 않고 요청 경계에서 400으로 끊는다(M4, AGP-24).
 * {@code @Size}는 UTF-16 단위, PostgreSQL {@code VARCHAR(n)}은 코드포인트 단위라 서로게이트 쌍(이모지 등)은
 * 여기서 더 보수적으로 세어진다 — 컬럼 초과 방향으로는 절대 새지 않는다. {@code voicePrompt}는 TEXT라 상한이 없다.
 * {@code email}·{@code grants}는 auth-server/org-service로만 전달되고 로컬 컬럼이 없어 여기서 폭을 묶지 않는다.
 */
public record PersonaCreateRequest(
        @NotBlank @Pattern(regexp = "[a-z0-9-]{2,40}", message = "slug는 소문자/숫자/하이픈 2~40자여야 합니다") String slug,
        @NotNull PersonaRole role,
        @NotBlank @Size(max = 80, message = "name은 80자 이하여야 합니다") String name,
        @Size(max = 16, message = "emoji는 16자 이하여야 합니다") String emoji,
        String voicePrompt,
        String email,
        List<@Valid GrantRequest> grants) {

    public record GrantRequest(
            @NotBlank String resourceType,
            @NotBlank String resourceId,
            @NotBlank String role) {}
}
