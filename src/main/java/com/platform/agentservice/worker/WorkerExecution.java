package com.platform.agentservice.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 워커 실행의 "로컬에서 프로세스를 돌리는" 부분(P4a AGP-69 — {@link WorkerLauncher}에서 분리). {@link WorkSpec} 하나를 받아
 * 워크스페이스 준비(승계·회의·clone) → 하네스·페르소나 스킬 실체화 → 인증 획득({@link Authorizer}) → mcp-config 파일 → {@code claude -p}
 * 실행 → stdout JSON 파싱까지 한다. DB·Spring 빈·run 엔티티를 모른다 — 서버 인프로세스 실행과 러너 프로그램(AGP-69 T3)이 같은
 * 코드를 쓰게 하려는 경계다. run 토큰 발급·철회, LLM 키 해석, run 상태 전이는 호출자 몫이다.
 *
 * <p>인증은 워크스페이스 준비 뒤에 {@link Authorizer}로 받는다 — clone 실패처럼 준비 단계에서 끝나면 토큰을 발급하지 않는다
 * (P2a T3 이래의 순서 그대로: 발급 전 실패는 철회할 것이 없다).
 *
 * <p>mcp-config를 파일로, 클론 트리 밖 형제 디렉터리에 두는 이유(F1·I4)는 {@link WorkerLauncher} 문서 참고 — 이 클래스가 그 규칙의
 * 구현이다. 파일에는 run 토큰이 담기므로 로그로 남기지 않고 {@code finally}에서 디렉터리째 지운다.
 */
@Slf4j
public class WorkerExecution {

    static final Duration CLONE_TIMEOUT = Duration.ofMinutes(5);
    static final int RAW_TAIL_LIMIT = 2000;
    static final String MCP_CONFIG_FILENAME = ".mcp-run.json";
    /** git 클론 루트(workspace)의 형제 디렉터리 이름 접미사 — 예: {@code run-42} → {@code run-42-cfg}(I4). */
    static final String MCP_CONFIG_DIR_SUFFIX = "-cfg";
    /** 워커가 실경로를 보고하기 전의 자리표시값 — {@code RunService.PENDING_WORKSPACE}와 같다. */
    public static final String PENDING_WORKSPACE = "pending";

    /** 워크스페이스 준비가 끝난 뒤 호출된다 — run 토큰과 워커 프로세스에 실을 인증 env를 돌려준다. 던지면 실행하지 않는다. */
    @FunctionalInterface
    public interface Authorizer {
        Credentials authorize();
    }

    /** {@code runToken}은 mcp-config 파일에만, {@code env}는 워커 호출 extraEnv에만 들어간다(git clone에는 안 간다). */
    public record Credentials(String runToken, Map<String, String> env) {

        @Override
        public String toString() {
            return "Credentials{envKeys=" + (env == null ? "[]" : env.keySet()) + "}";
        }
    }

    private final String workDir;
    private final String claudeBin;
    private final HarnessMaterializer harnessMaterializer;
    private final CommandExecutor commandExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkerExecution(String workDir, String claudeBin, HarnessMaterializer harnessMaterializer,
                           CommandExecutor commandExecutor) {
        this.workDir = workDir;
        this.claudeBin = claudeBin;
        this.harnessMaterializer = harnessMaterializer;
        this.commandExecutor = commandExecutor;
    }

