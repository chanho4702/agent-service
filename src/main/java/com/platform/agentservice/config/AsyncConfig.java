package com.platform.agentservice.config;

import com.platform.agentservice.run.SchedulerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 워커 실행 전용 스레드풀(P2a T4) — {@code RunService.execute}가 {@code
 * @Async("workerExecutor")}로 여기 태운다. core/max를 스케줄러 전역 동시성 상한
 * ({@link SchedulerProperties#maxConcurrentGlobal()})과 맞춰서, 풀 크기 자체가 2차
 * 동시성 제약이 되지 않게 한다 — 1차 제약은 Dispatcher의 DB count 체크다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public ThreadPoolTaskExecutor workerExecutor(SchedulerProperties schedulerProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int poolSize = Math.max(1, schedulerProperties.maxConcurrentGlobal());
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("agent-worker-");
        executor.initialize();
        return executor;
    }
}
