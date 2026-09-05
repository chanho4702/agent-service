package com.platform.agentservice.persona.dto;

import com.platform.agentservice.persona.PersonaRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/** {@code POST /api/agent/personas} 요청. */
public record PersonaCreateRequest(
        @NotBlank @Pattern(regexp = "[a-z0-9-]{2,40}", message = "slug는 소문자/숫자/하이픈 2~40자여야 합니다") String slug,
        @NotNull PersonaRole role,
        @NotBlank String name,
        String emoji,
        String voicePrompt,
        String email,
        List<@Valid GrantRequest> grants) {

    public record GrantRequest(
            @NotBlank String resourceType,
            @NotBlank String resourceId,
            @NotBlank String role) {}
}
