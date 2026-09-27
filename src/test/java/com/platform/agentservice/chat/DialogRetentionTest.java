package com.platform.agentservice.chat;

import com.platform.agentservice.chat.dto.DialogAppendRequest;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 보존(P3g ③) — 쓰기 시점에 사용자×페르소나 상한 초과분(오래된 것부터)·90일 지난 행을 지운다. 다른 소유자 행은 상한 정리 대상이 아니다. */
@DataJpaTest
@ActiveProfiles("test")
class DialogRetentionTest {

    @Autowired DialogEntryRepository repository;
    @Autowired PersonaRepository personas;
    @Autowired EntityManager em;

    final Instant now = Instant.parse("2026-09-27T03:00:00Z");
    Persona persona;
    DialogService service;

    @BeforeEach
    void setUp() {
        persona = personas.save(Persona.of(7001L, "retention", PersonaRole.PLANNER, "서연", null, null));
        service = new DialogService(repository, personas, new ChatProperties(true, "m", 400, 30, Duration.ofMinutes(10),
                BigDecimal.ONE, BigDecimal.ONE, 10, 90, 3), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static DialogAppendRequest one(String text) {
        return new DialogAppendRequest(List.of(new DialogAppendRequest.Item("USER", "SAY", text, null, null, null)));
    }

    @Test
    void keeps_only_the_newest_n_per_owner_and_persona() {
        for (int i = 1; i <= 5; i++) {
            service.append(1L, persona.getId(), one("m" + i));
        }
        service.append(2L, persona.getId(), one("other-owner"));

        assertThat(repository.findAll()).filteredOn(e -> e.getUserMemberId() == 1L)
                .extracting(DialogEntry::getText).containsExactlyInAnyOrder("m3", "m4", "m5");
        assertThat(repository.findAll()).filteredOn(e -> e.getUserMemberId() == 2L).hasSize(1);
    }

    @Test
    void rows_older_than_retention_days_are_removed_on_write() {
        service.append(1L, persona.getId(), one("old"));
        service.append(2L, persona.getId(), one("old-other"));
        em.flush();
        em.createNativeQuery("update dialog_entry set created_at = :t")
                .setParameter("t", java.sql.Timestamp.from(now.minus(Duration.ofDays(91)))).executeUpdate();
        em.clear();

        service.append(1L, persona.getId(), one("new"));

        assertThat(repository.findAll()).extracting(DialogEntry::getText).containsExactly("new");
    }

    @Test
    void chat_turn_is_recorded_as_two_say_rows_in_the_session() {
        service.recordChatTurn(1L, persona.getId(), "c-abcdef01", "질문", "대답");

        assertThat(service.recentSessionTurns(1L, persona.getId(), "c-abcdef01", 10))
                .extracting(DialogEntry::getSpeaker, DialogEntry::getText)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(DialogSpeaker.USER, "질문"),
                        org.assertj.core.groups.Tuple.tuple(DialogSpeaker.PERSONA, "대답"));
    }
}
