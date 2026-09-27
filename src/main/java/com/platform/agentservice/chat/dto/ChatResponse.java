package com.platform.agentservice.chat.dto;

import com.platform.agentservice.chat.ChatMood;

/** @param suggest {@code "DIRECTIVE"}(작업 요청으로 보이면 — 프론트가 '지시하기'를 권한다) 또는 null */
public record ChatResponse(String sessionId, String reply, ChatMood mood, String suggest) {
}
