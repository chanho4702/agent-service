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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 회의 계열 cron 등록(P3b 회고 D-P3b-4② + P3c 매니저 D-P3c-2) — 설정된 cron만 등록되고, 둘 다 없으면 설정 자체가 서지 않으며,
 * 스케줄러 킬스위치(SCHEDULER_ENABLED=false)와 독립적으로 스케줄링 인프라가 서는지 본다.
 */
class MeetingSchedulingConfigTest {

    private static List<CronTask> register(MeetingService meetingService, String retroCron, String managerCron) {
        MeetingSchedulingConfig config = new MeetingSchedulingConfig(meetingService,
                new MeetingProperties(7L, retroCron, false, true, managerCron));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        config.configureTasks(registrar);
        return registrar.getCronTaskList();
    }

    @Test
    void retro_only_registers_a_seoul_cron_task_that_runs_retros() {
        MeetingService meetingService = mock(MeetingService.class);

        List<CronTask> tasks = register(meetingService, " 0 0 18 * * MON-FRI ", null);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getExpression()).isEqualTo("0 0 18 * * MON-FRI");
        assertThat(((CronTrigger) tasks.get(0).getTrigger()).getExpression()).isEqualTo("0 0 18 * * MON-FRI");
        tasks.get(0).getRunnable().run();
        verify(meetingService).runRetros();
        verify(meetingService, never()).runManagerRounds();
    }

    @Test
    void manager_only_registers_a_cron_task_that_runs_manager_rounds() {
        MeetingService meetingService = mock(MeetingService.class);

        List<CronTask> tasks = register(meetingService, "  ", " 0 30 9 * * * ");

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getExpression()).isEqualTo("0 30 9 * * *");
        tasks.get(0).getRunnable().run();
        verify(meetingService).runManagerRounds();
        verify(meetingService, never()).runRetros();
    }

    @Test
    void both_crons_register_two_independent_tasks() {
        MeetingService meetingService = mock(MeetingService.class);

        List<CronTask> tasks = register(meetingService, "0 0 18 * * *", "0 30 9 * * *");

        assertThat(tasks).extracting(CronTask::getExpression).containsExactly("0 0 18 * * *", "0 30 9 * * *");
        tasks.forEach(t -> t.getRunnable().run());
        verify(meetingService).runRetros();
        verify(meetingService).runManagerRounds();
    }

    @Test
    void neither_cron_registers_nothing() {
        assertThat(register(mock(MeetingService.class), null, "")).isEmpty();
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
            assertThat(context.getBeansOfType(MeetingSchedulingConfig.class)).hasSize(1);
            Set<ScheduledTask> tasks = holder.getScheduledTasks();
            assertThat(tasks).anyMatch(t -> t.getTask() instanceof CronTask cron
                    && cron.getExpression().equals("0 0 18 * * *"));
        }
    }

    @Nested
    @SpringBootTest(properties = {"platform.agent.meetings.manager-cron=0 30 9 * * *",
            "platform.agent.meetings.space-id=7", "platform.agent.scheduler.enabled=false"})
    @ActiveProfiles("test")
    class ManagerOnlyWithSchedulerOff {

        @Autowired ApplicationContext context;
        @Autowired ScheduledTaskHolder holder;
        @MockitoBean MeetingService meetingService;

        @Test
        void manager_cron_alone_activates_the_config_and_is_scheduled() {
            assertThat(context.getBeansOfType(MeetingSchedulingConfig.class)).hasSize(1);
            Set<ScheduledTask> tasks = holder.getScheduledTasks();
            assertThat(tasks).anyMatch(t -> t.getTask() instanceof CronTask cron
                    && cron.getExpression().equals("0 30 9 * * *"));
            assertThat(tasks).noneMatch(t -> t.getTask() instanceof CronTask cron
                    && cron.getExpression().equals("0 0 18 * * *"));
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class DefaultOff {

        @Autowired ApplicationContext context;

        @Test
        void no_meeting_cron_means_no_meeting_scheduling_config() {
            assertThat(context.getBeansOfType(MeetingSchedulingConfig.class)).isEmpty();
        }
    }
}
