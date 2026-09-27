package com.platform.agentservice.chat;

import com.platform.agentservice.chat.dto.DialogAppendRequest;
import com.platform.agentservice.chat.dto.DialogEntryResponse;
import com.platform.agentservice.chat.dto.DialogListResponse;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.tools.ToolInputGuard;
import com.platform.common.error.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 사무실 대화 기록(P3g ③). <b>소유자는 호출자 JWT의 사용자 하나뿐</b>이다 — 모든 조회·삭제가 (userMemberId, personaId) 축으로만
 * 돈다(관리자도 남의 기록을 못 본다 — 사적 대화). 보존(90일·사용자×페르소나 500건)은 별도 배치 없이 쓰기 시점에 정리한다.
 *
 * <p>오류 문구에는 입력 본문을 싣지 않는다(위반 위치·사유만).
 */
@Service
public class DialogService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    static final int APPEND_MAX = 20;
    static final int ISSUE_KEY_MAX = 40;
    private static final Pattern DIGITS = Pattern.compile("\\d{1,18}");

    private final DialogEntryRepository repository;
    private final PersonaRepository personaRepository;
    private final ChatProperties properties;
    private final Clock clock;

    @Autowired
    public DialogService(DialogEntryRepository repository, PersonaRepository personaRepository, ChatProperties properties) {
        this(repository, personaRepository, properties, Clock.systemUTC());
    }

    DialogService(DialogEntryRepository repository, PersonaRepository personaRepository, ChatProperties properties, Clock clock) {
        this.repository = repository;
        this.personaRepository = personaRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DialogListResponse list(long userId, long personaId, String before, Integer limit) {
        requirePersona(personaId);
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new IllegalArgumentException("limit은 1~" + MAX_LIMIT + " 사이여야 합니다");
        }
        PageRequest page = PageRequest.of(0, size + 1);
        List<DialogEntry> rows;
        if (before == null || before.isBlank()) {
            rows = repository.findByUserMemberIdAndPersonaIdOrderByIdDesc(userId, personaId, page);
        } else {
            rows = repository.findByUserMemberIdAndPersonaIdAndIdLessThanOrderByIdDesc(userId, personaId,
                    parseId(before.strip(), "before"), page);
        }
        boolean hasMore = rows.size() > size;
        List<DialogEntryResponse> entries = new ArrayList<>(rows.subList(0, Math.min(size, rows.size())).stream()
                .map(DialogEntryResponse::of).toList());
        Collections.reverse(entries);
        return new DialogListResponse(entries, hasMore);
    }

    /** 프론트가 남기는 STATUS·DIRECTIVE·ASSIGN(및 SAY) 행 — 서버 수다 세션에 섞이지 않게 sessionId는 비운다. */
    @Transactional
    public int append(long userId, long personaId, DialogAppendRequest request) {
        requirePersona(personaId);
        if (request == null || request.entries() == null || request.entries().isEmpty()) {
            throw new IllegalArgumentException("entries가 필요합니다");
        }
        if (request.entries().size() > APPEND_MAX) {
            throw new IllegalArgumentException("한 번에 " + APPEND_MAX + "건까지 저장할 수 있습니다");
        }
        List<DialogEntry> rows = new ArrayList<>();
        for (int i = 0; i < request.entries().size(); i++) {
            rows.add(toEntry(userId, personaId, request.entries().get(i), "entries[" + i + "]"));
        }
        repository.saveAll(rows);
        prune(userId, personaId);
        return rows.size();
    }

    /** 수다 한 턴(사용자 SAY + 페르소나 SAY)을 한 트랜잭션으로 — 문맥이 늘 사용자/페르소나 교대가 되게 성공한 턴만 남긴다. */
    @Transactional
    public void recordChatTurn(long userId, long personaId, String sessionId, String userText, String reply) {
        repository.save(DialogEntry.of(userId, personaId, sessionId, DialogSpeaker.USER, DialogKind.SAY, userText, null, null, null));
        repository.save(DialogEntry.of(userId, personaId, sessionId, DialogSpeaker.PERSONA, DialogKind.SAY, reply, null, null, null));
        prune(userId, personaId);
    }

    /** 같은 세션의 최근 SAY 턴(오래된 것 먼저). */
    @Transactional(readOnly = true)
    public List<DialogEntry> recentSessionTurns(long userId, long personaId, String sessionId, int turns) {
        List<DialogEntry> rows = new ArrayList<>(repository.findByUserMemberIdAndPersonaIdAndSessionIdAndKindOrderByIdDesc(
                userId, personaId, sessionId, DialogKind.SAY, PageRequest.of(0, Math.max(1, turns) * 2)));
        Collections.reverse(rows);
        return rows;
    }

    /**
     * 보존 정리 — 90일 지난 행은 전역으로(쓰기가 없는 사용자 기록도 결국 지워지게, created_at 인덱스), 건수 상한은 이 사용자×페르소나만.
     * 한계: 아무도 쓰지 않는 기간에는 90일 정리도 돌지 않는다(다음 쓰기 때 한꺼번에).
     */
    void prune(long userId, long personaId) {
        repository.deleteOlderThan(Instant.now(clock).minus(Duration.ofDays(properties.retentionDays())));
        List<Long> overflow = repository.findIdsNewestFirst(userId, personaId, PageRequest.of(properties.retentionMax(), 1));
        if (!overflow.isEmpty()) {
            repository.deleteOwnedUpTo(userId, personaId, overflow.get(0));
        }
    }

    private void requirePersona(long personaId) {
        if (!personaRepository.existsById(personaId)) {
            throw new NotFoundException("페르소나를 찾을 수 없습니다: " + personaId);
        }
    }

    private static DialogEntry toEntry(long userId, long personaId, DialogAppendRequest.Item item, String path) {
        if (item == null) {
            throw new IllegalArgumentException(path + "가 비었습니다");
        }
        DialogSpeaker speaker = parseEnum(DialogSpeaker.class, item.speaker(), path + ".speaker");
        DialogKind kind = parseEnum(DialogKind.class, item.kind(), path + ".kind");
        String text = checkText(item.text(), path + ".text", DialogEntry.TEXT_MAX);
        String issueKey = null;
        if (item.issueKey() != null && !item.issueKey().isBlank()) {
            issueKey = checkText(item.issueKey().strip(), path + ".issueKey", ISSUE_KEY_MAX);
        }
        Long runId = optionalId(item.runId(), path + ".runId");
        Long commentId = optionalId(item.commentId(), path + ".commentId");
        return DialogEntry.of(userId, personaId, null, speaker, kind, text, issueKey, runId, commentId);
    }

    /** 1~max자 + 입력 인코딩 규약(깨진 서로게이트·제어문자·U+FFFD 거부 — MCP 도구와 같은 {@link ToolInputGuard} 판정). */
    static String checkText(String text, String field, int max) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + "가 필요합니다");
        }
        if (text.length() > max) {
            throw new IllegalArgumentException(field + "는 " + max + "자 이하여야 합니다");
        }
        String reason = ToolInputGuard.invalidReason(text);
        if (reason != null) {
            throw new IllegalArgumentException("입력 인코딩 거부 — " + field + ": " + reason);
        }
        return text;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + "가 필요합니다");
        }
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " 값이 올바르지 않습니다");
        }
    }

    private static Long optionalId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseId(raw.strip(), field);
    }

    private static long parseId(String raw, String field) {
        if (!DIGITS.matcher(raw).matches()) {
            throw new IllegalArgumentException(field + "는 숫자 id여야 합니다");
        }
        return Long.parseLong(raw);
    }
}