    public WorkerResult execute(WorkSpec spec, Authorizer authorizer) {
        Path inherited = spec.workspaceLineage() ? existingWorkspace(spec.inheritedWorkspacePath()) : null;
        if (spec.workspaceLineage() && inherited == null) {
            // 새로 clone하면 origin/main..HEAD가 비어 리뷰어가 빈 변경을 통과시킬 수 있다 — 검증 없는 확정이 되므로
            // clone으로 대신하지 않고 실패시켜 사고형 BLOCKED로 사람에게 넘긴다.
            return WorkerResult.failure(-1, false, "승계할 워크스페이스가 없습니다: " + spec.inheritedWorkspacePath(), null);
        }
        Path workspace;
        Path mcpConfigDir;
        if (inherited != null) {
            // D-P2c-1: 워커 커밋은 푸시되지 않고 이 워크스페이스에만 있다 — 다시 clone하면 검증·수정 대상이 사라진다.
            // 하네스도 이미 실체화돼 있다.
            workspace = inherited;
            // 워크스페이스 이름(run-<원 run id>-cfg)을 쓰면 같은 워크스페이스를 잇는 run들이 한 디렉터리를 공유한다 —
            // 겹쳐 돌 일은 없지만, 한쪽의 finally 삭제가 다른 쪽 토큰 파일을 지우는 경합을 구조로 없앤다.
            mcpConfigDir = workspace.resolveSibling("run-" + spec.runId() + MCP_CONFIG_DIR_SUFFIX);
        } else if (spec.meeting()) {
            // D-P3b-2: 회의는 코드가 필요 없다 — clone하지 않으므로 리포 매핑이 없는 프로젝트에서도 회의가 죽지 않는다.
            // 하네스(롤 정의)는 참석 롤을 롤플레이하는 근거라 그대로 실체화한다.
            workspace = prepareWorkspaceDir(spec.runId());
            harnessMaterializer.materialize(workspace);
            mcpConfigDir = mcpConfigDirFor(workspace);
        } else {
            workspace = prepareWorkspaceDir(spec.runId());

            CommandExecutor.ExecResult cloneResult = commandExecutor.exec(
                    List.of("git", "clone", spec.repoUrl(), "."), workspace, Map.of(), CLONE_TIMEOUT);
            if (cloneResult.timedOut() || cloneResult.exitCode() != 0) {
                return WorkerResult.failure(cloneResult.exitCode(), cloneResult.timedOut(), rawTail(cloneResult), workspace.toString());
            }

            harnessMaterializer.materialize(workspace);
            mcpConfigDir = mcpConfigDirFor(workspace);
        }
        // 계보 run도 쓴다 — REVIEW는 작업자와 다른 페르소나(리뷰어)라 승계 워크스페이스에 리뷰어 스킬이 있어야 한다(AGP-62).
        harnessMaterializer.materializePersonaSkill(workspace, spec.expertise());

        Credentials credentials = authorizer.authorize();
        Path mcpConfigPath = mcpConfigDir.resolve(MCP_CONFIG_FILENAME);
        try {
            createDirectoriesUnchecked(mcpConfigDir);
            writeMcpConfigFile(mcpConfigPath, spec.mcpUrl(), credentials.runToken());
            List<String> command = buildCommand(spec, mcpConfigPath);
            CommandExecutor.ExecResult execResult = commandExecutor.exec(
                    command, workspace, credentials.env() == null ? Map.of() : credentials.env(),
                    Duration.ofMinutes(spec.timeoutMinutes()));
            return toWorkerResult(execResult, workspace);
        } finally {
            deleteRecursivelyQuietly(mcpConfigDir);
        }
    }

    /** {@code --mcp-config}에는 JSON이 아니라 {@link #writeMcpConfigFile}이 써 둔 파일의 절대경로를 넘긴다(F1). */
    public List<String> buildCommand(WorkSpec spec, Path mcpConfigPath) {
        List<String> command = new ArrayList<>();
        command.add(claudeBin);
        command.add("-p");
        command.add(spec.prompt());
        command.add("--permission-mode");
        command.add("dontAsk");
        command.add("--permission-prompts");
        command.add("none");
        command.add("--allowedTools");
        command.add(spec.allowedTools());
        command.add("--strict-mcp-config");
        command.add("--mcp-config");
        command.add(mcpConfigPath.toAbsolutePath().toString());
        command.add("--output-format");
        command.add("json");
        command.add("--max-turns");
        command.add(String.valueOf(spec.maxTurns()));
        if (spec.model() != null && !spec.model().isBlank()) {
            command.add("--model");
            command.add(spec.model());
        }
        return command;
    }

