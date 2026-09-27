package com.platform.agentrunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 설정 파싱·토큰 가림, zip-slip, 워크스페이스 보존, claude 실행 파일 찾기. */
class RunnerUnitsTest {

    @TempDir
    Path dir;

    @Test
    void config_envAndArgs_argsWin_tokenMasked() {
        RunnerConfig c = RunnerConfig.parse(new String[]{"--server", "https://host/", "--concurrency=3"},
                Map.of("RUNNER_SERVER", "http://ignored", "RUNNER_TOKEN", "agr_abcdefghSECRET", "RUNNER_CONCURRENCY", "1",
                        "RUNNER_WORK_DIR", dir.toString()));
        assertThat(c.server().toString()).isEqualTo("https://host");
        assertThat(c.concurrency()).isEqualTo(3);
        assertThat(c.workDir()).isEqualTo(dir);
        assertThat(c.retentionDays()).isEqualTo(7);
        assertThat(c.tokenPrefix()).isEqualTo("agr_abcd…");
        assertThat(c.toString()).doesNotContain("SECRET").contains("agr_abcd…");
        assertThat(c.insecureRemote()).isFalse();
    }

    @Test
    void config_rejectsBadInputWithoutEchoingToken() {
        assertThatThrownBy(() -> RunnerConfig.parse(new String[]{}, Map.of("RUNNER_SERVER", "http://h", "RUNNER_TOKEN", "agp_personaSECRET")))
                .hasMessageNotContaining("SECRET").hasMessageContaining("agr_");
        assertThatThrownBy(() -> RunnerConfig.parse(new String[]{"--concurrency", "9"},
                Map.of("RUNNER_SERVER", "http://h", "RUNNER_TOKEN", "agr_abcdefgh1234")))
                .hasMessageContaining("1~8");
        assertThatThrownBy(() -> RunnerConfig.parse(new String[]{}, Map.of("RUNNER_SERVER", "ftp://h", "RUNNER_TOKEN", "agr_abcdefgh1234")))
                .hasMessageContaining("http");
        assertThatThrownBy(() -> RunnerConfig.parse(new String[]{"--bogus", "1"}, Map.of()))
                .hasMessageContaining("--bogus");
        assertThat(RunnerConfig.parse(new String[]{}, Map.of("RUNNER_SERVER", "http://platform.example.com",
                "RUNNER_TOKEN", "agr_abcdefgh1234")).insecureRemote()).isTrue();
    }

    @Test
    void claimToString_hidesSecrets() {
        Protocol.ClaimResponse claim = new Protocol.ClaimResponse(1, "AGP-1", 1L, 1, "SERVER", null, "agp_runSECRET",
                "sk-SECRET", "PLATFORM", null, null);
        assertThat(claim.toString()).doesNotContain("SECRET").contains("llmApiKey=set");
    }

