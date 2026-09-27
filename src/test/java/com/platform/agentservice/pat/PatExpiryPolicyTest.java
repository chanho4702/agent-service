package com.platform.agentservice.pat;

import com.platform.agentservice.alert.AlertService;
import com.platform.agentservice.pat.dto.PatCreateRequest;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.pat.dto.PatSummaryResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.common.error.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 사람용 PAT 위생(D-P4-3b) — 만료 기본 90일·1~365일·무기한은 전역 관리자 명시만, 만료 토큰 거부, 목록 경고 플래그, 7일 전 알림 1회.
 * 기존(V13 이전) 토큰과 run 토큰은 정책 밖이다.
 */
@DataJpaTest
@ActiveProfiles("test")
class PatExpiryPolicyTest {

    @Autowired PatTokenRepository patTokenRepository;
    @Autowired PersonaRepository personaRepository;

    private PatService patService;

    @BeforeEach
    void setUp() {
        patTokenRepository.deleteAllInBatch();
        personaRepository.deleteAllInBatch();
        patService = new PatService(patTokenRepository, personaRepository);
        personaRepository.save(Persona.of(9101L, "hy-bot", PersonaRole.BACKEND, "봇", null, null));
    }

    private PatCreateRequest request(Integer days, Boolean noExpiry) {
        return new PatCreateRequest("my-claude-code", "hy-bot", days, noExpiry);
    }

    @Test
    void default_expiry_is_90_days() {
        PatCreatedResponse issued = patService.issueForHuman(request(null, null), 1L, "a@example.com", false);

        assertThat(issued.expiresAt()).isCloseTo(Instant.now().plus(90, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));
        PatToken stored = patTokenRepository.findById(issued.id()).orElseThrow();
        assertThat(stored.getOwnerEmail()).isEqualTo("a@example.com");
    }