    /** 승계받은 워크스페이스(REVIEW·반려-fix)가 실제로 남아 있으면 그 경로, 자리표시값("pending")이거나 디렉터리가 사라졌으면 {@code null}. */
    static Path existingWorkspace(String path) {
        if (path == null || path.isBlank() || PENDING_WORKSPACE.equals(path)) {
            return null;
        }
        try {
            Path candidate = Paths.get(path);
            return Files.isDirectory(candidate) ? candidate : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** 워크스페이스(git 클론 루트)의 형제 디렉터리 — 클론 트리 밖이라 어떤 git 명령도 여기 닿지 못한다(I4). */
    private static Path mcpConfigDirFor(Path workspace) {
        return workspace.resolveSibling(workspace.getFileName() + MCP_CONFIG_DIR_SUFFIX);
    }

    private Path prepareWorkspaceDir(long runId) {
        Path base = Paths.get(workDir);
        Path candidate = base.resolve("run-" + runId);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = base.resolve("run-" + runId + "-" + suffix);
            suffix++;
        }
        try {
            Files.createDirectories(candidate);
        } catch (IOException e) {
            throw new UncheckedIOException("워커 워크스페이스 생성 실패: " + candidate, e);
        }
        return candidate;
    }

    private String buildMcpConfigJson(String mcpUrl, String token) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "http");
        server.put("url", mcpUrl);
        server.put("headers", headers);

