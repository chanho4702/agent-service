package com.platform.agentrunner;

import com.platform.agentservice.worker.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code java -jar agent-runner.jar --server <주소> --token agr_…} — 사용자 PC(LOCAL 러너, 그 PC의 Claude 로그인으로 돈다) 또는 플랫폼 러너
 * 컨테이너(PLATFORM, claim마다 서버가 LLM 키를 준다)에서 도는 러너. 프로토콜·동작은 CLAUDE.md §13.
 *
 * <p>종료 코드: 0 정상 종료, 2 설정 오류·시작 거부(토큰 거부·claude/git 없음).
 */
public final class RunnerMain {

    private static final Logger log = LoggerFactory.getLogger(RunnerMain.class);

    private RunnerMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv()));
    }

    static int run(String[] args, Map<String, String> env) {
        List<String> argList = List.of(args);
        if (argList.contains("--help") || argList.contains("-h")) {
            System.out.println(RunnerConfig.USAGE);
            return 0;
        }
        if (argList.contains("--version")) {
            System.out.println("agent-runner " + version());
            return 0;
        }
        RunnerConfig config;
        try {
            config = RunnerConfig.parse(args, env);
        } catch (IllegalArgumentException e) {
            System.err.println("설정 오류: " + e.getMessage());
            System.err.println();
            System.err.println(RunnerConfig.USAGE);
            return 2;
        }
        RunnerLogging.configure(config.jsonLogs(), System.out);

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        log.info("agent-runner {} — 서버 {} · 토큰 {} · 작업 폴더 {} · 동시 {} · 이름 {}", version(), config.server(),
                config.tokenPrefix(), config.workDir().toAbsolutePath(), config.concurrency(), config.name());
        if (config.insecureRemote()) {
            log.warn("평문 http로 원격 서버에 붙습니다 — 러너·run 토큰이 암호화되지 않은 채 오갑니다. https 주소를 쓰세요");
        }
        if (argList.stream().anyMatch(a -> a.equals("--token") || a.startsWith("--token="))) {
            log.warn("토큰을 명령행 인자로 넘겼습니다 — 같은 PC의 다른 프로세스 목록에 보일 수 있습니다. RUNNER_TOKEN env를 권장합니다");
        }

        try {
            Files.createDirectories(config.workspacesDir());
            Files.createDirectories(config.harnessDir());
            Files.createDirectories(config.pendingResultsDir());
        } catch (IOException e) {
            log.error("작업 폴더를 만들 수 없습니다: {} — {}", config.workDir(), e.getMessage());
            return 2;
        }

        // 서버와 같은 env 커튼 — 부모 env는 OS 구동 최소 세트 + extra-env-keys만 자식(git·claude)에 간다
        TrackingCommandExecutor executor = new TrackingCommandExecutor(new WorkerProperties(config.workspacesDir().toString(),
                "", List.of(), "", 0, 0, "", "", Map.of(), config.extraEnvKeys(), false));

        ClaudeCli.Resolution claude = ClaudeCli.resolve(config.claudeBin(), env, windows);
        if (claude.bin() == null) {
            log.error("{}", claude.problem());
            return 2;
        }
        String problem = ClaudeCli.checkRuns(executor, claude.bin(), config.workDir());
        if (problem != null) {
            log.error("{} — claude 경로: {} (CLAUDE_BIN으로 지정할 수 있습니다)", problem, claude.bin());
            return 2;
        }
        String gitProblem = ClaudeCli.checkGit(executor, config.workDir());
        if (gitProblem != null) {
            log.error("{}", gitProblem);
            return 2;
        }
        log.info("claude {} ({})", ClaudeCli.version(executor, claude.bin(), config.workDir()), claude.bin());

        RunnerApi api = new RunnerApi(config.server(), config.token(), version());
        HarnessCache harness = new HarnessCache(config.harnessDir(), api);
        ResultReporter reporter = new ResultReporter(api, config.pendingResultsDir(), Duration.ofSeconds(2));
        String mcpUrl = config.server().toString().replaceAll("/+$", "") + "/api/agent/mcp";
        log.info("워커 MCP 주소 {}", mcpUrl);
        RunTask.Context context = new RunTask.Context(config.workspacesDir(), claude.bin(), config.extraEnvKeys(), env, harness,
                executor, executor, reporter, null, mcpUrl);
        Runner runner = new Runner(config, api, context, version(), osDescription());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> runner.shutdown(config.shutdownGrace()), "runner-shutdown"));
        try {
            runner.start();
        } catch (Runner.FatalStartException e) {
            log.error("{}", e.getMessage());
            runner.shutdown(Duration.ZERO);
            return 2;
        }
        if ("LOCAL".equals(runner.kind())) {
            String authWarning = ClaudeCli.checkLocalAuth(executor, claude.bin(), config.workDir(), env);
            if (authWarning != null) {
                log.warn("{}", authWarning);
            }
        }
        log.info("run을 기다립니다 — Ctrl+C로 종료(진행 중 run은 최대 {}초 기다림)", config.shutdownGrace().toSeconds());
        if (windows) {
            log.info("Windows에서는 Ctrl+C가 같은 콘솔의 claude 프로세스에도 전달돼 진행 중 run이 곧바로 끝날 수 있습니다(중단으로 보고됨)");
        }
        try {
            runner.awaitTermination();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    static String version() {
        String v = RunnerMain.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    static String osDescription() {
        String os = System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "") + " ("
                + System.getProperty("os.arch", "?") + ")";
        return os.length() > 80 ? os.substring(0, 80) : os;
    }
}
