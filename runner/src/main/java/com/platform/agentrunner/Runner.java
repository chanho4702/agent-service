package com.platform.agentrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 러너 루프 — heartbeat(30초, 서버의 {@code stopRunIds}로 워커 중단) + claim(동시 한도 아래일 때 약 10초 간격, 오류면 지터 백오프) +
 * run 실행({@link RunTask}) + 결과 보고. 종료 신호가 오면 claim을 멈추고 진행 중 run을 유예 시간까지 기다린 뒤 남은 것은 죽이고 "중단됨"으로
 * 보고한다(heartbeat는 끝까지 보낸다 — 끊기면 서버가 그 run들을 "러너 연결 끊김"으로 먼저 막는다).
 */
class Runner {

    private static final Logger log = LoggerFactory.getLogger(Runner.class);
    static final Duration MAX_CLAIM_BACKOFF = Duration.ofMinutes(5);
    static final Duration SWEEP_INTERVAL = Duration.ofHours(6);
    /** 유예가 끝나 남은 run을 죽인 뒤 그 결과 보고에 주는 시간. */
    static final Duration REPORT_AFTER_KILL = Duration.ofSeconds(60);

    /** 첫 heartbeat가 거부됨(토큰 철회·오타) — 시작을 멈춘다. */
    static final class FatalStartException extends Exception {
        FatalStartException(String message) {
            super(message);
        }
    }

    private final RunnerConfig config;
    private final RunnerApi api;
    private final RunTask.Context baseContext;
    private final String version;
    private final String os;

    private final Map<Long, RunTask> tasks = new ConcurrentHashMap<>();
    private final Object wake = new Object();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final AtomicInteger claimErrors = new AtomicInteger();
    private volatile boolean stopping;
    private volatile Instant reportGiveUpAt = Instant.MAX;
    private volatile String kind;
    private volatile boolean heartbeatFailing;

    private ExecutorService workers;
    private ScheduledExecutorService scheduler;
    private Thread claimThread;

    Runner(RunnerConfig config, RunnerApi api, RunTask.Context contextWithoutGiveUp, String version, String os) {
        this.config = config;
        this.api = api;
        this.version = version;
        this.os = os;
        this.baseContext = new RunTask.Context(contextWithoutGiveUp.workspacesDir(), contextWithoutGiveUp.claudeBin(),
                contextWithoutGiveUp.extraEnvKeys(), contextWithoutGiveUp.ownEnv(), contextWithoutGiveUp.harness(),
                contextWithoutGiveUp.executor(), contextWithoutGiveUp.killer(), contextWithoutGiveUp.reporter(),
                () -> reportGiveUpAt, contextWithoutGiveUp.mcpUrl());
    }

    String kind() {
        return kind;
    }

    Set<Long> activeRunIds() {
        return Set.copyOf(tasks.keySet());
    }

    /**
     * 첫 heartbeat(토큰 확인) → 남은 결과 파일 재전송 → 워크스페이스 정리 → heartbeat 주기 → claim 루프.
     *
     * @throws FatalStartException 서버가 러너 토큰을 거부(401·403)
     */
    void start() throws FatalStartException {
        Protocol.HeartbeatResponse first = firstHeartbeat();
        kind = first.kind();
        markAlive();
        log.info("서버 연결 — runnerId={} kind={} (offline 판정 {}초)", first.runnerId(), first.kind(), first.offlineAfterSeconds());

        workers = Executors.newFixedThreadPool(config.concurrency(), named("run-worker"));
        scheduler = Executors.newScheduledThreadPool(2, named("runner-bg"));

        Workspaces.sweep(config.workspacesDir(), config.retentionDays(), Set.of(), true, Instant.now());
        resendPending();

        long hb = config.heartbeatInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::heartbeatSafely, hb, hb, TimeUnit.MILLISECONDS);
        long sweep = SWEEP_INTERVAL.toMillis();
        scheduler.scheduleWithFixedDelay(this::sweepSafely, sweep, sweep, TimeUnit.MILLISECONDS);