    @Test
    void harnessExtract_rejectsZipSlipAndWritesNothingOutside() throws Exception {
        Path target = dir.resolve("h");
        Files.createDirectories(target);
        for (String evil : List.of("../evil.txt", "a/../../evil.txt", "/abs/evil.txt", "C:/evil.txt", "..\\evil.txt")) {
            byte[] zip = StubServer.zip(Map.of("ok.txt", "ok", evil, "pwned"));
            assertThatThrownBy(() -> HarnessCache.extract(zip, target)).as(evil).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(dir.resolve("evil.txt")).doesNotExist();
        byte[] good = StubServer.zip(Map.of(".claude/skills/x/SKILL.md", "s", "CLAUDE.md", "c"));
        HarnessCache.extract(good, target);
        assertThat(target.resolve(".claude/skills/x/SKILL.md")).hasContent("s");
    }

    @Test
    void workspaceSweep_keepsRecentAndInUse_dropsExpiredAndStartupCfg() throws Exception {
        Path ws = dir.resolve("workspaces");
        Path old = Files.createDirectories(ws.resolve("run-1"));
        Files.createDirectories(old.resolve(".git/objects"));
        Path readOnly = Files.writeString(old.resolve(".git/objects/pack"), "x");
        readOnly.toFile().setReadOnly();
        Path oldInUse = Files.createDirectories(ws.resolve("run-2"));
        Path fresh = Files.createDirectories(ws.resolve("run-3"));
        Path cfg = Files.createDirectories(ws.resolve("run-3-cfg"));
        Path other = Files.createDirectories(ws.resolve("keep-me"));
        FileTime tenDaysAgo = FileTime.from(Instant.now().minus(Duration.ofDays(10)));
        Files.setLastModifiedTime(old, tenDaysAgo);
        Files.setLastModifiedTime(oldInUse, tenDaysAgo);
        Files.setLastModifiedTime(other, tenDaysAgo);

        int removed = Workspaces.sweep(ws, 7, Set.of(oldInUse.toAbsolutePath().normalize()), true, Instant.now());

        assertThat(removed).isEqualTo(2);
        assertThat(old).doesNotExist();
        assertThat(cfg).doesNotExist();
        assertThat(oldInUse).isDirectory();
        assertThat(fresh).isDirectory();
        assertThat(other).isDirectory();
        assertThat(Workspaces.sweep(ws, 0, Set.of(), false, Instant.now().plus(Duration.ofDays(400)))).isZero();
    }

    @Test
    void claudeResolve_windowsPrefersExe_usesNativeBehindNpmShim_refusesBareCmd() throws Exception {
        Path native1 = Files.createDirectories(dir.resolve("native"));
        Files.writeString(native1.resolve("claude.exe"), "");
        Path npm = Files.createDirectories(dir.resolve("npm"));
        Files.writeString(npm.resolve("claude.cmd"), "");
        Path pkgBin = Files.createDirectories(npm.resolve("node_modules/@anthropic-ai/claude-code/bin"));
        Files.writeString(pkgBin.resolve("claude.exe"), "");
        Path bareCmd = Files.createDirectories(dir.resolve("bare"));
        Files.writeString(bareCmd.resolve("claude.cmd"), "");
        String sep = java.io.File.pathSeparator;

        assertThat(ClaudeCli.resolve(null, Map.of("Path", npm + sep + native1), true).bin())
                .isEqualTo(native1.resolve("claude.exe").toString());
        assertThat(ClaudeCli.resolve(null, Map.of("PATH", npm.toString()), true).bin())
                .isEqualTo(pkgBin.resolve("claude.exe").toString());
        ClaudeCli.Resolution bare = ClaudeCli.resolve(null, Map.of("PATH", bareCmd.toString()), true);
        assertThat(bare.bin()).isNull();
        assertThat(bare.problem()).contains("claude.cmd");
        assertThat(ClaudeCli.resolve("C:\\x\\claude.exe", Map.of(), true).bin()).isEqualTo("C:\\x\\claude.exe");
        assertThat(ClaudeCli.resolve(null, Map.of("PATH", dir.resolve("none").toString()), false).bin()).isNull();
    }

    @Test
    void trackingExecutor_killStopsTheRealProcessOfThatThread() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> longCommand = windows ? List.of("ping", "-n", "30", "127.0.0.1") : List.of("sleep", "30");
        TrackingCommandExecutor executor = new TrackingCommandExecutor(new com.platform.agentservice.worker.WorkerProperties(
                dir.toString(), "", List.of(), "", 0, 0, "", "", Map.of(), List.of(), false));
        java.util.concurrent.atomic.AtomicReference<com.platform.agentservice.worker.CommandExecutor.ExecResult> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread runThread = new Thread(() -> result.set(executor.exec(longCommand, dir, Map.of(), Duration.ofSeconds(60))));
        long start = System.nanoTime();
        runThread.start();
        Thread.sleep(1500);

        executor.kill(runThread);
        runThread.join(10_000);

        assertThat(runThread.isAlive()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
        assertThat(result.get().exitCode()).isNotZero();
    }

    @Test
    void localAuth_envKeyCountsAsLoggedIn_otherwiseAsksCli() {
        FakeExecutor never = new FakeExecutor() {
            @Override
            public ExecResult exec(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout) {
                return new ExecResult(1, "{\"loggedIn\": false}", "", false);
            }
        };
        assertThat(ClaudeCli.checkLocalAuth(never, "claude", dir, Map.of("ANTHROPIC_API_KEY", "k"))).isNull();
        assertThat(ClaudeCli.checkLocalAuth(never, "claude", dir, Map.of("USERPROFILE", dir.toString())))
                .isEqualTo(ClaudeCli.NOT_LOGGED_IN);
    }
}
