package com.platform.agentrunner;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 러너 루프 왕복 — 스텁 서버(claim·heartbeat·harness·result) + 가짜 git/claude로 claim → 실행 → 결과 보고, stopRunIds 중단, 결과 재시도,
 * 하네스 캐시, 인증 env 분리(PLATFORM 키는 워커에만·LOCAL은 없음), 토큰 비노출을 확인한다.
 */
class RunnerLoopTest {

    static final String RUNNER_TOKEN = "agr_runner-SECRET-0123456789";
    static final String RUN_TOKEN = "agp_run-token-SECRET";
    static final String PLATFORM_KEY = "sk-ant-platform-SECRET";

    @TempDir
    Path work;
    StubServer stub;
    FakeExecutor executor;
    Runner runner;
    ByteArrayOutputStream logs;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubServer();
        executor = new FakeExecutor();
        logs = new ByteArrayOutputStream();
        RunnerLogging.configure(false, new PrintStream(logs, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.shutdown(Duration.ofSeconds(1));
        }
        stub.close();
    }

    private static final String RUNNER_SIDE_MCP = "http://runner-side/api/agent/mcp";

    private Runner startRunner(Map<String, String> ownEnv) throws Exception {
        RunnerConfig config = new RunnerConfig(stub.uri(), RUNNER_TOKEN, work, 2, "test", FakeExecutor.CLAUDE, List.of(), 7,
                Duration.ofMillis(100), Duration.ofMillis(50), Duration.ofSeconds(2), false, ownEnv);
        RunnerApi api = new RunnerApi(config.server(), config.token(), "test");
        RunTask.Context ctx = new RunTask.Context(config.workspacesDir(), FakeExecutor.CLAUDE, List.of(), ownEnv,
                new HarnessCache(config.harnessDir(), api), executor, executor,
                new ResultReporter(api, config.pendingResultsDir(), Duration.ofMillis(50)), null, RUNNER_SIDE_MCP);
        runner = new Runner(config, api, ctx, "test", "TestOS");
        runner.start();
        return runner;
    }

