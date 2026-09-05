package com.platform.agentservice.client.dto;

/**
 * {@code PUT /api/wiki/pages/{id}} 요청 body — full replace(S10). {@code title}은
 * {@code @NotBlank}, {@code content}/{@code expectedVersion}은 {@code @NotNull}로
 * 강제되므로 항상 채워 보낸다. {@code parentId}는 반드시 현재 값과 같아야 한다 —
 * 다르면 wiki-backend가 400을 던진다(재부모화는 전용 move API 몫, P1 미지원).
 */
public record PageUpdateRequest(
        String title,
        String content,
        Long parentId,
        Integer expectedVersion,
        String changeNote
) {
}
