package com.platform.agentrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 결과 보고 — 서버가 멱등(두 번째 보고는 {@code duplicate})이라 네트워크 오류·5xx·429면 지수 백오프로 계속 다시 보낸다. 보내기 전에
 * {@code <work>/pending-results/<runId>.json}에 적어 두고 성공(또는 영구 거부)하면 지운다 — 러너가 보고 도중 죽어도 다음 시작 때
 * {@link #pendingRunIds()}로 다시 보낸다. 파일에는 토큰이 없다(결과·커밋 링크뿐).
 *
 * <p>보고가 끝날 때까지 그 run은 heartbeat의 {@code runIds}에 남는다(호출자 몫) — 서버 입장에서 아직 RUNNING이라서다.
 */
class ResultReporter {

    private static final Logger log = LoggerFactory.getLogger(ResultReporter.class);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private final RunnerApi api;
    private final Path pendingDir;
    private final ObjectMapper mapper = RunnerApi.mapper();
    private final Duration initialBackoff;

    ResultReporter(RunnerApi api, Path pendingDir, Duration initialBackoff) {
        this.api = api;
        this.pendingDir = pendingDir;
        this.initialBackoff = initialBackoff;
    }

    /**
     * 보고가 받아들여지거나 영구 거부될 때까지 다시 보낸다. {@code giveUpAt}이 지나면 파일을 남기고 멈춘다(다음 시작 때 재전송).
     *
     * @return 서버에 전달됐거나 영구 거부로 끝났으면 true, 파일로 남겼으면 false
     */
    boolean report(long runId, Protocol.ResultReport report, Supplier<Instant> giveUpAt) {
        byte[] json;
        try {
            json = mapper.writeValueAsBytes(report);
            save(runId, json);
        } catch (IOException e) {
            log.warn("run={} 결과를 파일에 남기지 못했습니다(보고는 계속): {}", runId, e.getMessage());
            try {
                json = mapper.writeValueAsBytes(report);
            } catch (IOException fatal) {
                log.error("run={} 결과 직렬화 실패 — 보고하지 못합니다", runId);
                return true;
            }
        }
        return send(runId, json, giveUpAt);
    }

    /** 남은 보고 파일을 다시 보낸다(시작 때). */
    boolean resend(long runId, Supplier<Instant> giveUpAt) {
        try {
            return send(runId, Files.readAllBytes(file(runId)), giveUpAt);
        } catch (IOException e) {
            log.warn("run={} 남은 결과 파일을 읽지 못했습니다 — 버립니다: {}", runId, e.getMessage());
            delete(runId);
            return true;
        }
    }

    List<Long> pendingRunIds() {
        if (!Files.isDirectory(pendingDir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(pendingDir)) {
            return list.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("\\d+\\.json"))
                    .map(n -> Long.parseLong(n.substring(0, n.length() - 5)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private boolean send(long runId, byte[] json, Supplier<Instant> giveUpAt) {
        Duration backoff = initialBackoff;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                Protocol.ResultResponse res = api.result(runId, json);
                delete(runId);
                if (res.duplicate()) {
                    log.info("run={} 결과는 이미 보고돼 있었습니다(runStatus={})", runId, res.runStatus());
                } else {
                    log.info("run={} 결과 보고 완료 — runStatus={}", runId, res.runStatus());
                }
                return true;
            } catch (RunnerApi.HttpStatusException e) {
                if (e.permanent()) {
                    log.error("run={} 결과 보고가 거부됐습니다(HTTP {}{}) — 다시 보내지 않습니다", runId, e.status,
                            e.error == null ? "" : ": " + e.error);
                    delete(runId);
                    return true;
                }
                log.warn("run={} 결과 보고 실패({}회, HTTP {}) — {}초 뒤 재시도", runId, attempt, e.status, backoff.toSeconds());
            } catch (InterruptedException e) {
                // 보고 도중의 인터럽트는 종료 절차 — 파일을 남기고 멈춘다
                Thread.currentThread().interrupt();
                log.warn("run={} 결과 보고 중단 — 다음 시작 때 다시 보냅니다", runId);
                return false;
            } catch (IOException e) {
                log.warn("run={} 결과 보고 실패({}회, {}) — {}초 뒤 재시도", runId, attempt, e.getClass().getSimpleName(),
                        backoff.toSeconds());
            }
            if (Instant.now().plus(backoff).isAfter(giveUpAt.get())) {
                log.warn("run={} 결과를 보내지 못한 채 종료합니다 — {}에 남겨 두었고 다음 시작 때 다시 보냅니다", runId, file(runId));
                return false;
            }
            try {
                Thread.sleep(jitter(backoff).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
        }
    }

    static Duration jitter(Duration base) {
        long ms = Math.max(1, base.toMillis());
        return Duration.ofMillis(ms / 2 + ThreadLocalRandom.current().nextLong(ms / 2 + 1));
    }

    private void save(long runId, byte[] json) throws IOException {
        Files.createDirectories(pendingDir);
        Path tmp = pendingDir.resolve(runId + ".json.tmp");
        Files.write(tmp, json);
        Files.move(tmp, file(runId), StandardCopyOption.REPLACE_EXISTING);
    }

    private void delete(long runId) {
        try {
            Files.deleteIfExists(file(runId));
        } catch (IOException e) {
            log.debug("결과 파일 삭제 실패: {}", e.getMessage());
        }
    }

    Path file(long runId) {
        return pendingDir.resolve(runId + ".json");
    }
}
