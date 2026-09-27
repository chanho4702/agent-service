package com.platform.agentservice.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 러너가 재사용할 실행부(P4a AGP-69) — {@link WorkerExecution}은 run 엔티티·DB 없이 {@link WorkSpec} + 인증 콜백만으로 서버 인프로세스와
 * 같은 절차를 밟는다. 인증(토큰)은 워크스페이스 준비가 끝난 뒤에만 요청되고, mcp-config는 명세의 MCP 주소·토큰으로 클론 밖에 썼다가 지운다.
 */
class WorkerExecutionTest {

    @TempDir Path workDir;

    private final List<List<String>> commands = new ArrayList<>();
    private String mcpConfigAtCall;
    private Map<String, String> envAtClaude;

    private CommandExecutor executor(int cloneExit, String claudeStdout) {
        return (command, cwd, extraEnv, timeout) -> {
            commands.add(command);
            if (command.get(0).equals("git")) {
                return new CommandExecutor.ExecResult(cloneExit, "", cloneExit == 0 ? "" : "fatal: repo not found", false);
            }
            int idx = command.indexOf("--mcp-config");
            try {
                mcpConfigAtCall = Files.readString(Path.of(command.get(idx + 1)));
            } catch (IOException e) {
                mcpConfigAtCall = null;
            }
            envAtClaude = extraEnv;
            return new CommandExecutor.ExecResult(0, claudeStdout, "", false);
        };
    }

    private WorkerExecution execution(CommandExecutor executor) {
        WorkerProperties props = new WorkerProperties(workDir.toString(), workDir.resolve("no-bundle").toString(), List.of(),
                "claude-local", 80, 40, "Read", "unused", Map.of(), List.of(), false);
        return new WorkerExecution(workDir.toString(), "claude-local", new HarnessMaterializer(props), executor);
    }

    private static WorkSpec spec(boolean lineage, String inherited) {
        return new WorkSpec(42L, "TASK", lineage, inherited, false, "https://example.com/r.git", "프롬프트", "claude-x",
                80, "Read,Edit", 40, "https://platform.example/api/agent/mcp", null);
    }

    @Test
    void runs_spec_with_supplied_token_and_public_mcp_url_then_deletes_the_config() {
        AtomicInteger authorized = new AtomicInteger();
        WorkerResult result = execution(executor(0, "{\"result\":\"ok\",\"session_id\":\"s1\",\"total_cost_usd\":0.5}"))
                .execute(spec(false, null), () -> {
                    authorized.incrementAndGet();
                    return new WorkerExecution.Credentials("agp_runner-run-token", Map.of("ANTHROPIC_API_KEY", "sk-x"));
                });

        assertThat(result.succeeded()).isTrue();
        assertThat(result.sessionId()).isEqualTo("s1");
        assertThat(authorized).hasValue(1);
        assertThat(mcpConfigAtCall).contains("https://platform.example/api/agent/mcp").contains("Bearer agp_runner-run-token");
        assertThat(envAtClaude).containsEntry("ANTHROPIC_API_KEY", "sk-x");
        List<String> claude = commands.get(1);
        assertThat(claude.get(0)).as("claude 실행 파일은 실행하는 쪽(러너) 설정").isEqualTo("claude-local");
        assertThat(claude).containsSequence("--model", "claude-x").containsSequence("--allowedTools", "Read,Edit");
        assertThat(Files.exists(workDir.resolve("run-42-cfg"))).as("토큰이 담긴 설정 디렉터리는 실행 후 지운다").isFalse();
    }

    @Test
    void clone_failure_never_asks_for_credentials() {
        AtomicInteger authorized = new AtomicInteger();
        WorkerResult result = execution(executor(128, "")).execute(spec(false, null), () -> {
            authorized.incrementAndGet();
            return new WorkerExecution.Credentials("t", Map.of());
        });

        assertThat(result.succeeded()).isFalse();
        assertThat(authorized).hasValue(0);
    }

    @Test
    void missing_inherited_workspace_fails_instead_of_cloning() {
        WorkerResult result = execution(executor(0, "{}")).execute(spec(true, workDir.resolve("gone").toString()),
                () -> new WorkerExecution.Credentials("t", Map.of()));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.rawTail()).contains("승계할 워크스페이스가 없습니다");
        assertThat(commands).isEmpty();
    }

    @Test
    void tail_keeps_last_2000_chars() {
        assertThat(WorkerExecution.tail("x".repeat(2500) + "END")).hasSize(2000).endsWith("END");
        assertThat(WorkerExecution.tail(null)).isNull();
    }
}
