package com.platform.agentservice.worker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * {@link CommandExecutor}의 {@link ProcessBuilder} 실제 구현(P2a T3). stdout/stderr는 각각 별도
 * 스레드로 배수(drain)해야 한다 — 둘 다 파이프 버퍼가 차면 프로세스가 멈추므로, {@code waitFor}
 * 전에 읽기 시작한다. 타임아웃 시 {@code destroyForcibly}로 강제 종료한다.
 *
 * <p><b>env 커튼(AGP-48)</b>: 자식 프로세스는 부모(이 서비스) env를 상속하지 않는다 —
 * {@code environment().clear()} 후 OS 구동에 필요한 {@link #BASE_ALLOWED_KEYS}와
 * {@code platform.agent.worker.extra-env-keys}만 부모에서 옮기고, 그 위에 호출부가 명시한
 * {@code extraEnv}를 덧씌운다. 워커는 이슈 내용(외부 입력)으로 프롬프트가 구성되는 LLM
 * 프로세스라, 호스트 env의 자격증명(GH 토큰·DB 비밀번호 등)이 거기로 새면 프롬프트 인젝션
 * 한 번에 유출된다. 모든 자식(git clone·워커·커밋 수확 git)이 이 한 지점을 지나므로 여기서
 * 일괄로 친다. 인증 키(CLAUDE_CODE_OAUTH_TOKEN 등)는 워커에만 필요하므로 이 목록에 넣지 않고
 * {@link WorkerLauncher}가 워커 호출에만 extraEnv로 싣는다. 프로세스 격리는 아니다 —
 * 파일시스템·네트워크는 여전히 공유한다(컨테이너 격리는 P2b).
 *
 * <p>Windows에서 {@code claude}는 흔히 {@code claude.cmd}다 — 이 클래스는 커맨드 문자열을
 * 그대로 넘길 뿐 실행 파일 해석에 관여하지 않는다. 실행 파일 이름은
 * {@code platform.agent.worker.claude-bin} 설정으로 주입한다(하드코딩 금지).
 */
@Component
public class ProcessCommandExecutor implements CommandExecutor {

    private static final Duration DRAIN_JOIN_TIMEOUT = Duration.ofSeconds(5);

    /**
     * git·claude CLI가 도는 최소 세트(Windows 키 + POSIX 키)(대문자 정규화, 매칭은 대소문자 무관 — Windows env
     * 키는 대소문자를 가리지 않지만 {@link System#getenv()} 맵 키는 원형 그대로라 {@code Path}·
     * {@code SystemRoot}처럼 섞여 온다). USERPROFILE·HOMEDRIVE/HOMEPATH는 claude가 구독 자격증명
     * ({@code ~/.claude})과 git 전역 설정을 찾는 위치다. 2026-09-26 이 세트로 실측 통과: {@code git clone},
     * {@code claude --version}, 헤드리스 {@code claude -p}(구독 인증), 워커의 {@code Bash(git *)} 도구
     * (claude가 PATH의 git에서 Git Bash를 유도), {@code gradlew --version}.
     * JAVA_HOME은 실측상 없어도 돌았지만 넣는다 — 비밀이 아니고, gradle은 PATH보다 JAVA_HOME을 먼저
     * 보므로 빼면 워커의 {@code Bash(gradlew *)}가 PATH의 다른 JDK로 조용히 바뀐다(이 호스트 PATH 첫
     * java가 C:\java11).
     *
     * <p>POSIX 키(HOME/LANG/LC_* 등)도 포함한다(최종 리뷰 M1) — Linux 워커 호스트에서 HOME이 없으면
     * git이 {@code ~/.gitconfig}(user.name/email·safe.directory·credential helper)를 못 읽어 워커
     * 커밋이 "Please tell me who you are"로 실패하고, LANG/LC_ALL이 없으면 C 로케일이 되어 git이
     * 한글 경로를 이스케이프한다. 전부 경로·로케일 값일 뿐 자격증명이 아니라 커튼 목적(비밀 차단)과
     * 충돌하지 않는다. Windows에서는 이 키들이 없어 no-op이다.
     */
    static final Set<String> BASE_ALLOWED_KEYS = Set.of(
            "PATH", "PATHEXT", "SYSTEMROOT", "SYSTEMDRIVE", "COMSPEC", "WINDIR",
            "TEMP", "TMP", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
            "APPDATA", "LOCALAPPDATA", "PROGRAMDATA",
            "USERNAME", "USERDOMAIN", "COMPUTERNAME",
            "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE",
            "JAVA_HOME",
            "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "USER", "LOGNAME", "SHELL");

    private final Set<String> allowedKeys;
    private final Map<String, String> parentEnv;

    @Autowired
    public ProcessCommandExecutor(WorkerProperties properties) {
        this(properties.extraEnvKeys(), System.getenv());
    }

    /** 테스트 주입점 — 실제 OS env에 기대지 않고 가짜 부모 env로 커튼을 검증한다. */
    ProcessCommandExecutor(List<String> extraEnvKeys, Map<String, String> parentEnv) {
        Set<String> keys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        keys.addAll(BASE_ALLOWED_KEYS);
        if (extraEnvKeys != null) {
            // yml의 ${WORKER_EXTRA_ENV_KEYS:}가 미설정이면 [""]로 바인딩될 수 있다 — 빈 이름을 허용 키로 넣지 않는다.
            extraEnvKeys.stream().filter(k -> k != null && !k.isBlank()).map(String::trim).forEach(keys::add);
        }
        this.allowedKeys = keys;
        this.parentEnv = Map.copyOf(parentEnv);
    }

    @Override
    public ExecResult exec(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        applyCurtain(builder.environment(), extraEnv);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("프로세스 시작 실패: " + command, e);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutDrain = startDrain(process.getInputStream(), stdout);
        Thread stderrDrain = startDrain(process.getErrorStream(), stderr);

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            joinQuietly(stdoutDrain);
            joinQuietly(stderrDrain);
            return new ExecResult(-1, stdout.toString(), stderr.toString(), true);
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(stdoutDrain);
            joinQuietly(stderrDrain);
            return new ExecResult(-1, stdout.toString(), stderr.toString(), true);
        }

        joinQuietly(stdoutDrain);
        joinQuietly(stderrDrain);
        return new ExecResult(process.exitValue(), stdout.toString(), stderr.toString(), false);
    }

    /** 자식 env를 비우고 허용 키만 부모에서 옮긴 뒤 extraEnv를 덧씌운다 — 호출부 명시값이 항상 이긴다. */
    void applyCurtain(Map<String, String> childEnv, Map<String, String> extraEnv) {
        childEnv.clear();
        for (Map.Entry<String, String> entry : parentEnv.entrySet()) {
            if (allowedKeys.contains(entry.getKey())) {
                childEnv.put(entry.getKey(), entry.getValue());
            }
        }
        if (extraEnv != null) {
            childEnv.putAll(extraEnv);
        }
    }

    private Thread startDrain(InputStream in, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 프로세스가 강제 종료되면 스트림이 끊긴다 — 지금까지 모은 출력만으로 충분하다.
            }
        }, "cmd-exec-drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(DRAIN_JOIN_TIMEOUT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
