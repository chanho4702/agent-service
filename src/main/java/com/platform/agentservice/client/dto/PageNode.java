package com.platform.agentservice.client.dto;

/**
 * wiki-backend {@code PageNode}(지연 트리 노드)에서 문서 도구가 쓰는 목록 필드만 옮겨왔다
 * (S10) — {@code position/icon/updatedBy/updatedAt/childCount}는 트리 UI 전용이라
 * {@code find_pages} 요약에 필요하지 않다. {@code GET .../pages/search}, {@code GET
 * .../pages/children} 두 엔드포인트가 공통으로 이 shape를 돌려준다.
 */
public record PageNode(
        Long id,
        String title,
        String type,
        String status
) {
}
