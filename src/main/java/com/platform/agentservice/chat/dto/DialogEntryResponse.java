package com.platform.agentservice.chat.dto;

import com.platform.agentservice.chat.DialogEntry;
import com.platform.agentservice.chat.DialogKind;
import com.platform.agentservice.chat.DialogSpeaker;

import java.time.Instant;

/** id·runId·commentId는 문자열(프론트 계약 — JS 정밀도·기존 store 매핑 규약). */
public record DialogEntryResponse(String id, DialogSpeaker speaker, DialogKind kind, String text, String issueKey,
                                  String runId, String commentId, Instant createdAt) {

    public static DialogEntryResponse of(DialogEntry e) {
        return new DialogEntryResponse(String.valueOf(e.getId()), e.getSpeaker(), e.getKind(), e.getText(), e.getIssueKey(),
                e.getRunId() == null ? null : String.valueOf(e.getRunId()),
                e.getCommentId() == null ? null : String.valueOf(e.getCommentId()),
                e.getCreatedAt());
    }
}
