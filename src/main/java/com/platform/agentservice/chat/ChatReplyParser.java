package com.platform.agentservice.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * 수다 응답의 구조화 부분(표정·지시 권유)을 떼어 낸다. 도구(tool_use) 없이 프롬프트 규약으로 받는다 — 모델이 마지막 줄에
 * {@code @@meta {"mood":"…","suggest":…}}를 붙인다. 규약을 어기거나 JSON이 깨지면 {@code mood=null·suggest=null}로 안전하게
 * 떨어지고, 답변은 메타 줄을 뺀 텍스트만 남는다(메타 흔적이 사용자 화면에 새지 않게).
 */
final class ChatReplyParser {

    static final String META_MARKER = "@@meta";
    static final int REPLY_MAX = 600;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatReplyParser() {
    }

    record Parsed(String reply, ChatMood mood, String suggest) {
    }

    static Parsed parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        String meta = null;
        int markerAt = text.lastIndexOf(META_MARKER);
        if (markerAt >= 0) {
            meta = text.substring(markerAt + META_MARKER.length()).strip();
            text = text.substring(0, markerAt).strip();
        } else {
            int lastBreak = text.lastIndexOf('\n');
            String lastLine = text.substring(lastBreak + 1).strip();
            if (lastLine.startsWith("{") && lastLine.endsWith("}") && lastLine.contains("\"mood\"")) {
                meta = lastLine;
                text = lastBreak < 0 ? "" : text.substring(0, lastBreak).strip();
            }
        }
        ChatMood mood = null;
        String suggest = null;
        if (meta != null) {
            try {
                JsonNode node = MAPPER.readTree(meta);
                if (node != null && node.isObject()) {
                    mood = moodOf(node.path("mood"));
                    JsonNode s = node.path("suggest");
                    if (s.isTextual() && "DIRECTIVE".equalsIgnoreCase(s.asText().strip())) {
                        suggest = "DIRECTIVE";
                    }
                }
            } catch (Exception e) {
                mood = null;
                suggest = null;
            }
        }
        return new Parsed(truncate(text), mood, suggest);
    }

    private static ChatMood moodOf(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        try {
            return ChatMood.valueOf(node.asText().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 서로게이트 쌍 한가운데서 자르면 깨진 문자가 나간다. */
    static String truncate(String s) {
        if (s.length() <= REPLY_MAX) {
            return s;
        }
        int end = REPLY_MAX - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end) + "…";
    }
}
