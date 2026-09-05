package com.platform.agentservice.client.dto;

/**
 * wiki-backend {@code PageResponse}에서 이 서비스의 6개 문서 도구가 실제로 쓰는 필드만
 * 옮겨왔다(S10) — {@code position/icon/views/archivedAt/ownerId/verifiedAt/verifiedBy/
 * verifiedUntil/importedAuthorName/importedSourceUrl}은 W23~W29 화면 전용이라 도구
 * 응답·재조회 로직 어디에도 필요하지 않다. wiki-backend의 {@code type}/{@code status}는
 * enum이지만 JSON 계약이 이미 소문자 문자열("page"/"folder", "draft"/"published")이라
 * 여기서는 String으로만 다룬다.
 */
public record PageResponse(
        Long id,
        Long spaceId,
        Long parentId,
        String title,
        String content,
        Integer version,
        String type,
        String status
) {
}
