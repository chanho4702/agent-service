package com.platform.agentservice.runner;

import com.platform.agentservice.execution.ExecutionSite;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunStatus;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 사무실 "러너 대기" 판정(D-P4-4) — QUEUED인데 지금 집어갈 러너가 없는가. */
class RunnerAvailabilityTest {

    private static Runner online(long id, Runner r) {
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    private static Run queued(long projectId, ExecutionSite site, Long pinned) {
        Run run = Run.queued(RunType.TASK, "AGP-1", projectId, 5L, RunTrigger.USER, "harness://default", null);
        run.assignExecutionSite(site);
        ReflectionTestUtils.setField(run, "runnerId", pinned);
        return run;
    }

    @Test
    void local_run_waits_until_a_runner_in_scope_is_online() {
        Runner project1 = online(1L, Runner.local("p1", 1L, 5L, "h1", "agr_1111"));
        Runner global = online(2L, Runner.local("g", null, 5L, "h2", "agr_2222"));

        assertThat(RunnerAvailability.Snapshot.empty(true).awaitingRunner(queued(1L, ExecutionSite.LOCAL, null))).isTrue();
        assertThat(new RunnerAvailability.Snapshot(List.of(project1), true).awaitingRunner(queued(1L, ExecutionSite.LOCAL, null)))
                .isFalse();
        assertThat(new RunnerAvailability.Snapshot(List.of(project1), true).awaitingRunner(queued(2L, ExecutionSite.LOCAL, null)))
                .as("다른 프로젝트 러너는 못 집는다").isTrue();
        assertThat(new RunnerAvailability.Snapshot(List.of(global), true).awaitingRunner(queued(2L, ExecutionSite.LOCAL, null)))
                .as("전역 러너는 어느 프로젝트든").isFalse();
    }

    @Test
    void pinned_run_waits_for_its_own_runner() {
        Runner other = online(3L, Runner.local("o", null, 5L, "h3", "agr_3333"));
        assertThat(new RunnerAvailability.Snapshot(List.of(other), true).awaitingRunner(queued(1L, ExecutionSite.LOCAL, 9L)))
                .isTrue();
    }

    @Test
    void server_run_waits_only_when_in_process_is_off_and_no_platform_runner_is_online() {
        Runner platform = online(4L, Runner.platform("h4", "agr_4444"));
        Run server = queued(1L, ExecutionSite.SERVER, null);

        assertThat(RunnerAvailability.Snapshot.empty(true).awaitingRunner(server)).isFalse();
        assertThat(RunnerAvailability.Snapshot.empty(false).awaitingRunner(server)).isTrue();
        assertThat(new RunnerAvailability.Snapshot(List.of(platform), false).awaitingRunner(server)).isFalse();
    }

    @Test
    void only_queued_runs_can_be_waiting() {
        Run running = queued(1L, ExecutionSite.LOCAL, null);
        ReflectionTestUtils.setField(running, "status", RunStatus.RUNNING);
        assertThat(RunnerAvailability.Snapshot.empty(false).awaitingRunner(running)).isFalse();
    }
}
