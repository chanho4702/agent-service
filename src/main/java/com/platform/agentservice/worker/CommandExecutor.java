package com.platform.agentservice.worker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 외부 프로세스 실행 추상화(P2a T3) — {@link WorkerLauncher}가 {@code git clone}과
 * {@code claude -p}를 실제 프로세스 없이 단위테스트할 수 있도록 페이크로 교체 가능하게 한다.
 * 실 구현은 {@link ProcessCommandExecutor}, E2E 통합은 후속 태스크(T7)가 다룬다.
 */
public interface CommandExecutor {

    /** {@code timedOut=true}일 때 {@code exitCode}는 의미가 없다(프로세스를 강제 종료했다). */
    record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {
    }

    ExecResult exec(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout);
}
