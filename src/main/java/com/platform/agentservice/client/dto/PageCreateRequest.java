package com.platform.agentservice.client.dto;

/**
 * {@code POST /api/wiki/pages} 요청 body — wiki-backend와 동일 shape(S10). {@code type}을
 * null로 보내면 wiki-backend가 "page"로 기본값 처리한다(PageType.from). {@code status}는
 * "draft"/"published" 문자열이며, null이면 "published"로 기본값 처리된다(PageStatus.from).
 */
public record PageCreateRequest(
        Long spaceId,
        Long parentId,
        String title,
        String content,
        String type,
        String status
) {
}
