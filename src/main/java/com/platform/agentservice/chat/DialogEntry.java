package com.platform.agentservice.chat;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** 사무실 대화 기록 한 줄(V10). 소유자({@code userMemberId}) 본인만 읽는다. */
@Entity
@Table(name = "dialog_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DialogEntry {

    public static final int TEXT_MAX = 2000;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long userMemberId;
    @Column(nullable = false) private Long personaId;
    @Column(length = 40) private String sessionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private DialogSpeaker speaker;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private DialogKind kind;
    @Column(nullable = false, length = TEXT_MAX) private String text;
    @Column(length = 40) private String issueKey;
    private Long runId;
    private Long commentId;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;

    public static DialogEntry of(long userMemberId, long personaId, String sessionId, DialogSpeaker speaker, DialogKind kind,
                                 String text, String issueKey, Long runId, Long commentId) {
        DialogEntry e = new DialogEntry();
        e.userMemberId = userMemberId;
        e.personaId = personaId;
        e.sessionId = sessionId;
        e.speaker = speaker;
        e.kind = kind;
        e.text = text;
        e.issueKey = issueKey;
        e.runId = runId;
        e.commentId = commentId;
        return e;
    }
}
