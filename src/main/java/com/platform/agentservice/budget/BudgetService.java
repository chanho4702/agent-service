package com.platform.agentservice.budget;

import com.platform.agentservice.run.BudgetGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * 예산 캡·킬 스위치의 실제 구현(P2a T5) — {@code BudgetGuardConfig}의 permissive 기본
 * {@link BudgetGuard}를 {@code @ConditionalOnMissingBean}에 의해 밀어낸다(이 클래스가
 * {@link BudgetGuard} 타입 빈으로 컴포넌트 스캔되는 시점에 그 조건이 이미 불만족되므로 —
 * {@code BudgetGuardConfig} 참고).
 *
 * <p>월 예산 집계는 "이번 달 1일 00:00 UTC"부터 현재까지의 {@link UsageLedgerRepository}
 * 합계다(캘린더 월 — rolling 30일 아님). 플랫폼 스코프와 프로젝트 스코프 모두 같은
 * {@code monthlyUsdCap}과 비교한다(P2a 결정 — 프로젝트별 개별 한도 테이블은 P2b).
 *
 * <p>킬 스위치는 순수 인메모리 {@code volatile} 플래그다 — 프로세스 재시작 시 항상
 * {@code false}로 리셋된다. 재기동 후에도 유지되는 영속화는 P2b 범위다.
 */
@Service
public class BudgetService implements BudgetGuard {

    private static final String PLATFORM_SCOPE_ID = "platform";

    private final BudgetProperties properties;
    private final UsageLedgerRepository usageLedgerRepository;
    private final Clock clock;

    private volatile boolean killSwitchOn = false;

    @Autowired
    public BudgetService(BudgetProperties properties, UsageLedgerRepository usageLedgerRepository) {
        this(properties, usageLedgerRepository, Clock.systemUTC());
    }

    /** 테스트 전용 — 월 경계(캘린더 월 1일)를 고정 시각으로 검증할 때 쓴다. */
    BudgetService(BudgetProperties properties, UsageLedgerRepository usageLedgerRepository, Clock clock) {
        this.properties = properties;
        this.usageLedgerRepository = usageLedgerRepository;
        this.clock = clock;
    }

    /** {@code Dispatcher}가 새 이슈 픽업 전에 호출한다 — 킬 스위치 ON, 플랫폼 캡 도달, 해당 프로젝트 캡 도달 중 하나면 false. */
    @Override
    public boolean allow(long projectId) {
        if (killSwitchOn) {
            return false;
        }
        Instant since = currentMonthStartUtc();
        BigDecimal platformSum = monthToDateSum(LedgerScope.PLATFORM, PLATFORM_SCOPE_ID, since);
        if (platformSum.compareTo(properties.monthlyUsdCap()) >= 0) {
            return false;
        }
        BigDecimal projectSum = monthToDateSum(LedgerScope.PROJECT, String.valueOf(projectId), since);
        return projectSum.compareTo(properties.monthlyUsdCap()) < 0;
    }

    public boolean killSwitchOn() {
        return killSwitchOn;
    }

    /** {@code POST /api/agent/kill-switch} — 전역, 인메모리, 재기동 시 리셋(javadoc 참고). */
    public void setKillSwitch(boolean on) {
        this.killSwitchOn = on;
    }

    /** {@code GET /api/agent/budget} — 감독용 스냅샷. 알림(경고 채널)은 P3 — 이 값을 폴링하는 것은 호출자 몫이다. */
    public BudgetSnapshot snapshot() {
        BigDecimal platformSum = monthToDateSum(LedgerScope.PLATFORM, PLATFORM_SCOPE_ID, currentMonthStartUtc());
        return new BudgetSnapshot(properties.monthlyUsdCap(), platformSum, killSwitchOn);
    }

    private BigDecimal monthToDateSum(LedgerScope scope, String scopeId, Instant since) {
        return usageLedgerRepository.sumCostSince(scope, scopeId, since).orElse(BigDecimal.ZERO);
    }

    private Instant currentMonthStartUtc() {
        return YearMonth.now(clock).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public record BudgetSnapshot(BigDecimal monthlyCapUsd, BigDecimal platformMonthToDateUsd, boolean killSwitch) {
    }
}
