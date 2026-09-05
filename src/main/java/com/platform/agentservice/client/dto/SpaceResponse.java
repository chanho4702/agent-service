package com.platform.agentservice.client.dto;

/** wiki-backend {@code SpaceResponse}와 필드명을 그대로 맞춘다(S10). {@code GET /api/wiki/spaces}는 접근 가능한 스페이스만 서버가 걸러서 돌려준다. */
public record SpaceResponse(
        Long id,
        String key,
        String name,
        String description,
        Long ownerId
) {
}
