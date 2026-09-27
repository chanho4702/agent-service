package com.platform.agentservice.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProcessCommandExecutor} 테스트. 커튼(AGP-48) 규칙 자체는 가짜 부모 env를 주입해 OS와 무관하게
 * 검증하고, 실 프로세스 경로는 Windows에서만 돈다.
 *
 * <p>실프로세스 테스트는 워커 호스트=Windows 전용이라 CI(Linux)에서는 스킵한다 — {@code cmd}/
 * {@code powershell} 커맨드를 그대로 실행해 이식성이 없고, 가짜로 우회하면 실제 ProcessBuilder 동작을
 * 못 본다(컨트롤러 판정, CI 레드 마이크로 픽스).
 */
class ProcessCommandExecutorTest {

    private static Map<String, String> fakeParentEnv() {
        Map<String, String> env = new HashMap<>();
        // System.getenv() 맵 키는 원형 그대로(대소문자 섞임) 온다 — 매칭이 대소문자 무관인지 이 모양으로 확인한다.
        env.put("Path", "C:\\Windows\\system32");
        env.put("SystemRoot", "C:\\Windows");
        env.put("USERPROFILE", "C:\\Users\\agent");
        env.put("GH_TOKEN", "ghp_host-secret");
        env.put("DB_PASSWORD", "host-db-secret");
        env.put("CLAUDE_CODE_OAUTH_TOKEN", "oauth-from-parent");
        env.put("HTTP_PROXY", "http://proxy.corp:3128");
        return env;
    }

    @Test
    void curtain_drops_unlisted_parent_vars_and_keeps_base_keys_case_insensitively() {
        ProcessCommandExecutor executor = new ProcessCommandExecutor(List.of(), fakeParentEnv());
        Map<String, String> child = new HashMap<>(Map.of("STALE_INHERITED", "x"));

        executor.applyCurtain(child, Map.of());

        assertThat(child).containsOnly(
                Map.entry("Path", "C:\\Windows\\system32"),
                Map.entry("SystemRoot", "C:\\Windows"),
                Map.entry("USERPROFILE", "C:\\Users\\agent"));
    }

