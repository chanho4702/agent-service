package com.platform.agentservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 처리 인프라는 {@code platform.agent.scheduler.enabled=true}일 때만
 * 등록한다(기본 OFF — 킬스위치, P2a T4). 테스트 프로필도 이 값을 켜지 않으므로
 * {@code Dispatcher.tick()}이 스프링 컨텍스트 테스트 중에 실제로 틱하는 일이 없다 —
 * 디스패처 자체의 정책(동시성 상한·라벨 필터 등)은 순수 Mockito 단위테스트로 검증한다
 * ({@code DispatcherTest}). {@code @EnableScheduling}이 없으면 {@code @Scheduled} 메서드는
 * 그냥 무시될 뿐 오류가 나지 않는다 — Dispatcher 빈 자체는 평소처럼 생성된다.
 */
@Configuration
@ConditionalOnProperty(prefix = "platform.agent.scheduler", name = "enabled", havingValue = "true")
@EnableScheduling
public class SchedulerEnablementConfig {
}
