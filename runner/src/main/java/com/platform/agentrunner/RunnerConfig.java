package com.platform.agentrunner;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 러너 설정 — 명령행 인자가 env보다 이긴다. 필수는 서버 주소와 러너 토큰(agr_)뿐이다.
 *
 * <p>토큰은 어떤 로그·예외 메시지·{@link #toString()}에도 원문으로 싣지 않는다 — 밖으로 나가는 것은 {@link #tokenPrefix()}(앞 8자)뿐이다.
 *
 * <p>{@code env}는 러너 프로세스 자신의 환경(테스트 주입점). LOCAL 러너는 여기 있는 사용자 인증 키(ANTHROPIC_API_KEY·
 * CLAUDE_CODE_OAUTH_TOKEN)를 워커에 그대로 넘긴다 — 그 PC 사람의 키라서다(플랫폼 키는 claim 응답으로만 온다).
 */
public record RunnerConfig(
        URI server,
        String token,
        Path workDir,
        int concurrency,
        String name,
        String claudeBin,
        List<String> extraEnvKeys,
        int retentionDays,
        Duration heartbeatInterval,
        Duration pollInterval,
        Duration shutdownGrace,
        boolean jsonLogs,
        Map<String, String> env
) {

    public static final String TOKEN_PREFIX = "agr_";
    static final int MAX_CONCURRENCY = 8;

    /** 사용법 — {@code --help}. */
    static final String USAGE = """
            사용법: java -jar agent-runner.jar --server <주소> --token <agr_…> [옵션]

              --server <url>           플랫폼 주소(예: https://host, http://localhost)          env RUNNER_SERVER
              --token <agr_…>          러너 토큰(AI 팀 설정 → 러너에서 발급)                   env RUNNER_TOKEN
                                       명령행 인자는 다른 프로세스에서 보일 수 있어 env를 권장한다.
              --work-dir <dir>         워크스페이스·하네스 캐시 위치(기본 ~/agent-runner)      env RUNNER_WORK_DIR
              --concurrency <1-8>      동시에 돌릴 run 수(기본 1)                              env RUNNER_CONCURRENCY
              --name <이름>            로그에 쓸 러너 이름(기본 호스트 이름)                   env RUNNER_NAME
              --claude-bin <경로>      claude 실행 파일(기본 PATH에서 찾음)                    env CLAUDE_BIN
              --extra-env-keys <a,b>   워커에 추가로 넘길 env 이름(프록시·사내 CA 등)          env RUNNER_EXTRA_ENV_KEYS
              --retention-days <n>     오래된 워크스페이스 정리 기준 일수(기본 7, 0=정리 안 함) env RUNNER_RETENTION_DAYS
              --shutdown-grace <초>    종료 신호 뒤 진행 중 run을 기다리는 시간(기본 300)      env RUNNER_SHUTDOWN_GRACE
              --log-format text|json   로그 형식(기본 text, 컨테이너는 json)                   env RUNNER_LOG_FORMAT
              --version                버전 출력
              --help                   이 도움말
            """;

    public RunnerConfig {
        extraEnvKeys = extraEnvKeys == null ? List.of() : List.copyOf(extraEnvKeys);
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    /** 명령행 인자와 env로 설정을 만든다. 잘못된 값은 {@link IllegalArgumentException}(메시지에 토큰을 싣지 않는다). */
    public static RunnerConfig parse(String[] args, Map<String, String> env) {
        Map<String, String> opts = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("알 수 없는 인자: " + arg);
            }
            String key = arg.substring(2);
            String value;
            int eq = key.indexOf('=');
            if (eq >= 0) {
                value = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--" + key + " 값이 없습니다");
                }
                value = args[++i];
            }
            opts.put(key, value);
        }
        for (String key : opts.keySet()) {
            if (!List.of("server", "token", "work-dir", "concurrency", "name", "claude-bin", "extra-env-keys",
                    "retention-days", "shutdown-grace", "log-format").contains(key)) {
                throw new IllegalArgumentException("알 수 없는 옵션: --" + key);
            }
        }

        String server = pick(opts, "server", env, "RUNNER_SERVER");
        if (server == null) {
            throw new IllegalArgumentException("--server(또는 RUNNER_SERVER)가 필요합니다");
        }
        URI serverUri = parseServer(server);

        String token = pick(opts, "token", env, "RUNNER_TOKEN");
        if (token == null) {
            throw new IllegalArgumentException("--token(또는 RUNNER_TOKEN)이 필요합니다");
        }
        if (!token.startsWith(TOKEN_PREFIX) || token.length() < TOKEN_PREFIX.length() + 8 || !token.chars().allMatch(c -> c > 32 && c < 127)) {
            throw new IllegalArgumentException("러너 토큰 형식이 아닙니다(agr_로 시작해야 합니다) — AI 팀 설정에서 발급한 러너 토큰을 쓰세요");
        }

        String workDir = pick(opts, "work-dir", env, "RUNNER_WORK_DIR");
        Path workPath = workDir != null ? Path.of(workDir) : defaultWorkDir(env);

        int concurrency = intOption(pick(opts, "concurrency", env, "RUNNER_CONCURRENCY"), 1, "concurrency");
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("concurrency는 1~" + MAX_CONCURRENCY + "여야 합니다");
        }
        int retention = intOption(pick(opts, "retention-days", env, "RUNNER_RETENTION_DAYS"), 7, "retention-days");
        if (retention < 0) {
            throw new IllegalArgumentException("retention-days는 0 이상이어야 합니다");
        }
        int grace = intOption(pick(opts, "shutdown-grace", env, "RUNNER_SHUTDOWN_GRACE"), 300, "shutdown-grace");
        if (grace < 0) {
            throw new IllegalArgumentException("shutdown-grace는 0 이상이어야 합니다");
        }
        String logFormat = pick(opts, "log-format", env, "RUNNER_LOG_FORMAT");
        boolean json;
        if (logFormat == null || logFormat.equalsIgnoreCase("text")) {
            json = false;
        } else if (logFormat.equalsIgnoreCase("json")) {
            json = true;
        } else {
            throw new IllegalArgumentException("log-format은 text 또는 json이어야 합니다");
        }

        String name = pick(opts, "name", env, "RUNNER_NAME");
        if (name == null) {
            name = hostName(env);
        }

        return new RunnerConfig(serverUri, token, workPath, concurrency, name,
                pick(opts, "claude-bin", env, "CLAUDE_BIN"),
                splitList(pick(opts, "extra-env-keys", env, "RUNNER_EXTRA_ENV_KEYS")),
                retention, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(grace), json, env);
    }

    /** 로그·화면용 — {@code agr_1234…}. */
    public String tokenPrefix() {
        return tokenPrefixOf(token);
    }

    static String tokenPrefixOf(String token) {
        if (token == null) {
            return "(없음)";
        }
        return token.substring(0, Math.min(8, token.length())) + "…";
    }

    public Path workspacesDir() {
        return workDir.resolve("workspaces");
    }

    public Path harnessDir() {
        return workDir.resolve("harness");
    }

    public Path pendingResultsDir() {
        return workDir.resolve("pending-results");
    }

    /** 평문 http로 원격 호스트에 붙으면 러너·run 토큰이 평문으로 오간다 — 시작 로그에 경고한다. */
    public boolean insecureRemote() {
        String host = server.getHost() == null ? "" : server.getHost().toLowerCase(Locale.ROOT);
        return "http".equalsIgnoreCase(server.getScheme())
                && !(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]")
                || host.endsWith(".localhost") || host.equals("host.docker.internal") || !host.contains("."));
    }

    RunnerConfig withTimings(Duration heartbeat, Duration poll, Duration grace) {
        return new RunnerConfig(server, token, workDir, concurrency, name, claudeBin, extraEnvKeys, retentionDays,
                heartbeat, poll, grace, jsonLogs, env);
    }

    @Override
    public String toString() {
        return "RunnerConfig{server=" + server + ", token=" + tokenPrefix() + ", workDir=" + workDir + ", concurrency="
                + concurrency + ", name=" + name + ", claudeBin=" + claudeBin + ", extraEnvKeys=" + extraEnvKeys
                + ", retentionDays=" + retentionDays + ", shutdownGrace=" + shutdownGrace.toSeconds() + "s}";
    }

    private static String pick(Map<String, String> opts, String opt, Map<String, String> env, String envKey) {
        String v = opts.get(opt);
        if (v == null || v.isBlank()) {
            v = env.get(envKey);
        }
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static int intOption(String value, int def, String name) {
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + "는 정수여야 합니다: " + value);
        }
    }

    static URI parseServer(String server) {
        String s = server.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("서버 주소 형식이 아닙니다: " + server);
        }
        if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("서버 주소는 http(s)://호스트 형태여야 합니다: " + server);
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("서버 주소에 쿼리·프래그먼트·사용자 정보를 넣지 마세요: " + server);
        }
        return uri;
    }

    private static Path defaultWorkDir(Map<String, String> env) {
        String home = env.get("USERPROFILE");
        if (home == null || home.isBlank()) {
            home = env.get("HOME");
        }
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home");
        }
        return Path.of(home, "agent-runner");
    }

    private static String hostName(Map<String, String> env) {
        String n = env.get("COMPUTERNAME");
        if (n == null || n.isBlank()) {
            n = env.get("HOSTNAME");
        }
        if (n == null || n.isBlank()) {
            try {
                n = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                n = "runner";
            }
        }
        return n;
    }

    private static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }
}