    @Test
    void curtain_does_not_pass_auth_keys_by_itself_they_only_arrive_through_extra_env() {
        ProcessCommandExecutor executor = new ProcessCommandExecutor(List.of(), fakeParentEnv());
        Map<String, String> clone = new HashMap<>();
        Map<String, String> worker = new HashMap<>();

        executor.applyCurtain(clone, Map.of());
        executor.applyCurtain(worker, Map.of("CLAUDE_CODE_OAUTH_TOKEN", "oauth-explicit"));

        assertThat(clone).doesNotContainKey("CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(worker).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "oauth-explicit");
    }

    @Test
    void extra_env_keys_escape_hatch_passes_named_keys_case_insensitively() {
        ProcessCommandExecutor executor = new ProcessCommandExecutor(List.of(" http_proxy ", ""), fakeParentEnv());
        Map<String, String> child = new HashMap<>();

        executor.applyCurtain(child, Map.of());

        assertThat(child).containsEntry("HTTP_PROXY", "http://proxy.corp:3128");
        assertThat(child).doesNotContainKeys("GH_TOKEN", "DB_PASSWORD");
    }

    @Test
    void extra_env_overrides_curtain_values() {
        ProcessCommandExecutor executor = new ProcessCommandExecutor(List.of(), fakeParentEnv());
        Map<String, String> child = new HashMap<>();

        executor.applyCurtain(child, Map.of("USERPROFILE", "C:\\override", "AGENT_EXTRA", "v"));

        assertThat(child).containsEntry("USERPROFILE", "C:\\override").containsEntry("AGENT_EXTRA", "v");
    }

    @Test
    void worker_properties_default_extra_env_keys_to_empty_when_unbound() {
        WorkerProperties props = new WorkerProperties("w", "b", List.of(), "claude", 1, 1, "Read", "u", Map.of(), null, false);

        assertThat(props.extraEnvKeys()).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void real_child_process_sees_only_curtained_env(@TempDir Path cwd) {
        // 탐침을 주입 맵에만 심으면 clear()가 빠져도 통과한다(ProcessBuilder가 미리 채우는 건 실제 OS env라
        // 탐침이 애초에 없다). 그래서 자식이 본 키 전체가 허용 집합 안인지 본다 — 화이트리스트 밖의 실제 OS
        // 변수(PROCESSOR_IDENTIFIER·OS 등)가 하나라도 새면 걸린다.
        Map<String, String> parent = new HashMap<>(System.getenv());
        parent.put("AGENT_CURTAIN_HATCH_PROBE", "hatch-value");
        ProcessCommandExecutor executor = new ProcessCommandExecutor(List.of("agent_curtain_hatch_probe"), parent);

        CommandExecutor.ExecResult result = executor.exec(List.of("cmd", "/c", "set"), cwd,
                Map.of("AGENT_TEST_ENV_VAR", "worker-value"), Duration.ofSeconds(10));

        assertThat(result.exitCode()).isZero();
        Map<String, String> childEnv = result.stdout().lines()
                .filter(line -> line.indexOf('=') > 0)
                .collect(Collectors.toMap(line -> line.substring(0, line.indexOf('=')).toUpperCase(Locale.ROOT),
                        line -> line.substring(line.indexOf('=') + 1), (a, b) -> a));
        // cmd.exe는 PROMPT가 없으면 스스로 기본값 $P$G를 넣는다 — 그 값일 때만 커튼 밖 키로 치지 않는다
        // (부모에서 샌 PROMPT라면 호스트 값이 보여 여기서 걸린다).
        if ("$P$G".equals(childEnv.get("PROMPT"))) {
            childEnv.remove("PROMPT");
        }
        Set<String> permitted = new HashSet<>(ProcessCommandExecutor.BASE_ALLOWED_KEYS);
        permitted.add("AGENT_CURTAIN_HATCH_PROBE");
        permitted.add("AGENT_TEST_ENV_VAR");

        assertThat(permitted).containsAll(childEnv.keySet());
        assertThat(childEnv).containsKey("PATH")
                .containsEntry("AGENT_CURTAIN_HATCH_PROBE", "hatch-value")
                .containsEntry("AGENT_TEST_ENV_VAR", "worker-value");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void runs_command_and_captures_stdout(@TempDir Path cwd) {
        ProcessCommandExecutor executor = new ProcessCommandExecutor(List.of(), System.getenv());
        CommandExecutor.ExecResult result = executor.exec(
                List.of("cmd", "/c", "echo hello-world"), cwd, Map.of(), Duration.ofSeconds(10));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello-world");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void kills_process_and_reports_timeout_when_exceeding_duration(@TempDir Path cwd) {
        ProcessCommandExecutor executor = new ProcessCommandExecutor(List.of(), System.getenv());
        // 강제 타임아웃보다 길게 재워 destroyForcibly 경로를 탄다.
        CommandExecutor.ExecResult result = executor.exec(
                List.of("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 30"),
                cwd, Map.of(), Duration.ofSeconds(2));

        assertThat(result.timedOut()).isTrue();
    }

    /**
     * 커튼 뒤에서 실제 {@code git clone}(공개 소형 리포)이 도는지 보는 실측 스모크. 네트워크에 기대므로 기본
     * 스위트에서 빼고 {@code AGENT_CURTAIN_SMOKE=true}일 때만 돈다(워커 호스트 설정을 바꿨을 때 수동 확인용).
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @EnabledIfEnvironmentVariable(named = "AGENT_CURTAIN_SMOKE", matches = "true")
    void smoke_git_clone_and_claude_version_work_behind_curtain(@TempDir Path cwd) {
        ProcessCommandExecutor executor = new ProcessCommandExecutor(List.of(), System.getenv());

        CommandExecutor.ExecResult clone = executor.exec(
                List.of("git", "clone", "--depth", "1", "https://github.com/octocat/Hello-World.git", "."),
                cwd, Map.of(), Duration.ofMinutes(2));
        assertThat(clone.exitCode()).as(clone.stderr()).isZero();
        assertThat(Files.exists(cwd.resolve("README"))).isTrue();

        CommandExecutor.ExecResult version = executor.exec(
                List.of("claude", "--version"), cwd, Map.of(), Duration.ofSeconds(30));
        assertThat(version.exitCode()).as(version.stderr()).isZero();
        assertThat(version.stdout()).containsIgnoringCase("claude");
    }

    @Test
    void windows_arg_escaping_escapes_quotes_and_the_backslashes_right_before_them_only() {
        assertThat(ProcessCommandExecutor.escapeWindowsArg("no quotes C:\\path\\")).isEqualTo("no quotes C:\\path\\");
        assertThat(ProcessCommandExecutor.escapeWindowsArg("title=\"[회의] x\"")).isEqualTo("title=\\\"[회의] x\\\"");
        assertThat(ProcessCommandExecutor.escapeWindowsArg("a\\\"b")).isEqualTo("a\\\\\\\"b");
        assertThat(ProcessCommandExecutor.escapeWindowsArg("C:\\dir\\ \"q\"")).isEqualTo("C:\\dir\\ \\\"q\\\"");
    }

    /**
     * run 18(로컬 러너 E2E) 회귀 — 따옴표·줄바꿈·공백·역슬래시가 섞인 프롬프트가 자식 프로세스에 한 인자로 온전히 닿는다. 자식은 Java
     * 단일 파일 실행(인자 파싱이 claude.exe·git.exe와 같은 MSVCRT 규칙)으로 받은 인자를 UTF-8 base64로 돌려준다.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windows_child_receives_prompt_with_quotes_as_one_intact_argument(@TempDir Path dir) throws Exception {
        Path echo = dir.resolve("EchoArgs.java");
        Files.writeString(echo, "public class EchoArgs { public static void main(String[] a) { System.out.println(a.length); "
                + "for (String s : a) System.out.println(java.util.Base64.getEncoder().encodeToString("
                + "s.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } }");
        String prompt = "## 회의\n- create_page(spaceId=5, title=\"[착수/계획 회의] <날짜>\", contentMarkdown=...)\n"
                + "경로 C:\\agent-work\\run-1\\ 와 \\\"이중\\\" 그리고 끝\\";
        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        ProcessCommandExecutor executor = new ProcessCommandExecutor(List.of(), System.getenv());

        CommandExecutor.ExecResult r = executor.exec(List.of(javaExe, echo.toString(), prompt, "--next", "x y"), dir, Map.of(),
                Duration.ofSeconds(60));

        assertThat(r.exitCode()).as(r.stderr()).isZero();
        List<String> lines = r.stdout().lines().toList();
        assertThat(lines.get(0)).isEqualTo("3");
        assertThat(new String(java.util.Base64.getDecoder().decode(lines.get(1)), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(prompt);
        assertThat(new String(java.util.Base64.getDecoder().decode(lines.get(3)), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("x y");
    }
}
