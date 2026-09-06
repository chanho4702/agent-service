package com.platform.agentservice.worker;

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
import java.util.concurrent.TimeUnit;

/**
 * {@link CommandExecutor}의 {@link ProcessBuilder} 실제 구현(P2a T3). 부모 프로세스 환경을
 * 그대로 상속한 위에 {@code extraEnv}를 덧씌운다(워커 launcher가 CLAUDE_CODE_OAUTH_TOKEN·
 * ANTHROPIC_API_KEY를 명시적으로 전달). stdout/stderr는 각각 별도 스레드로 배수(drain)해야
 * 한다 — 둘 다 파이프 버퍼가 차면 프로세스가 멈추므로, {@code waitFor} 전에 읽기 시작한다.
 * 타임아웃 시 {@code destroyForcibly}로 강제 종료한다.
 *
 * <p>Windows에서 {@code claude}는 흔히 {@code claude.cmd}다 — 이 클래스는 커맨드 문자열을
 * 그대로 넘길 뿐 실행 파일 해석에 관여하지 않는다. 실행 파일 이름은
 * {@code platform.agent.worker.claude-bin} 설정으로 주입한다(하드코딩 금지).
 */
@Component
public class ProcessCommandExecutor implements CommandExecutor {

    private static final Duration DRAIN_JOIN_TIMEOUT = Duration.ofSeconds(5);

    @Override
    public ExecResult exec(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.environment().putAll(extraEnv);

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
