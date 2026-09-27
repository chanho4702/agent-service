package com.platform.agentservice.config;

import com.platform.agentservice.run.MeetingProperties;
import com.platform.agentservice.run.MeetingService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 회고 cron 등록(P3b D-P3b-4②) — retro-cron이 있을 때만 켜지고, 스케줄러 킬스위치(SCHEDULER_ENABLED=false)와
 * 독립적으로 스케줄링 인프라가 서는지 본다.
 */
class MeetingRetroSchedulingConfigTest {

    @Test
    void registers_a_seoul_cron_task_that_runs_retros() {
        MeetingService meetingService = mock(MeetingService.class);
        MeetingRetroSchedulingConfig config = new MeetingRetroSchedulingConfig(meetingService,
                new MeetingProperties(7L, " 0 0 18 * * MON-FRI ", false, true));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        config.configureTasks(registrar);

        List<CronTask> tasks = registrar.getCronTaskList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getExpression()).isEqualTo("0 0 18 * * MON-FRI");
        assertThat(((CronTrigger) tasks.get(0).getTrigger()).getExpression()).isEqualTo("0 0 18 * * MON-FRI");
        tasks.get(0).getRunnable().run();
        verify(meetingService).runRetros();
    }

    @Nested
    @SpringBootTest(properties = {"platform.agent.meetings.retro-cron=0 0 18 * * *",
            "platform.agent.meetings.space-id=7", "platform.agent.scheduler.enabled=false"})
    @ActiveProfiles("test")
    class RetroOnlyWithSchedulerOff {

        @Autowired ApplicationContext context;
        @Autowired ScheduledTaskHolder holder;
        @MockitoBean MeetingService meetingService;

        @Test
        void retro_cron_is_scheduled_even_when_the_dispatcher_kill_switch_is_off() {
            assertThat(context.getBeansOfType(SchedulerEnablementConfig.class)).isEmpty();
            assertThat(context.getBeansOfType(MeetingRetroSchedulingConfig.class)).hasSize(1);
            Set<ScheduledTask> tasks = holder.getScheduledTasks();
            assertThat(tasks).anyMatch(t -> t.getTask() instanceof CronTask cron
                    && cron.getExpression().equals("0 0 18 * * *"));
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class DefaultOff {

        @Autowired ApplicationContext context;

        @Test
        void no_retro_cron_means_no_retro_scheduling_config() {
            assertThat(context.getBeansOfType(MeetingRetroSchedulingConfig.class)).isEmpty();
        }
    }
}
