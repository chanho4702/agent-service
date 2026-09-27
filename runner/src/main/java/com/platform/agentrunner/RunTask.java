package com.platform.agentrunner;

import com.platform.agentservice.worker.CommandExecutor;
import com.platform.agentservice.worker.CommitLinkParser;
import com.platform.agentservice.worker.HarnessMaterializer;
import com.platform.agentservice.worker.WorkSpec;
import com.platform.agentservice.worker.WorkerExecution;
import com.platform.agentservice.worker.WorkerProperties;
import com.platform.agentservice.worker.WorkerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * claim 하나 실행 — 하네스 확보 → 서버와 같은 {@link WorkerExecution}(워크스페이스·clone·하네스·페르소나 스킬·mcp-config·claude -p·JSON
 * 파싱) → {@link CommitLinkParser}로 커밋 링크 → 결과 보고. 인증은 서버 인프로세스와 같은 자리({@code Authorizer} — 워크스페이스 준비 뒤)에서
 * 준다: run 토큰은 mcp-config 파일에만, LLM 키는 워커 호출 env에만.
 *
 * <p>중단({@link #cancel}): 서버가 {@code stopRunIds}로 알리거나 러너가 종료 유예를 넘기면 자식 프로세스 트리를 죽이고 결과를
 * "중단됨"(비정상 종료)으로 보고한다.
 */
class RunTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RunTask.class);
    static final String API_KEY_ENV = "ANTHROPIC_API_KEY";
    private static final int COMMITS_MAX = 100;

    /** 실행에 필요한 러너 쪽 부품. */
    /**
     * {@code mcpUrl}: 워커 mcp-config에 쓸 주소 — 러너 자신의 {@code --server} + {@code /api/agent/mcp}. 서버가 보낸 {@code spec.mcpUrl}은 서버 설정
     * 한 값이라 플랫폼 러너(내부 nginx)와 PC 러너(공개 주소)를 동시에 맞출 수 없다. null이면 spec 값을 쓴다.
     */
    record Context(Path workspacesDir, String claudeBin, List<String> extraEnvKeys, Map<String, String> ownEnv,
                   HarnessCache harness, CommandExecutor executor, ProcessKiller killer, ResultReporter reporter,
                   Supplier<Instant> reportGiveUpAt, String mcpUrl) {
    }

    private final Protocol.ClaimResponse claim;
    private final Context ctx;
    private final Runnable onDone;
    private volatile Thread thread;
    private volatile boolean executing;
    private volatile String cancelReason;

    RunTask(Protocol.ClaimResponse claim, Context ctx, Runnable onDone) {
        this.claim = claim;
        this.ctx = ctx;
        this.onDone = onDone;
    }

    long runId() {
        return claim.runId();
    }

    /** 이 run이 이어 쓰는(보존해야 할) 승계 워크스페이스 — 없으면 null. */
    Path inheritedWorkspace() {
        WorkSpec spec = claim.spec();
        if (spec == null || !spec.workspaceLineage() || spec.inheritedWorkspacePath() == null) {
            return null;
        }
        try {
            return Path.of(spec.inheritedWorkspacePath()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 실행 중이면 프로세스 트리를 죽인다. 보고 단계에서는 아무것도 하지 않는다(보고는 끝까지 간다). */
    void cancel(String reason) {
        if (cancelReason != null) {
            return;
        }
        cancelReason = reason;
        Thread t = thread;
        if (executing && t != null) {
            log.warn("run={} 중단 — {}", claim.runId(), reason);
            ctx.killer().kill(t);
            t.interrupt();
        }
    }

    @Override
    public void run() {
        thread = Thread.currentThread();
        executing = true;
        WorkerResult result;
        List<CommitLinkParser.CommitLink> commits = List.of();
        WorkSpec spec = claim.spec();
        try {
            log.info("run={} 시작 — {} {}({})", claim.runId(), claim.issueKey(), spec == null ? "?" : spec.runType(),
                    claim.credentialScope());
            if (spec == null || claim.runToken() == null) {
                throw new IllegalStateException("claim 응답에 실행 명세·run 토큰이 없습니다");
            }
            if (ctx.mcpUrl() != null) {
                spec = spec.withMcpUrl(ctx.mcpUrl());
            }
            Workspaces.touch(inheritedWorkspace());
            HarnessCache.Harness harness = ctx.harness().ensure(claim.harness() == null ? null : claim.harness().sha256());
            WorkerProperties props = new WorkerProperties(ctx.workspacesDir().toString(), harness.bundle().toString(),
                    harness.rootFiles(), ctx.claudeBin(), spec.maxTurns(), spec.timeoutMinutes(), spec.allowedTools(),
                    spec.mcpUrl(), Map.of(), ctx.extraEnvKeys(), false);
            WorkerExecution execution = new WorkerExecution(ctx.workspacesDir().toString(), ctx.claudeBin(),
                    new HarnessMaterializer(props), ctx.executor());
            result = execution.execute(spec, () -> {
                if (cancelReason != null) {
                    throw new IllegalStateException("실행 전에 중단됐습니다");
                }
                return new WorkerExecution.Credentials(claim.runToken(), workerEnv(claim.llmApiKey(), ctx.ownEnv()));
            });
            if (cancelReason == null && !spec.meeting() && result.workspacePath() != null) {
                // 회의 워크스페이스는 git이 아니다 — git이 상위 저장소를 찾아 남의 커밋을 링크하지 않게 건너뛴다(서버 규칙과 같다)
                commits = new CommitLinkParser(ctx.executor()).parse(Path.of(result.workspacePath()));
            }
            Workspaces.touch(result.workspacePath() == null ? null : Path.of(result.workspacePath()));
        } catch (InterruptedException e) {
            result = WorkerResult.failure(-1, false, "러너: 실행 중 인터럽트", null);
        } catch (RuntimeException e) {
            log.warn("run={} 러너 실행 오류: {}", claim.runId(), e.getClass().getSimpleName());
            result = WorkerResult.failure(-1, false, "러너 실행 오류: " + describe(e), null);
        } finally {
            executing = false;
            Thread.interrupted(); // 중단 인터럽트가 결과 보고 HTTP를 끊지 않게
        }
        if (cancelReason != null) {
            result = cancelled(result, cancelReason);
        }
        log.info("run={} 종료 — exit={}{}", claim.runId(), result.exitCode(), result.timedOut() ? " (시간 초과)" : "");
        try {
            ctx.reporter().report(claim.runId(), toReport(result, commits), ctx.reportGiveUpAt());
        } finally {
            onDone.run();
        }
    }

    /**
     * 워커 env — PLATFORM 러너(claim에 키가 있음)는 그 키를 {@code ANTHROPIC_API_KEY} <b>하나만</b>(러너 env의 다른 인증은 싣지 않는다 —
     * 과금 주체가 흐려지지 않게, 서버 인프로세스와 같은 규칙). LOCAL(키 없음)은 플랫폼 것을 아무것도 넣지 않고, 러너 프로세스 자신의 env에
     * 사람이 둔 인증 키만 넘긴다(env 커튼이 부모 env를 막으므로 넘기지 않으면 사용자 키가 워커에 닿지 않는다). 둘 다 없으면 빈 맵 —
     * claude가 그 PC의 구독 로그인({@code ~/.claude})으로 돈다.
     */
    static Map<String, String> workerEnv(String llmApiKey, Map<String, String> ownEnv) {
        if (llmApiKey != null && !llmApiKey.isBlank()) {
            return Map.of(API_KEY_ENV, llmApiKey);
        }
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : ClaudeCli.AUTH_ENV_KEYS) {
            String value = ownEnv.get(key);
            if (value != null && !value.isBlank()) {
                env.put(key, value);
            }
        }
        return env;
    }

    static WorkerResult cancelled(WorkerResult r, String reason) {
        String note = "러너: 중단됨 — " + reason;
        String tail = r.rawTail() == null ? note : note + "\n" + r.rawTail();
        return new WorkerResult(r.exitCode() == 0 ? -1 : r.exitCode(), false, r.resultText(), r.sessionId(), r.costUsd(),
                r.inputTokens(), r.outputTokens(), r.model(), WorkerExecution.tail(tail), r.workspacePath());
    }

    static Protocol.ResultReport toReport(WorkerResult r, List<CommitLinkParser.CommitLink> links) {
        List<Protocol.CommitReport> commits = new ArrayList<>();
        for (CommitLinkParser.CommitLink l : links) {
            if (commits.size() >= COMMITS_MAX) {
                break;
            }
            commits.add(new Protocol.CommitReport(l.issueKey(), cut(l.sha(), 64), cut(l.subject(), 500), cut(l.url(), 500)));
        }
        return new Protocol.ResultReport(r.exitCode(), r.timedOut(), r.resultText(), cut(r.sessionId(), 80), r.costUsd(),
                r.inputTokens(), r.outputTokens(), r.model(), r.rawTail(), r.workspacePath(), commits);
    }

    /** 예외 요지 — 메시지에 커맨드(프롬프트)가 실릴 수 있어 200자로 자른다. 토큰은 어떤 예외 메시지에도 싣지 않는다(worker-core 규약). */
    private static String describe(RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "…";
        }
        return e.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
