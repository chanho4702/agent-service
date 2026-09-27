package com.platform.agentrunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agentservice.worker.CommandExecutor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * claude 실행 파일 찾기와 시작 점검.
 *
 * <p><b>Windows</b>: {@code ProcessBuilder}는 {@code .exe}만 PATH에서 찾는다. 네이티브 설치(권장)는 {@code %USERPROFILE%\.local\bin\claude.exe}라
 * 그대로 찾는다. npm 전역 설치는 {@code claude.cmd} 셔임인데, {@code .cmd}는 cmd.exe를 거쳐 실행돼 여러 줄 프롬프트·따옴표가 깨진다 —
 * 그래서 셔임 옆 {@code node_modules/@anthropic-ai/claude-code/bin/claude.exe}(패키지에 든 네이티브 바이너리)를 대신 쓰고, 그것도 없으면
 * {@code .cmd}를 쓰지 않고 시작을 거부한다(잘린 프롬프트로 run을 돌리는 것보다 낫다). {@code CLAUDE_BIN}/{@code --claude-bin}을 주면 그 값이
 * 이긴다.
 */
final class ClaudeCli {

    static final List<String> AUTH_ENV_KEYS = List.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY");
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(60);

    private ClaudeCli() {
    }

    /** {@code bin}이 null이면 쓸 수 있는 실행 파일이 없다 — {@code problem}이 사유. */
    record Resolution(String bin, String problem) {
    }

    static Resolution resolve(String configured, Map<String, String> env, boolean windows) {
        if (configured != null && !configured.isBlank()) {
            return new Resolution(configured.trim(), null);
        }
        List<Path> path = pathDirs(env);
        if (windows) {
            for (Path dir : path) {
                Path exe = dir.resolve("claude.exe");
                if (Files.isRegularFile(exe)) {
                    return new Resolution(exe.toString(), null);
                }
            }
            for (Path dir : path) {
                if (Files.isRegularFile(dir.resolve("claude.cmd"))) {
                    Path nativeExe = dir.resolve("node_modules").resolve("@anthropic-ai").resolve("claude-code")
                            .resolve("bin").resolve("claude.exe");
                    if (Files.isRegularFile(nativeExe)) {
                        return new Resolution(nativeExe.toString(), null);
                    }
                    return new Resolution(null, "claude.cmd(npm 셔임)만 찾았습니다(" + dir + ") — cmd.exe를 거치면 여러 줄 프롬프트가 깨집니다. "
                            + "네이티브 설치(claude.exe)를 쓰거나 CLAUDE_BIN에 claude.exe 경로를 지정하세요");
                }
            }
        } else {
            for (Path dir : path) {
                Path bin = dir.resolve("claude");
                if (Files.isRegularFile(bin) && Files.isExecutable(bin)) {
                    return new Resolution(bin.toString(), null);
                }
            }
        }
        return new Resolution(null, "PATH에서 claude를 찾지 못했습니다 — Claude Code를 설치하거나 CLAUDE_BIN에 경로를 지정하세요");
    }

    /** {@code claude --version} — 실패하면 사유 문구, 성공하면 null. */
    static String checkRuns(CommandExecutor executor, String bin, Path cwd) {
        try {
            CommandExecutor.ExecResult r = executor.exec(List.of(bin, "--version"), cwd, Map.of(), CHECK_TIMEOUT);
            if (r.timedOut() || r.exitCode() != 0) {
                return "claude --version 실패(exit " + r.exitCode() + (r.timedOut() ? ", 시간 초과" : "") + ")";
            }
            return null;
        } catch (RuntimeException e) {
            return "claude를 실행하지 못했습니다: " + e.getClass().getSimpleName();
        }
    }

    static String version(CommandExecutor executor, String bin, Path cwd) {
        try {
            CommandExecutor.ExecResult r = executor.exec(List.of(bin, "--version"), cwd, Map.of(), CHECK_TIMEOUT);
            return r.stdout() == null ? "?" : r.stdout().strip();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    static String checkGit(CommandExecutor executor, Path cwd) {
        try {
            CommandExecutor.ExecResult r = executor.exec(List.of("git", "--version"), cwd, Map.of(), CHECK_TIMEOUT);
            return r.timedOut() || r.exitCode() != 0 ? "git --version 실패" : null;
        } catch (RuntimeException e) {
            return "git을 실행하지 못했습니다 — Git을 설치하고 PATH에 두세요";
        }
    }

    /**
     * LOCAL 러너 인증 점검 — 그 PC의 구독 로그인 또는 사용자 env 키가 있어야 워커가 돈다. {@code claude auth status}(JSON
     * {@code loggedIn})를 먼저 보고, 명령이 없거나 해석이 안 되면 env 키·{@code ~/.claude/.credentials.json} 존재로 가늠한다. 경고 문구 또는 null.
     */
    static String checkLocalAuth(CommandExecutor executor, String bin, Path cwd, Map<String, String> ownEnv) {
        boolean envKey = AUTH_ENV_KEYS.stream().anyMatch(k -> ownEnv.get(k) != null && !ownEnv.get(k).isBlank());
        if (envKey) {
            return null;
        }
        try {
            CommandExecutor.ExecResult r = executor.exec(List.of(bin, "auth", "status"), cwd, Map.of(), CHECK_TIMEOUT);
            if (!r.timedOut() && r.stdout() != null && r.stdout().strip().startsWith("{")) {
                JsonNode node = new ObjectMapper().readTree(r.stdout().strip());
                JsonNode loggedIn = node.get("loggedIn");
                if (loggedIn != null && loggedIn.isBoolean()) {
                    return loggedIn.asBoolean() ? null : NOT_LOGGED_IN;
                }
            }
        } catch (Exception ignored) {
            // 아래 추정으로
        }
        for (String home : new String[]{ownEnv.get("USERPROFILE"), ownEnv.get("HOME")}) {
            if (home != null && Files.isRegularFile(Path.of(home, ".claude", ".credentials.json"))) {
                return null;
            }
        }
        return NOT_LOGGED_IN;
    }

    static final String NOT_LOGGED_IN = "claude 로그인이 확인되지 않습니다 — 이 PC에서 `claude`를 실행해 /login 하거나 ANTHROPIC_API_KEY를 설정하세요"
            + "(LOCAL 러너는 이 PC의 인증으로 돈다 — 플랫폼 키를 받지 않는다)";

    private static List<Path> pathDirs(Map<String, String> env) {
        String path = null;
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().toUpperCase(Locale.ROOT).equals("PATH")) {
                path = e.getValue();
                break;
            }
        }
        List<Path> dirs = new ArrayList<>();
        if (path == null) {
            return dirs;
        }
        for (String part : path.split(File.pathSeparator)) {
            String p = part.strip();
            if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
                p = p.substring(1, p.length() - 1);
            }
            if (!p.isEmpty()) {
                try {
                    dirs.add(Path.of(p));
                } catch (RuntimeException ignored) {
                    // 잘못된 PATH 조각
                }
            }
        }
        return dirs;
    }
}
