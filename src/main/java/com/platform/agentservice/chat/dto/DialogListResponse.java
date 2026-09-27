package com.platform.agentservice.chat.dto;

import java.util.List;

/** entries는 시간순(오래된 것 먼저) — 더 이전 기록은 entries[0].id를 before로 다시 부른다. */
public record DialogListResponse(List<DialogEntryResponse> entries, boolean hasMore) {
}
