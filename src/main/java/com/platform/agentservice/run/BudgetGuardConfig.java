package com.platform.agentservice.run;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link BudgetGuard}의 기본(permissive) 구현 — T5가 실제 {@code BudgetService}를 빈으로
 * 등록하면 {@code @ConditionalOnMissingBean}에 의해 이 기본값은 자동으로 밀려난다. 이
 * 태스크(P2a T4) 시점에는 예산 집계가 아직 없으므로 항상 허용한다(디스패처 픽업 자체를
 * 막지 않음).
 */
@Configuration
public class BudgetGuardConfig {

    @Bean
    @ConditionalOnMissingBean(BudgetGuard.class)
    BudgetGuard permissiveBudgetGuard() {
        return projectId -> true;
    }
}