    @Test
    void request_may_choose_1_to_365_days() {
        assertThat(patService.issueForHuman(request(1, null), 1L, null, false).expiresAt())
                .isCloseTo(Instant.now().plus(1, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));
        assertThat(patService.issueForHuman(request(365, null), 1L, null, false).expiresAt())
                .isCloseTo(Instant.now().plus(365, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> patService.issueForHuman(request(0, null), 1L, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> patService.issueForHuman(request(366, null), 1L, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void no_expiry_only_for_global_admin_explicitly() {
        assertThatThrownBy(() -> patService.issueForHuman(request(null, true), 2L, null, false))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("무기한 토큰은 전역 관리자만 발급할 수 있습니다");
        assertThatThrownBy(() -> patService.issueForHuman(request(30, true), 1L, null, true))
                .isInstanceOf(IllegalArgumentException.class);

        PatCreatedResponse forever = patService.issueForHuman(request(null, true), 1L, null, true);
        assertThat(forever.expiresAt()).isNull();
        // 전역 관리자라도 명시하지 않으면 기본 90일이다.
        assertThat(patService.issueForHuman(request(null, false), 1L, null, true).expiresAt()).isNotNull();
    }

    @Test
    void expired_token_is_rejected() {
        PatCreatedResponse issued = patService.issueForHuman(request(1, null), 1L, null, false);
        PatToken token = patTokenRepository.findById(issued.id()).orElseThrow();
        ReflectionTestUtils.setField(token, "expiresAt", Instant.now().minusSeconds(1));
        patTokenRepository.saveAndFlush(token);

        assertThat(patService.validate(issued.token())).isEmpty();
    }

    @Test
    void runner_token_is_rejected_by_pat_validation() {
        assertThat(patService.validate("agr_" + "z".repeat(40))).isEmpty();
    }

    @Test
    void run_tokens_keep_no_expiry_and_existing_tokens_are_untouched() {
        PatCreatedResponse run = patService.issue(new PatCreateRequest("run:5", "hy-bot", null), PatToken.SYSTEM_OWNER_MEMBER_ID);
        assertThat(patTokenRepository.findById(run.id()).orElseThrow().getExpiresAt()).isNull();
    }

    @Test
    void list_flags_no_expiry_and_expiring_soon_and_kind() {
        PatCreatedResponse forever = patService.issueForHuman(request(null, true), 1L, null, true);
        PatCreatedResponse soon = patService.issueForHuman(request(3, null), 1L, null, false);
        PatCreatedResponse later = patService.issueForHuman(request(90, null), 1L, null, false);
        PatCreatedResponse run = patService.issue(new PatCreateRequest("run:6", "hy-bot", null), PatToken.SYSTEM_OWNER_MEMBER_ID);

        List<PatSummaryResponse> list = patService.list(null);
        PatSummaryResponse f = find(list, forever.id());
        PatSummaryResponse s = find(list, soon.id());
        PatSummaryResponse l = find(list, later.id());
        PatSummaryResponse r = find(list, run.id());
        assertThat(f.noExpiry()).isTrue();
        assertThat(f.expiringSoon()).isFalse();
        assertThat(s.expiringSoon()).isTrue();
        assertThat(s.noExpiry()).isFalse();
        assertThat(l.expiringSoon()).isFalse();
        assertThat(r.kind()).isEqualTo("RUN");
        assertThat(r.noExpiry()).as("run 토큰은 무기한 경고 대상이 아니다").isFalse();
        assertThat(f.kind()).isEqualTo("HUMAN");
    }

    private static PatSummaryResponse find(List<PatSummaryResponse> list, long id) {
        return list.stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }

    // ---- 만료 7일 전 알림 ----

    @Test
    void expiring_tokens_are_mailed_once_per_owner_and_not_again_the_next_day() {
        AlertService alertService = Mockito.mock(AlertService.class);
        when(alertService.notifyTokensExpiring(any(), anyLong(), any())).thenReturn(true);
        Instant now = Instant.now();
        PatExpiryNotifier notifier = new PatExpiryNotifier(patTokenRepository, personaRepository, alertService,
                Clock.fixed(now, ZoneOffset.UTC));

        patService.issueForHuman(request(3, null), 1L, "a@example.com", false);
        patService.issueForHuman(request(5, null), 1L, "a@example.com", false);
        patService.issueForHuman(request(2, null), 2L, null, false);           // 메일 미상 → 운영 수신자
        patService.issueForHuman(request(30, null), 1L, "a@example.com", false); // 창 밖
        patService.issue(new PatCreateRequest("run:9", "hy-bot", 1), PatToken.SYSTEM_OWNER_MEMBER_ID); // run 토큰 제외

        assertThat(notifier.warnExpiringSoon()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AlertService.ExpiringToken>> tokens = ArgumentCaptor.forClass(List.class);
        verify(alertService).notifyTokensExpiring(eq("a@example.com"), eq(1L), tokens.capture());
        assertThat(tokens.getValue()).hasSize(2);
        verify(alertService).notifyTokensExpiring(isNull(), eq(2L), any());

        // 다음 날 다시 돌아도 같은 토큰에는 보내지 않는다.
        PatExpiryNotifier tomorrow = new PatExpiryNotifier(patTokenRepository, personaRepository, alertService,
                Clock.fixed(now.plus(Duration.ofDays(1)), ZoneOffset.UTC));
        assertThat(tomorrow.warnExpiringSoon()).isZero();
        verify(alertService, times(2)).notifyTokensExpiring(any(), anyLong(), any());
    }

    @Test
    void failed_notification_is_retried_next_run() {
        AlertService alertService = Mockito.mock(AlertService.class);
        when(alertService.notifyTokensExpiring(any(), anyLong(), any())).thenReturn(false);
        PatExpiryNotifier notifier = new PatExpiryNotifier(patTokenRepository, personaRepository, alertService,
                Clock.fixed(Instant.now(), ZoneOffset.UTC));
        PatCreatedResponse issued = patService.issueForHuman(request(3, null), 1L, "a@example.com", false);

        assertThat(notifier.warnExpiringSoon()).isZero();
        assertThat(patTokenRepository.findById(issued.id()).orElseThrow().getExpiryWarnedAt()).isNull();
        verify(alertService, never()).notifyBlocked(any(), any(), any());
    }
}