    private String claimJson(long runId, String site, String llmApiKey) throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("runId", runId);
        spec.put("runType", "TASK");
        spec.put("workspaceLineage", false);
        spec.put("inheritedWorkspacePath", null);
        spec.put("meeting", false);
        spec.put("repoUrl", "https://github.com/o/r.git");
        spec.put("prompt", "## 작업 이슈\nAGP-70\nrunId=" + runId + "\n");
        spec.put("model", "claude-test");
        spec.put("maxTurns", 80);
        spec.put("allowedTools", "Read,Edit");
        spec.put("timeoutMinutes", 40);
        spec.put("mcpUrl", "http://host/api/agent/mcp");
        spec.put("expertise", null);
        spec.put("futureField", "모르는 필드는 무시");
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("runId", runId);
        claim.put("issueKey", "AGP-70");
        claim.put("projectId", 1);
        claim.put("attempt", 1);
        claim.put("executionSite", site);
        claim.put("spec", spec);
        claim.put("runToken", RUN_TOKEN);
        claim.put("llmApiKey", llmApiKey);
        claim.put("credentialScope", llmApiKey == null ? "LOCAL" : "PLATFORM");
        claim.put("harness", Map.of("sha256", stub.harnessSha, "path", "/api/agent/runners/harness"));
        claim.put("budget", Map.of("perRunUsdCap", 5, "monthlyUsdCap", 100));
        return StubServer.MAPPER.writeValueAsString(claim);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("시간 안에 조건이 채워지지 않았습니다");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void claimExecuteReport_platformKeyOnlyInWorkerEnv_tokensNeverLogged() throws Exception {
        stub.claims.add(claimJson(812, "SERVER", PLATFORM_KEY));
        // 러너 env에 사람의 OAuth 토큰이 있어도 PLATFORM 키가 오면 그 키 하나만 간다
        startRunner(Map.of("CLAUDE_CODE_OAUTH_TOKEN", "user-oauth-SECRET"));

        await(() -> stub.results.containsKey(812L));
        JsonNode result = stub.results.get(812L);
        assertThat(result.get("exitCode").asInt()).isZero();
        assertThat(result.get("timedOut").asBoolean()).isFalse();
        assertThat(result.get("resultText").asText()).isEqualTo("done");
        assertThat(result.get("sessionId").asText()).isEqualTo("s-1");
        assertThat(result.get("costUsd").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(result.get("inputTokens").asLong()).isEqualTo(10);
        assertThat(result.get("model").asText()).isEqualTo("claude-test");
        assertThat(result.get("workspacePath").asText()).endsWith("run-812");
        JsonNode commit = result.get("commits").get(0);
        assertThat(commit.get("issueKey").asText()).isEqualTo("AGP-70");
        assertThat(commit.get("url").asText()).isEqualTo("https://github.com/o/r/commit/abc123");

        FakeExecutor.Call claude = executor.claudeCall();
        assertThat(claude.env()).containsExactly(Map.entry("ANTHROPIC_API_KEY", PLATFORM_KEY));
        assertThat(executor.gitClone().env()).isEmpty();
        assertThat(claude.mcpConfig()).contains("Bearer " + RUN_TOKEN).contains(RUNNER_SIDE_MCP)
                .as("서버가 보낸 spec.mcpUrl이 아니라 러너 --server 기준 주소").doesNotContain("http://host/api/agent/mcp");
        assertThat(claude.harnessPresent()).as("서버 하네스 zip이 워크스페이스에 실체화된다").isTrue();
        assertThat(claude.command()).doesNotContain(RUN_TOKEN, PLATFORM_KEY);

        // TASK 워크스페이스는 남긴다(REVIEW·반려-fix가 이어 쓴다), 토큰이 든 cfg 디렉터리는 지운다
        Path workspace = Path.of(result.get("workspacePath").asText());
        assertThat(workspace).isDirectory();
        assertThat(workspace.resolveSibling("run-812-cfg")).doesNotExist();
        assertThat(Files.list(work.resolve("pending-results")).count()).isZero();

        assertThat(stub.authHeaders).allMatch(h -> h.equals("Bearer " + RUNNER_TOKEN));
        await(() -> stub.heartbeats.size() >= 2);
        assertThat(stub.heartbeats.getFirst().get("maxConcurrency").asInt()).isEqualTo(2);
        assertThat(stub.heartbeats.getFirst().get("os").asText()).isEqualTo("TestOS");

        runner.shutdown(Duration.ofSeconds(1));
        String logText = logs.toString(StandardCharsets.UTF_8);
        assertThat(logText).contains("run=812");
        assertThat(logText).doesNotContain(RUNNER_TOKEN, RUN_TOKEN, PLATFORM_KEY, "user-oauth-SECRET");
    }

    @Test
    void localClaim_injectsNoPlatformKey() throws Exception {
        stub.kind = "LOCAL";
        stub.claims.add(claimJson(900, "LOCAL", null));
        startRunner(Map.of());

        await(() -> stub.results.containsKey(900L));
        assertThat(executor.claudeCall().env()).isEmpty();
        assertThat(runner.kind()).isEqualTo("LOCAL");
    }

    @Test
    void workerEnv_localPassesOnlyThePcOwnersAuthKeys() {
        Map<String, String> own = Map.of("ANTHROPIC_API_KEY", "user-key", "GH_TOKEN", "gh", "PATH", "/bin");
        assertThat(RunTask.workerEnv(null, own)).containsExactly(Map.entry("ANTHROPIC_API_KEY", "user-key"));
        assertThat(RunTask.workerEnv(null, Map.of())).isEmpty();
        assertThat(RunTask.workerEnv("sk-p", Map.of("CLAUDE_CODE_OAUTH_TOKEN", "o", "ANTHROPIC_API_KEY", "user")))
                .containsExactly(Map.entry("ANTHROPIC_API_KEY", "sk-p"));
    }

    @Test
    void stopRunIds_killsWorkerAndReportsCancelled() throws Exception {
        executor.block = true;
        stub.claims.add(claimJson(813, "SERVER", PLATFORM_KEY));
        startRunner(Map.of());

        assertThat(executor.claudeStarted.await(10, TimeUnit.SECONDS)).isTrue();
        await(() -> stub.heartbeats.stream().anyMatch(h -> h.get("runIds").toString().contains("813")));
        stub.stopRunIds.add(813L);

        await(() -> stub.results.containsKey(813L));
        assertThat(executor.kills.get()).isPositive();
        JsonNode result = stub.results.get(813L);
        assertThat(result.get("exitCode").asInt()).isNotZero();
        assertThat(result.get("timedOut").asBoolean()).isFalse();
        assertThat(result.get("rawTail").asText()).contains("러너: 중단됨");
        await(() -> runner.activeRunIds().isEmpty());
    }

    @Test
    void resultReport_retriesTransientFailures() throws Exception {
        stub.resultFailuresLeft.set(2);
        stub.claims.add(claimJson(814, "SERVER", PLATFORM_KEY));
        startRunner(Map.of());

        await(() -> stub.results.containsKey(814L));
        assertThat(stub.resultAttempts.get()).isEqualTo(3);
        await(() -> !Files.exists(work.resolve("pending-results").resolve("814.json")));
    }

    @Test
    void harness_downloadedOnceAndReusedBySha() throws Exception {
        stub.claims.add(claimJson(815, "SERVER", PLATFORM_KEY));
        stub.claims.add(claimJson(816, "SERVER", PLATFORM_KEY));
        startRunner(Map.of());

        await(() -> stub.results.containsKey(815L) && stub.results.containsKey(816L));
        assertThat(stub.harnessDownloads.get()).isEqualTo(1);
        assertThat(work.resolve("harness").resolve(stub.harnessSha).resolve("CLAUDE.md")).isRegularFile();
    }

    @Test
    void pendingResultFromEarlierRun_isResentOnStart() throws Exception {
        Path pending = work.resolve("pending-results");
        Files.createDirectories(pending);
        Files.writeString(pending.resolve("777.json"), "{\"exitCode\":1,\"timedOut\":false,\"commits\":[]}");
        startRunner(Map.of());

        await(() -> stub.results.containsKey(777L));
        assertThat(stub.results.get(777L).get("exitCode").asInt()).isEqualTo(1);
        await(() -> !Files.exists(pending.resolve("777.json")));
    }

    @Test
    void shutdown_killsRunsPastGraceAndReportsThem() throws Exception {
        executor.block = true;
        stub.claims.add(claimJson(817, "SERVER", PLATFORM_KEY));
        startRunner(Map.of());
        assertThat(executor.claudeStarted.await(10, TimeUnit.SECONDS)).isTrue();

        runner.shutdown(Duration.ofMillis(300));

        assertThat(stub.results).containsKey(817L);
        assertThat(stub.results.get(817L).get("rawTail").asText()).contains("러너 종료");
        assertThat(stub.claims).isEmpty();
    }

    @Test
    void firstHeartbeatRejected_isFatal() throws Exception {
        stub.heartbeatStatus = 401;
        RunnerConfig config = new RunnerConfig(stub.uri(), RUNNER_TOKEN, work, 1, "t", FakeExecutor.CLAUDE, List.of(), 7,
                Duration.ofMillis(100), Duration.ofMillis(50), Duration.ofSeconds(1), false, Map.of());
        RunnerApi api = new RunnerApi(config.server(), config.token(), "test");
        Runner r = new Runner(config, api, new RunTask.Context(config.workspacesDir(), "c", List.of(), Map.of(),
                new HarnessCache(config.harnessDir(), api), executor, executor,
                new ResultReporter(api, config.pendingResultsDir(), Duration.ofMillis(50)), null, null), "test", "os");

        org.assertj.core.api.Assertions.assertThatThrownBy(r::start)
                .isInstanceOf(Runner.FatalStartException.class)
                .hasMessageContaining("agr_runn…")
                .hasMessageNotContaining(RUNNER_TOKEN);
        assertThat(stub.claims).isEmpty();
    }
}
