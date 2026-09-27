package com.platform.agentservice.config;

import com.platform.agentservice.pat.PatExpiryNotifier;
import com.platform.agentservice.runner.RunnerProperties;
import com.platform.agentservice.runner.RunnerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.ZoneId;

/**
 * 상시 유지보수 주기 작업(P4a) — 러너 생존 판정(D-P4-3, 기본 30초)과 사람용 PAT 만료 임박 알림(D-P4-3b, 기본 매일 09:00 Asia/Seoul).
 * 스케줄러 킬스위치({@code SCHEDULER_ENABLED})와 무관하게 돈다 — 러너 run은 디스패처가 꺼져 있어도 claim되고, 끊긴 러너의 run은 누군가
 * 정리해야 한다. {@code MeetingSchedulingConfig}와 같은 이유로 {@code @EnableScheduling}을 여기서도 건다(이때 {@code Dispatcher.tick}도
 * 스케줄되지만 {@code enabled} 확인으로 곧바로 돌아간다). 테스트 프로필은 {@code platform.agent.maintenance.enabled=false}로 끈다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "platform.agent.maintenance", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class MaintenanceSchedulingConfig implements SchedulingConfigurer {

    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final RunnerService runnerService;
    private final PatExpiryNotifier patExpiryNotifier;
    private final RunnerProperties runnerProperties;
    private final String tokenExpiryCron;

    public MaintenanceSchedulingConfig(RunnerService runnerService, PatExpiryNotifier patExpiryNotifier,
                                       RunnerProperties runnerProperties,
                                       @Value("${platform.agent.tokens.expiry-warn-cron:0 0 9 * * *}") String tokenExpiryCron) {
        this.runnerService = runnerService;
        this.patExpiryNotifier = patExpiryNotifier;
        this.runnerProperties = runnerProperties;
        this.tokenExpiryCron = tokenExpiryCron;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        Duration interval = Duration.ofMillis(runnerProperties.livenessIntervalMs());
        registrar.addFixedDelayTask(new FixedDelayTask(this::sweepSafely, interval, interval));
        if (tokenExpiryCron != null && !tokenExpiryCron.isBlank()) {
            registrar.addCronTask(new CronTask(this::warnSafely, new CronTrigger(tokenExpiryCron.trim(), ZONE)));
        }
    }

    private void sweepSafely() {
        try {
            runnerService.sweepOffline();
        } catch (Exception e) {
            log.warn("러너 생존 판정 주기 실패 — 다음 주기에 다시 봅니다: {}", e.getMessage());
        }
    }

    private void warnSafely() {
        try {
            patExpiryNotifier.warnExpiringSoon();
        } catch (Exception e) {
            log.warn("PAT 만료 임박 점검 실패 — 다음 주기에 다시 봅니다: {}", e.getMessage());
        }
    }
}