        claimThread = new Thread(this::claimLoop, "runner-claim");
        claimThread.start();
    }

    void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    private Protocol.HeartbeatResponse firstHeartbeat() throws FatalStartException {
        Duration backoff = Duration.ofSeconds(2);
        while (true) {
            try {
                return api.heartbeat(heartbeatRequest());
            } catch (RunnerApi.HttpStatusException e) {
                if (e.status == 401 || e.status == 403) {
                    throw new FatalStartException("서버가 러너 토큰(" + config.tokenPrefix() + ")을 거부했습니다(HTTP " + e.status
                            + ") — 철회됐거나 잘못 복사된 토큰입니다. AI 팀 설정에서 러너를 다시 발급하세요"
                            + (e.status == 401 && e.error == null ? " (게이트웨이가 러너 경로를 JWT로 막고 있을 수도 있습니다)" : ""));
                }
                log.warn("서버 연결 실패(HTTP {}{}) — {}초 뒤 재시도", e.status, e.error == null ? "" : ": " + e.error, backoff.toSeconds());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FatalStartException("시작 중 인터럽트");
            } catch (Exception e) {
                log.warn("서버 연결 실패({}: {}) — {}초 뒤 재시도", e.getClass().getSimpleName(), e.getMessage(), backoff.toSeconds());
            }
            if (!sleep(ResultReporter.jitter(backoff)) && stopping) {
                throw new FatalStartException("시작 중 종료");
            }
            backoff = min(backoff.multipliedBy(2), Duration.ofSeconds(60));
        }
    }

    private void resendPending() {
        List<Long> pending = baseContext.reporter().pendingRunIds();
        if (pending.isEmpty()) {
            return;
        }
        log.info("지난 실행에서 보내지 못한 결과 {}건을 다시 보냅니다", pending.size());
        // 별도 스레드 — 서버가 아직 안 받으면 백오프로 오래 걸릴 수 있어 heartbeat·run 스레드를 잡지 않는다
        Thread resend = new Thread(() -> {
            for (Long runId : pending) {
                if (!baseContext.reporter().resend(runId, () -> reportGiveUpAt)) {
                    break;
                }
            }
        }, "runner-resend");
        resend.setDaemon(true);
        resend.start();
    }

    private void claimLoop() {
        while (!stopping) {
            if (tasks.size() >= config.concurrency()) {
                sleep(Duration.ofMillis(Math.min(1000, config.pollInterval().toMillis())));
                continue;
            }
            Duration wait;
            try {
                Optional<Protocol.ClaimResponse> claim = api.claim();
                if (claimErrors.getAndSet(0) > 0) {
                    log.info("claim 복구");
                }
                if (claim.isPresent()) {
                    submit(claim.get());
                    continue; // 한도가 남았으면 곧바로 다음 claim
                }
                wait = config.pollInterval();
            } catch (InterruptedException e) {
                if (stopping) {
                    break;
                }
                Thread.interrupted();
                continue;
            } catch (RunnerApi.HttpStatusException e) {
                wait = claimBackoff("HTTP " + e.status + (e.error == null ? "" : ": " + e.error));
            } catch (Exception e) {
                wait = claimBackoff(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
            sleep(wait);
        }
    }

    private Duration claimBackoff(String reason) {
        int n = claimErrors.incrementAndGet();
        Duration base = config.pollInterval().multipliedBy(1L << Math.min(n - 1, 10));
        Duration wait = ResultReporter.jitter(min(base, MAX_CLAIM_BACKOFF));
        if (n == 1 || n % 10 == 0) {
            log.warn("claim 실패({}회) — {} · {}초 뒤 재시도", n, reason, wait.toSeconds());
        }
        return wait;
    }

    private void submit(Protocol.ClaimResponse claim) {
        long runId = claim.runId();
        RunTask task = new RunTask(claim, baseContext, () -> tasks.remove(runId));
        tasks.put(runId, task);
        try {
            workers.submit(task);
        } catch (RuntimeException e) {
            // 풀이 닫힌 뒤(종료 경합) — 이 스레드에서 돌려 결과를 반드시 보고한다
            task.run();
        }
    }

    private Protocol.HeartbeatRequest heartbeatRequest() {
        return new Protocol.HeartbeatRequest(version, os, config.concurrency(), new ArrayList<>(tasks.keySet()));
    }

    void heartbeatOnce() throws Exception {
        Protocol.HeartbeatResponse res = api.heartbeat(heartbeatRequest());
        kind = res.kind();
        markAlive();
        if (heartbeatFailing) {
            heartbeatFailing = false;
            log.info("heartbeat 복구");
        }
        for (Long stop : res.stopRunIdsOrEmpty()) {
            RunTask task = tasks.get(stop);
            if (task != null) {
                task.cancel("서버가 중단을 요청했습니다(취소·차단 또는 재배정)");
            }
        }
    }

    /**
     * 마지막 heartbeat 성공 시각을 {@code <work>/runner.alive}에 적는다 — 러너 컨테이너 HEALTHCHECK가 이 파일의 나이를 본다(러너는 포트를 열지
     * 않는다). 내용은 시각뿐이다.
     */
    private void markAlive() {
        try {
            java.nio.file.Files.writeString(config.workDir().resolve(ALIVE_FILE), Instant.now().toString());
        } catch (java.io.IOException | RuntimeException e) {
            log.debug("alive 파일 쓰기 실패: {}", e.getMessage());
        }
    }

    static final String ALIVE_FILE = "runner.alive";

    private void heartbeatSafely() {
        try {
            heartbeatOnce();
        } catch (RunnerApi.HttpStatusException e) {
            logHeartbeatFailure("HTTP " + e.status + (e.error == null ? "" : ": " + e.error));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logHeartbeatFailure(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    private void logHeartbeatFailure(String reason) {
        if (!heartbeatFailing) {
            heartbeatFailing = true;
            log.warn("heartbeat 실패 — {} (계속 실패하면 서버가 진행 중 run을 '러너 연결 끊김'으로 막습니다)", reason);
        }
    }

    private void sweepSafely() {
        try {
            Set<Path> inUse = new HashSet<>();
            for (RunTask t : tasks.values()) {
                Path p = t.inheritedWorkspace();
                if (p != null) {
                    inUse.add(p);
                }
            }
            Workspaces.sweep(config.workspacesDir(), config.retentionDays(), inUse, false, Instant.now());
        } catch (RuntimeException e) {
            log.warn("워크스페이스 정리 실패: {}", e.getMessage());
        }
    }

    /** 종료 — claim 중지 → 진행 중 run을 {@code grace}까지 기다림 → 남은 run 중단·보고 → 정리. 여러 번 불러도 한 번만 돈다. */
    synchronized void shutdown(Duration grace) {
        if (terminated.getCount() == 0) {
            return;
        }
        stopping = true;
        wakeUp();
        if (claimThread != null) {
            claimThread.interrupt();
        }
        if (workers == null) {
            terminated.countDown();
            return;
        }
        if (!tasks.isEmpty()) {
            log.info("종료 — 진행 중 run {}개를 최대 {}초 기다립니다(heartbeat는 계속 보냄)", tasks.size(), grace.toSeconds());
        }
        Instant deadline = Instant.now().plus(grace);
        while (!tasks.isEmpty() && Instant.now().isBefore(deadline)) {
            sleepQuietly(200);
        }
        if (!tasks.isEmpty()) {
            reportGiveUpAt = Instant.now().plus(REPORT_AFTER_KILL);
            log.warn("유예 시간이 지났습니다 — 남은 run {}개를 중단하고 결과를 보고합니다", tasks.size());
            for (RunTask t : tasks.values()) {
                t.cancel("러너 종료(유예 " + grace.toSeconds() + "초 초과)");
            }
        } else {
            reportGiveUpAt = Instant.now().plus(Duration.ofSeconds(10));
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(REPORT_AFTER_KILL.toSeconds() + 5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler.shutdownNow();
        log.info("러너 종료");
        terminated.countDown();
    }

    boolean stopping() {
        return stopping;
    }

    private boolean sleep(Duration d) {
        synchronized (wake) {
            if (stopping) {
                return false;
            }
            try {
                wake.wait(Math.max(1, d.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !stopping;
    }

    private void wakeUp() {
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(false);
            return t;
        };
    }
}