        Map<String, Object> mcpServers = new LinkedHashMap<>();
        mcpServers.put("agent-platform", server);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mcpServers", mcpServers);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("mcp-config JSON 생성 실패", e);
        }
    }

    private static void createDirectoriesUnchecked(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("mcp-config 디렉터리 생성 실패: " + dir, e);
        }
    }

    /** JSON을 파일에 쓴다(F1, I4로 위치를 클론 밖 형제 디렉터리로 옮김) — 내용에 run 토큰이 담기므로 절대 로그로 남기지 않는다. */
    private void writeMcpConfigFile(Path path, String mcpUrl, String token) {
        String json = buildMcpConfigJson(mcpUrl, token);
        try {
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("mcp-config 파일 쓰기 실패: " + path, e);
        }
    }

    /**
     * 실행이 끝나면(성공·실패·타임아웃·예외 무관) 항상 지운다 — 디렉터리 자체(파일 포함)를 재귀적으로 지워서 토큰이 담긴 파일이
     * 어디에도 남지 않게 한다(I4).
     */
    private static void deleteRecursivelyQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("mcp-config 경로 삭제 실패(자격증명이 남을 수 있음): {}", p);
                }
            });
        } catch (IOException e) {
            log.warn("mcp-config 디렉터리 순회 실패(자격증명이 남을 수 있음): {}", dir);
        }
    }

    /**
     * 헤드리스 CLI의 {@code --output-format json} 출력은 stdout 전체가 JSON 객체 하나인 것이 정상이지만, 방어적으로 파싱한다: 먼저
     * trim한 전체를 JSON으로 시도하고, 실패하면 마지막 줄부터 거슬러 올라가며 {@code {...}} 형태의 줄을 찾는다(스펙 §10.5). 그래도
     * 못 찾으면 실패로 처리한다.
     */
    WorkerResult toWorkerResult(CommandExecutor.ExecResult execResult, Path workspace) {
        String workspacePath = workspace.toString();
        if (execResult.timedOut()) {
            return WorkerResult.failure(execResult.exitCode(), true, rawTail(execResult), workspacePath);
        }
        if (execResult.exitCode() != 0) {
            return WorkerResult.failure(execResult.exitCode(), false, rawTail(execResult), workspacePath);
        }

        JsonNode root = parseLastJsonObject(execResult.stdout());
        if (root == null) {
            return WorkerResult.failure(execResult.exitCode(), false, rawTail(execResult), workspacePath);
        }

        String resultText = textOrNull(root, "result");
        String sessionId = textOrNull(root, "session_id");

        // costUsd: 실측(v2.1.263, T3 micro follow-up)은 톱레벨 total_cost_usd. 혹시 다른 버전/변형이 usage 아래 중첩해서
        // 내려주는 경우를 대비해 레거시 폴백을 남긴다.
        BigDecimal costUsd = decimalOrNull(root, "total_cost_usd");

        JsonNode usage = root.get("usage");
        long inputTokens = 0L;
        long outputTokens = 0L;
        if (usage != null && usage.isObject()) {
            if (costUsd == null) {
                costUsd = decimalOrNull(usage, "total_cost_usd");
            }
            if (usage.has("input_tokens") || usage.has("output_tokens")) {
                // 실측 shape: usage 바로 아래 토큰 카운트(usage.models 맵은 없다).
                inputTokens = longOrZero(usage, "input_tokens");
                outputTokens = longOrZero(usage, "output_tokens");
            } else {
                // 레거시 폴백: usage.models 맵이 있으면 전 모델 합산.
                JsonNode models = usage.get("models");
                if (models != null && models.isObject()) {
                    for (Map.Entry<String, JsonNode> entry : models.properties()) {
                        JsonNode modelUsage = entry.getValue();
                        inputTokens += longOrZero(modelUsage, "input_tokens");
                        outputTokens += longOrZero(modelUsage, "output_tokens");
                    }
                }
            }
        }

        // model: 실측 응답에 톱레벨 model·modelUsage가 있는지는 미확인이라 3단 폴백을 둔다
        // (톱레벨 model → 톱레벨 modelUsage 첫 키 → usage.models 첫 키 → null).
        String model = textOrNull(root, "model");
        if (model == null) {
            model = firstKey(root.get("modelUsage"));
        }
        if (model == null && usage != null) {
            model = firstKey(usage.get("models"));
        }

        return new WorkerResult(execResult.exitCode(), false, resultText, sessionId, costUsd,
                inputTokens, outputTokens, model, rawTail(execResult), workspacePath);
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && value.isNumber()) ? value.decimalValue() : null;
    }

    /** 주어진 노드가 객체면 첫 프로퍼티 키를(삽입 순서 기준), 아니면 {@code null}을 반환한다. */
    private static String firstKey(JsonNode objectNode) {
        if (objectNode == null || !objectNode.isObject()) {
            return null;
        }
        for (Map.Entry<String, JsonNode> entry : objectNode.properties()) {
            return entry.getKey();
        }
        return null;
    }

    private JsonNode parseLastJsonObject(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        String trimmed = stdout.trim();
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception ignored) {
            // 전체가 단일 JSON이 아니면 줄 단위로 마지막 JSON 객체를 찾는다.
        }
        String[] lines = trimmed.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                try {
                    return objectMapper.readTree(line);
                } catch (Exception ignored) {
                    // 이 줄은 아니었다 — 계속 거슬러 올라간다.
                }
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private static long longOrZero(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && value.isNumber()) ? value.asLong() : 0L;
    }

    /** stdout·stderr 꼬리(실패 진단용) — 러너 결과 보고도 같은 상한으로 자른다. */
    public static String rawTail(CommandExecutor.ExecResult result) {
        return tail("stdout:\n" + nullToEmpty(result.stdout()) + "\nstderr:\n" + nullToEmpty(result.stderr()));
    }

    /** {@link #RAW_TAIL_LIMIT}자 뒤꼬리. */
    public static String tail(String combined) {
        if (combined == null || combined.length() <= RAW_TAIL_LIMIT) {
            return combined;
        }
        return combined.substring(combined.length() - RAW_TAIL_LIMIT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
