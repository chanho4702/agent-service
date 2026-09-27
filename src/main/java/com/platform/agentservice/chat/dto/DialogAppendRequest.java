package com.platform.agentservice.chat.dto;

import java.util.List;

/**
 * {@code POST /api/agent/personas/{id}/dialog}(P3g ③) — 1회 20건 이하. 필드는 전부 문자열로 받고 서비스가 고정 문구로 검사한다
 * (잘못된 enum 값이 역직렬화 오류로 {@code {error}} 계약 밖 400이 되지 않게, 거부 응답에 본문이 실리지 않게).
 */
public record DialogAppendRequest(List<Item> entries) {

    public record Item(String speaker, String kind, String text, String issueKey, String runId, String commentId) {
    }
}
