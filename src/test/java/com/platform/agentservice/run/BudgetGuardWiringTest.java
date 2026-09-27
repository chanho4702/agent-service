package com.platform.agentservice.run;

import com.platform.agentservice.budget.BudgetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGP-50 — 실 컨텍스트에서 {@link BudgetService}가 permissive 기본 {@link BudgetGuard}를 밀어내는지.
 * {@code BudgetGuardConfig}의 {@code @ConditionalOnMissingBean}은 자동구성이 아닌 일반 설정에 붙어 있어
 * 빈 정의 등록 순서(현재는 패키지 스캔 순서 budget → run)에 기대고 있다 — 순서가 뒤집히면 킬 스위치·예산 캡이
 * 조용히 "항상 허용"이 된다. 이 테스트가 그 회귀를 잡는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetGuardWiringTest {

    @Autowired ApplicationContext context;
    @Autowired BudgetGuard budgetGuard;
    @Autowired RunService runService;

    @Test
    void budget_service_is_the_only_budget_guard_and_run_service_uses_it() {
        assertThat(context.getBeansOfType(BudgetGuard.class)).hasSize(1);
        assertThat(budgetGuard).isInstanceOf(BudgetService.class);
        assertThat(context.containsBean("permissiveBudgetGuard")).isFalse();
        assertThat(ReflectionTestUtils.getField(runService, "budgetGuard")).isSameAs(budgetGuard);
    }

    @Test
    void kill_switch_on_the_wired_guard_blocks_execution() {
        BudgetService budgetService = (BudgetService) budgetGuard;
        try {
            budgetService.setKillSwitch(true);
            assertThat(budgetGuard.allow(1L)).isFalse();
        } finally {
            budgetService.setKillSwitch(false);
        }
    }
}
