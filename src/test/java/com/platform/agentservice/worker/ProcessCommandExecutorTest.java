package com.platform.agentservice.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProcessCommandExecutor} 실 프로세스 스모크 테스트(P2a T3) — Windows PowerShell로
 * 정상 종료·타임아웃 강제종료 두 경로만 확인한다. 실제 {@code claude -p} 통합은 후속 태스크(T7)의
 * E2E 몫이다.
 *
 * <p>워커 호스트=Windows 전용 실프로세스 스모크; CI(Linux)에서는 스킵한다 — {@code cmd}/
 * {@code powershell} 커맨드를 그대로 실행해 이식성이 없고, 이 스킬을 이식 가능하게 다시 쓰는
 * 대신(가짜로 우회하면 실제 ProcessBuilder 동작을 못 본다) CI 대상 OS에서 스킵을 택한다
 * (컨트롤러 판정, CI 레드 마이크로 픽스).
 */
@EnabledOnOs(OS.WINDOWS)
class ProcessCommandExecutorTest {

    private final ProcessCommandExecutor executor = new ProcessCommandExecutor();

    @Test
    void runs_command_and_captures_stdout(@TempDir Path cwd) {
        CommandExecutor.ExecResult result = executor.exec(
                List.of("cmd", "/c", "echo hello-world"), cwd, Map.of(), Duration.ofSeconds(10));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello-world");
    }

    @Test
    void kills_process_and_reports_timeout_when_exceeding_duration(@TempDir Path cwd) {
        // PowerShell sleep longer than the enforced timeout so we exercise destroyForcibly.
        CommandExecutor.ExecResult result = executor.exec(
                List.of("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 30"),
                cwd, Map.of(), Duration.ofSeconds(2));

        assertThat(result.timedOut()).isTrue();
    }

    @Test
    void passes_extra_env_to_child_process(@TempDir Path cwd) {
        CommandExecutor.ExecResult result = executor.exec(
                List.of("cmd", "/c", "echo %AGENT_TEST_ENV_VAR%"),
                cwd, Map.of("AGENT_TEST_ENV_VAR", "worker-value"), Duration.ofSeconds(10));

        assertThat(result.stdout()).contains("worker-value");
    }
}
