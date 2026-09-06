package com.platform.agentservice.budget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link BudgetService} — 순수 Mockito 단위테스트(P2a T5). {@link Clock}을 고정해 월 경계
 * (캘린더 월 1일 00:00 UTC) 계산을 정확히 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    private static final BigDecimal MONTHLY_CAP = new BigDecimal("100");
    private static final BigDecimal PER_RUN_CAP = new BigDecimal("5");
    private static final Instant FIXED_NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final Instant MONTH_START = Instant.parse("2026-09-01T00:00:00Z");

    @Mock UsageLedgerRepository usageLedgerRepository;

    private BudgetService serviceAt(Instant now) {
        BudgetProperties properties = new BudgetProperties(MONTHLY_CAP, PER_RUN_CAP);
        return new BudgetService(properties, usageLedgerRepository, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void allow_true_when_under_both_platform_and_project_caps() {
        BudgetService service = serviceAt(FIXED_NOW);
        when(usageLedgerRepository.sumCostSince(LedgerScope.PLATFORM, "platform", MONTH_START))
                .thenReturn(Optional.of(new BigDecimal("50")));
        when(usageLedgerRepository.sumCostSince(LedgerScope.PROJECT, "9", MONTH_START))
                .thenReturn(Optional.of(new BigDecimal("10")));

        assertThat(service.allow(9L)).isTrue();
    }

    @Test
    void allow_false_when_platform_month_to_date_reaches_cap() {
        BudgetService service = serviceAt(FIXED_NOW);
        when(usageLedgerRepository.sumCostSince(LedgerScope.PLATFORM, "platform", MONTH_START))
                .thenReturn(Optional.of(new BigDecimal("100")));

        assertThat(service.allow(9L)).isFalse();
    }

    @Test
    void allow_false_when_project_month_to_date_reaches_cap() {
        BudgetService service = serviceAt(FIXED_NOW);
        when(usageLedgerRepository.sumCostSince(LedgerScope.PLATFORM, "platform", MONTH_START))
                .thenReturn(Optional.of(new BigDecimal("10")));
        when(usageLedgerRepository.sumCostSince(LedgerScope.PROJECT, "9", MONTH_START))
                .thenReturn(Optional.of(new BigDecimal("100")));

        assertThat(service.allow(9L)).isFalse();
    }

    @Test
    void allow_false_when_kill_switch_on_regardless_of_ledger() {
        BudgetService service = serviceAt(FIXED_NOW);
        service.setKillSwitch(true);

        assertThat(service.allow(9L)).isFalse();
        Mockito.verifyNoInteractions(usageLedgerRepository); // 킬스위치가 먼저 막아 원장 조회 자체를 안 한다
    }

    @Test
    void allow_treats_missing_ledger_rows_as_zero() {
        BudgetService service = serviceAt(FIXED_NOW);
        when(usageLedgerRepository.sumCostSince(eq(LedgerScope.PLATFORM), eq("platform"), eq(MONTH_START)))
                .thenReturn(Optional.empty());
        when(usageLedgerRepository.sumCostSince(eq(LedgerScope.PROJECT), eq("9"), eq(MONTH_START)))
                .thenReturn(Optional.empty());

        assertThat(service.allow(9L)).isTrue();
    }

    @Test
    void allow_uses_calendar_month_start_not_a_rolling_window() {
        // 이번 달 첫날 자정 직후 시각이어도 조회 시작점은 여전히 이번 달 1일이다(rolling 30일 아님을 보증).
        BudgetService service = serviceAt(Instant.parse("2026-09-01T00:00:01Z"));
        when(usageLedgerRepository.sumCostSince(eq(LedgerScope.PLATFORM), eq("platform"), eq(MONTH_START)))
                .thenReturn(Optional.of(BigDecimal.ZERO));
        when(usageLedgerRepository.sumCostSince(eq(LedgerScope.PROJECT), eq("9"), eq(MONTH_START)))
                .thenReturn(Optional.of(BigDecimal.ZERO));

        assertThat(service.allow(9L)).isTrue();
        Mockito.verify(usageLedgerRepository).sumCostSince(LedgerScope.PLATFORM, "platform", MONTH_START);
        Mockito.verify(usageLedgerRepository).sumCostSince(LedgerScope.PROJECT, "9", MONTH_START);
    }

    @Test
    void snapshot_reflects_cap_month_to_date_sum_and_kill_switch() {
        BudgetService service = serviceAt(FIXED_NOW);
        when(usageLedgerRepository.sumCostSince(LedgerScope.PLATFORM, "platform", MONTH_START))
                .thenReturn(Optional.of(new BigDecimal("42")));

        BudgetService.BudgetSnapshot snapshot = service.snapshot();

        assertThat(snapshot.monthlyCapUsd()).isEqualByComparingTo("100");
        assertThat(snapshot.platformMonthToDateUsd()).isEqualByComparingTo("42");
        assertThat(snapshot.killSwitch()).isFalse();
    }

    @Test
    void kill_switch_getter_reflects_last_set_value() {
        BudgetService service = serviceAt(FIXED_NOW);
        assertThat(service.killSwitchOn()).isFalse();

        service.setKillSwitch(true);
        assertThat(service.killSwitchOn()).isTrue();

        service.setKillSwitch(false);
        assertThat(service.killSwitchOn()).isFalse();
    }
}
