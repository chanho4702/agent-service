package com.platform.agentservice.pat;

import com.platform.agentservice.pat.dto.PatCreateRequest;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 발급→해시만 저장(평문 재조회 불가), validate()의 성공/만료/철회/미존재 경로,
 * lastUsedAt 스로틀 갱신을 실제 JPA 리포지토리로 검증한다.
 */
@DataJpaTest
@ActiveProfiles("test")
class PatServiceTest {

    @Autowired PatTokenRepository patTokenRepository;
    @Autowired PersonaRepository personaRepository;

    private PatService patService;
    private Persona persona;

    @BeforeEach
    void setUp() {
        patTokenRepository.deleteAllInBatch();
        personaRepository.deleteAllInBatch();
        patService = new PatService(patTokenRepository, personaRepository);

        persona = personaRepository.save(
                Persona.of(9001L, "qa-bot", PersonaRole.REVIEWER, "QA Bot", "🤖", "너는 리뷰어다"));
    }

    @Test
    void issue_stores_only_hash_and_plaintext_is_not_re_derivable() {
        PatCreatedResponse response = patService.issue(
                new PatCreateRequest("ci-token", "qa-bot", null), 1L);

        assertThat(response.token()).startsWith("agp_");
        assertThat(response.personaSlug()).isEqualTo("qa-bot");

        PatToken stored = patTokenRepository.findById(response.id()).orElseThrow();
        assertThat(stored.getTokenHash()).hasSize(64); // SHA-256 hex
        assertThat(stored.getTokenHash()).isNotEqualTo(response.token());
        // 저장된 해시로부터 평문을 복원할 방법이 없다 — 오직 같은 평문을 다시 해시해야 일치한다.
        assertThat(patTokenRepository.findByTokenHash(response.token())).isEmpty();
    }

    @Test
    void issue_rejects_unknown_persona_slug_with_not_found() {
        assertThatThrownBy(() -> patService.issue(new PatCreateRequest("t", "no-such-slug", null), 1L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void issue_rejects_inactive_persona_with_conflict() {
        ReflectionTestUtils.setField(persona, "active", false);
        personaRepository.saveAndFlush(persona);

        assertThatThrownBy(() -> patService.issue(new PatCreateRequest("t", "qa-bot", null), 1L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void issue_with_expiresInDays_sets_expiresAt_in_future() {
        PatCreatedResponse response = patService.issue(
                new PatCreateRequest("t", "qa-bot", 30), 1L);

        PatToken stored = patTokenRepository.findById(response.id()).orElseThrow();
        assertThat(stored.getExpiresAt()).isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
    }

    @Test
    void issue_without_expiresInDays_never_expires() {
        PatCreatedResponse response = patService.issue(new PatCreateRequest("t", "qa-bot", null), 1L);

        PatToken stored = patTokenRepository.findById(response.id()).orElseThrow();
        assertThat(stored.getExpiresAt()).isNull();
    }

    @Test
    void validate_returns_principal_for_valid_token() {
        PatCreatedResponse response = patService.issue(new PatCreateRequest("t", "qa-bot", null), 42L);

        Optional<PatPrincipal> principal = patService.validate(response.token());

        assertThat(principal).isPresent();
        assertThat(principal.get().ownerMemberId()).isEqualTo(42L);
        assertThat(principal.get().personaId()).isEqualTo(persona.getId());
        assertThat(principal.get().personaMemberId()).isEqualTo(persona.getMemberId());
    }

    @Test
    void validate_rejects_unknown_token() {
        assertThat(patService.validate("agp_does-not-exist")).isEmpty();
    }

    @Test
    void validate_rejects_expired_token() {
        PatCreatedResponse response = patService.issue(new PatCreateRequest("t", "qa-bot", 1), 1L);
        PatToken stored = patTokenRepository.findById(response.id()).orElseThrow();
        ReflectionTestUtils.setField(stored, "expiresAt", Instant.now().minus(1, ChronoUnit.DAYS));
        patTokenRepository.saveAndFlush(stored);

        assertThat(patService.validate(response.token())).isEmpty();
    }

    @Test
    void validate_rejects_revoked_token() {
        PatCreatedResponse response = patService.issue(new PatCreateRequest("t", "qa-bot", null), 1L);
        patService.revoke(response.id());

        assertThat(patService.validate(response.token())).isEmpty();
    }

    @Test
    void validate_updates_lastUsedAt_on_first_use() {
        PatCreatedResponse response = patService.issue(new PatCreateRequest("t", "qa-bot", null), 1L);
        assertThat(patTokenRepository.findById(response.id()).orElseThrow().getLastUsedAt()).isNull();

        patService.validate(response.token());

        assertThat(patTokenRepository.findById(response.id()).orElseThrow().getLastUsedAt()).isNotNull();
    }

    @Test
    void validate_throttles_lastUsedAt_updates_within_window() {
        PatCreatedResponse response = patService.issue(new PatCreateRequest("t", "qa-bot", null), 1L);
        PatToken stored = patTokenRepository.findById(response.id()).orElseThrow();
        Instant recentUse = Instant.now().minusSeconds(5);
        ReflectionTestUtils.setField(stored, "lastUsedAt", recentUse);
        patTokenRepository.saveAndFlush(stored);

        patService.validate(response.token());

        Instant after = patTokenRepository.findById(response.id()).orElseThrow().getLastUsedAt();
        assertThat(after).isEqualTo(recentUse); // 60초 창 안이라 갱신 안 함
    }

    @Test
    void list_maps_persona_slug_and_revoked_flag_without_exposing_hash() {
        PatCreatedResponse issued = patService.issue(new PatCreateRequest("ci", "qa-bot", null), 1L);

        List<com.platform.agentservice.pat.dto.PatSummaryResponse> list = patService.list();

        assertThat(list).hasSize(1);
        var summary = list.get(0);
        assertThat(summary.id()).isEqualTo(issued.id());
        assertThat(summary.personaSlug()).isEqualTo("qa-bot");
        assertThat(summary.revoked()).isFalse();
    }

    @Test
    void revoke_is_idempotent() {
        PatCreatedResponse response = patService.issue(new PatCreateRequest("t", "qa-bot", null), 1L);

        patService.revoke(response.id());
        Instant firstRevokedAt = patTokenRepository.findById(response.id()).orElseThrow().getRevokedAt();
        patService.revoke(response.id()); // 두 번째 호출도 예외 없이 끝나야 한다
        Instant secondRevokedAt = patTokenRepository.findById(response.id()).orElseThrow().getRevokedAt();

        assertThat(secondRevokedAt).isEqualTo(firstRevokedAt);
    }

    @Test
    void revoke_unknown_id_does_not_throw() {
        patService.revoke(999_999L);
    }
}
