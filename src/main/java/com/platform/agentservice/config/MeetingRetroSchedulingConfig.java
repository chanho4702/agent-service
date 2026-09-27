package com.platform.agentservice.config;

import com.platform.agentservice.run.MeetingProperties;
import com.platform.agentservice.run.MeetingService;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.util.StringUtils;

import java.time.ZoneId;

/**
 * 회고 주기 실행(P3b, D-P3b-4②) — {@code platform.agent.meetings.retro-cron}이 비어 있지 않을 때만 등록된다.
 *
 * <p>스케줄러 킬스위치({@code SCHEDULER_ENABLED}, {@link SchedulerEnablementConfig})와 독립이다 — 무인 이슈 픽업은 끄고
 * 회고만 켜는 조합이 동작해야 해서 {@code @EnableScheduling}을 여기서도 건다(중복 선언은 같은 후처리기 하나로 합쳐진다).
 * 이 경우 {@code Dispatcher.tick}도 스케줄에 걸리지만 첫 줄의 {@code enabled} 확인으로 곧바로 돌아간다. 킬 스위치·예산
 * 캡은 {@code RunService.execute} 진입점이 회고 run에도 똑같이 건다.
 *
 * <p>{@code @Scheduled(cron = "${...}")} 대신 프로그램 등록을 쓰는 이유: 설정이 빈 문자열이면 애노테이션 경로는 기동
 * 오류가 난다. 시간대는 Asia/Seoul — 사무실 "오늘" 경계(OfficeService)와 같은 달력이다. 잘못된 cron은 기동 시 실패한다.
 */
@Configuration
@Conditional(MeetingRetroSchedulingConfig.RetroCronConfigured.class)
@EnableScheduling
public class MeetingRetroSchedulingConfig implements SchedulingConfigurer {

    static final ZoneId RETRO_ZONE = ZoneId.of("Asia/Seoul");

    private final MeetingService meetingService;
    private final MeetingProperties meetingProperties;

    public MeetingRetroSchedulingConfig(MeetingService meetingService, MeetingProperties meetingProperties) {
        this.meetingService = meetingService;
        this.meetingProperties = meetingProperties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addCronTask(new CronTask(meetingService::runRetros,
                new CronTrigger(meetingProperties.retroCron().trim(), RETRO_ZONE)));
    }

    static class RetroCronConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("platform.agent.meetings.retro-cron"));
        }
    }
}
