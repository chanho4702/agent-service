package com.platform.agentrunner;

import com.platform.agentservice.worker.CommandExecutor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * git·claude 가짜 — 실제 프로세스 없이 명령·cwd·extraEnv를 기록한다. {@code block}이면 claude가 {@link #kill}될 때까지 멈춘다(중단 테스트).
 */
class FakeExecutor implements CommandExecutor, ProcessKiller {

    static final String CLAUDE = "fake-claude";

    record Call(List<String> command, Path cwd, Map<String, String> env, String mcpConfig, boolean harnessPresent) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    final AtomicInteger kills = new AtomicInteger();
    final CountDownLatch claudeStarted = new CountDownLatch(1);
    volatile CountDownLatch killed = new CountDownLatch(1);
    volatile boolean block;

    @Override
    public ExecResult exec(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout) {
        String mcpConfig = null;
        boolean harness = false;
        if (command.get(0).equals(CLAUDE)) {
            int i = command.indexOf("--mcp-config");
            try {
                mcpConfig = Files.readString(Path.of(command.get(i + 1)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            harness = Files.isRegularFile(cwd.resolve("CLAUDE.md")) && Files.isRegularFile(cwd.resolve(".claude/agents/backend.md"));
        }
        calls.add(new Call(List.copyOf(command), cwd, Map.copyOf(extraEnv), mcpConfig, harness));

        if (command.get(0).equals("git")) {
            return switch (command.get(1)) {
                case "clone" -> {
                    try {
                        Files.createDirectories(cwd.resolve(".git"));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    yield new ExecResult(0, "", "", false);
                }
                case "config" -> new ExecResult(0, "https://github.com/o/r.git\n", "", false);
                case "log" -> new ExecResult(0, "abc123\tAGP-70: 러너 구현\n", "", false);
                default -> new ExecResult(0, "", "", false);
            };
        }
        claudeStarted.countDown();
        if (block) {
            try {
                if (!killed.await(20, TimeUnit.SECONDS)) {
                    return new ExecResult(-1, "", "", true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ExecResult(-1, "", "killed", true);
            }
            return new ExecResult(137, "", "killed", false);
        }
        return new ExecResult(0, "{\"result\":\"done\",\"session_id\":\"s-1\",\"total_cost_usd\":0.5,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20},\"model\":\"claude-test\"}", "", false);
    }

    @Override
    public void kill(Thread runThread) {
        kills.incrementAndGet();
        killed.countDown();
    }

    Call claudeCall() {
        return calls.stream().filter(c -> c.command().get(0).equals(CLAUDE)).findFirst().orElseThrow();
    }

    Call gitClone() {
        return calls.stream().filter(c -> c.command().size() > 1 && c.command().get(1).equals("clone")).findFirst().orElseThrow();
    }
}
