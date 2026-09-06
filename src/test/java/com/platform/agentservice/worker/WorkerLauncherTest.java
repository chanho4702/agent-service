package com.platform.agentservice.worker;

import com.platform.agentservice.pat.PatService;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.pat.dto.PatCreateRequest;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunTokenService;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkerLauncher} — 실 프로세스 없이 {@link FakeCommandExecutor}로 커맨드 조립·
 * 프롬프트 구성·clone-우선 순서·토큰 발급/철회·stdout JSON 파싱을 검증한다(P2a T3).
 */
@ExtendWith(MockitoExtension.class)
class WorkerLauncherTest {

    private static final long PERSONA_ID = 5L;
    private static final long RUN_ID = 42L;

    @Mock PatService patService;
    @Mock PersonaRepository personaRepository;

    @TempDir Path workDir;

    private FakeCommandExecutor commandExecutor;
    private WorkerLauncher launcher;
    private WorkerProperties properties;

    @BeforeEach
    void setUp() {
        commandExecutor = new FakeCommandExecutor();
        properties = new WorkerProperties(
                workDir.toString(),
                workDir.resolve("no-bundle-here").toString(), // 번들 없음 — materialize no-op으로 단순화
                List.of(),
                "claude",
                80,
                40,
                "Read,Edit,Write,Bash(git *)",
                "http://localhost/api/agent/mcp",
                Map.of());
        HarnessMaterializer materializer = new HarnessMaterializer(properties);
        RunTokenService runTokenService = new RunTokenService(patService, personaRepository);
        launcher = new WorkerLauncher(properties, materializer, commandExecutor, runTokenService);
    }

    private Run run(String model) {
        Run r = Run.queued(RunType.TASK, "AGP-9", 1L, PERSONA_ID, RunTrigger.USER, "harness://local", model);
        ReflectionTestUtils.setField(r, "id", RUN_ID);
        return r;
    }

    private void stubTokenIssuance() {
        Persona persona = Persona.of(77L, "bot-a", PersonaRole.BACKEND, "봇A", null, null);
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.of(persona));
        when(patService.issue(any(PatCreateRequest.class), org.mockito.ArgumentMatchers.eq(RunTokenService.SYSTEM_MEMBER_ID)))
                .thenReturn(new PatCreatedResponse("agp_secret-token", 9L, "run:" + RUN_ID, "bot-a"));
    }

    @Test
    void clones_repo_before_running_claude_and_command_targets_claude_binary_with_flags() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        // 톱레벨 total_cost_usd — 실측(v2.1.263) shape. usage.total_cost_usd 아님(T3 micro follow-up).
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0,
                "{\"result\":\"done\",\"session_id\":\"sess-1\",\"total_cost_usd\":0.5}", "", false));

        WorkerJob job = new WorkerJob("https://example.com/repo.git", "제목", "본문", List.of());
        WorkerResult result = launcher.launch(run(null), job);

        assertThat(commandExecutor.calls).hasSize(2);
        FakeCommandExecutor.Call cloneCall = commandExecutor.calls.get(0);
        assertThat(cloneCall.command()).containsExactly("git", "clone", "https://example.com/repo.git", ".");

        FakeCommandExecutor.Call claudeCall = commandExecutor.calls.get(1);
        List<String> cmd = claudeCall.command();
        assertThat(cmd.get(0)).isEqualTo("claude");
        assertThat(cmd.get(1)).isEqualTo("-p");
        // AssertJ의 contains(flag, value)는 멤버십만 검사한다(값과 플래그가 뒤바뀌어도 통과) —
        // 플래그 바로 다음 위치의 값을 직접 확인한다(fix round 1 I1).
        assertThat(cmd.get(cmd.indexOf("--permission-mode") + 1)).isEqualTo("dontAsk");
        assertThat(cmd.get(cmd.indexOf("--permission-prompts") + 1)).isEqualTo("none");
        assertThat(cmd.get(cmd.indexOf("--allowedTools") + 1)).isEqualTo("Read,Edit,Write,Bash(git *)");
        assertThat(cmd).contains("--strict-mcp-config");
        assertThat(cmd.get(cmd.indexOf("--output-format") + 1)).isEqualTo("json");
        assertThat(cmd.get(cmd.indexOf("--max-turns") + 1)).isEqualTo("80");
        assertThat(cmd).doesNotContain("--model"); // model 미지정이면 플래그 자체가 없어야 함

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.resultText()).isEqualTo("done");
        assertThat(result.sessionId()).isEqualTo("sess-1");
        assertThat(result.costUsd()).isEqualByComparingTo(BigDecimal.valueOf(0.5));
        // T6b: 커밋 파서가 실제 워크스페이스를 찾을 수 있도록 결과에 실제 경로를 싣는다
        // (run.workspacePath DB 컬럼은 "pending" 그대로다 — WorkerResult가 대신 나른다).
        assertThat(result.workspacePath()).isEqualTo(workDir.resolve("run-" + RUN_ID).toString());
    }

    @Test
    void parses_real_cli_output_shape_top_level_cost_and_direct_usage_tokens() {
        // T3 micro follow-up: 이 머신에서 실측한 `claude -p --output-format json`(v2.1.263)의
        // 실제 shape 그대로 재현한 픽스처. total_cost_usd/session_id는 톱레벨, 토큰은
        // usage.input_tokens/output_tokens에 직접 있고 usage.models 맵은 없다. 톱레벨
        // model·modelUsage 존재 여부는 미확인이라 이 픽스처에는 넣지 않는다(폴백 결과는 null).
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0,
                "{\"result\":\"done\",\"session_id\":\"sess-1\",\"total_cost_usd\":0.5,"
                        + "\"usage\":{\"input_tokens\":120,\"output_tokens\":340,"
                        + "\"cache_creation_input_tokens\":10,\"cache_read_input_tokens\":5,"
                        + "\"output_tokens_details\":{\"reasoning_tokens\":0}},"
                        + "\"iterations\":[{\"note\":\"n/a\"}]}",
                "", false));

        WorkerResult result = launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "t", "b", List.of()));

        assertThat(result.resultText()).isEqualTo("done");
        assertThat(result.sessionId()).isEqualTo("sess-1");
        assertThat(result.costUsd()).isEqualByComparingTo(BigDecimal.valueOf(0.5));
        assertThat(result.inputTokens()).isEqualTo(120L);
        assertThat(result.outputTokens()).isEqualTo(340L);
        assertThat(result.model()).isNull();
    }

    @Test
    void parses_legacy_nested_usage_models_shape_as_fallback() {
        // 레거시(당초 가정했던) shape: total_cost_usd가 usage 아래 중첩, 토큰은 usage.models
        // 맵의 모델별 항목으로. 실측과 다르지만 혹시 남아있는 변형에 대비한 폴백 경로 검증.
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0,
                "{\"result\":\"legacy-done\",\"session_id\":\"sess-legacy\","
                        + "\"usage\":{\"total_cost_usd\":0.75,\"models\":{\"claude-sonnet-5\":"
                        + "{\"input_tokens\":50,\"output_tokens\":75}}}}",
                "", false));

        WorkerResult result = launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "t", "b", List.of()));

        assertThat(result.resultText()).isEqualTo("legacy-done");
        assertThat(result.sessionId()).isEqualTo("sess-legacy");
        assertThat(result.costUsd()).isEqualByComparingTo(BigDecimal.valueOf(0.75));
        assertThat(result.inputTokens()).isEqualTo(50L);
        assertThat(result.outputTokens()).isEqualTo(75L);
        assertThat(result.model()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void includes_model_flag_when_run_has_model() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "{\"result\":\"ok\"}", "", false));

        launcher.launch(run("claude-sonnet-5"), new WorkerJob("https://example.com/repo.git", "t", "b", List.of()));

        List<String> cmd = commandExecutor.calls.get(1).command();
        int idx = cmd.indexOf("--model");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(cmd.get(idx + 1)).isEqualTo("claude-sonnet-5");
    }

    /**
     * F1(P2a T7 fix round): {@code --mcp-config}는 이제 인라인 JSON이 아니라 파일 경로다 —
     * Windows ProcessBuilder 인자 손상 재발 방지(task-7 3회 재현). 인자 값 자체가 순수
     * 경로 문자열(따옴표·중괄호 없음)인지, 그 경로의 파일이 CLI 호출 시점에는 올바른
     * JSON을 담고 있었는지(FakeCommandExecutor가 호출 순간 내용을 캡처), 실행이 끝난
     * 뒤에는 디렉터리째 삭제됐는지(토큰 잔존 방지)를 모두 확인한다.
     *
     * <p><b>I4(최종 리뷰)</b>: 그 파일은 워크스페이스(git 클론 루트) 안이 아니라 형제
     * 디렉터리 {@code run-<id>-cfg}에 있어야 한다 — 워커가 {@code Bash(git *)}로 클론
     * 트리 안의 아무 파일이나 커밋할 수 있으므로, 자격증명 파일은 애초에 그 트리 밖에
     * 둬야 한다. 워크스페이스 쪽에는 이 파일이 존재한 적조차 없어야 함을 함께 확인한다.
     */
    @Test
    void mcp_config_is_written_to_a_sibling_dir_outside_the_clone_and_deleted_after_run_completes() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "{\"result\":\"ok\"}", "", false));

        launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "t", "b", List.of()));

        List<String> cmd = commandExecutor.calls.get(1).command();
        int idx = cmd.indexOf("--mcp-config");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        String passedArg = cmd.get(idx + 1);
        Path workspace = workDir.resolve("run-" + RUN_ID);
        Path expectedDir = workDir.resolve("run-" + RUN_ID + "-cfg");
        Path expectedPath = expectedDir.resolve(".mcp-run.json");
        assertThat(passedArg).isEqualTo(expectedPath.toString());
        // 인라인 JSON이었다면 반드시 있었을 문자들이 인자 값에는 전혀 없어야 한다(재발 방지 가드).
        assertThat(passedArg).doesNotContain("{").doesNotContain("\"");
        // 클론 트리(workspace) 밖의 형제 디렉터리여야 한다 — git 명령이 닿을 수 없는 위치.
        assertThat(expectedDir.getParent()).isEqualTo(workspace.getParent());
        assertThat(expectedDir).isNotEqualTo(workspace);

        String contentAtCallTime = commandExecutor.mcpConfigContentAtCall;
        assertThat(contentAtCallTime).contains("http://localhost/api/agent/mcp");
        assertThat(contentAtCallTime).contains("Bearer agp_secret-token");
        assertThat(contentAtCallTime).contains("agent-platform");

        // 실행 후에는 파일뿐 아니라 디렉터리 자체도 남아 있으면 안 된다(재귀 삭제).
        assertThat(Files.exists(expectedPath)).isFalse();
        assertThat(Files.exists(expectedDir)).isFalse();
        // 워크스페이스(클론 루트) 쪽에는 이 파일이 존재한 적조차 없어야 한다.
        assertThat(Files.exists(workspace.resolve(".mcp-run.json"))).isFalse();
    }

    @Test
    void mcp_config_dir_is_deleted_even_when_claude_exits_nonzero() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(1, "not json at all", "boom", false));

        launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "t", "b", List.of()));

        Path expectedDir = workDir.resolve("run-" + RUN_ID + "-cfg");
        assertThat(Files.exists(expectedDir)).isFalse();
    }

    @Test
    void prompt_contains_issue_key_comments_convention_and_run_id() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "{\"result\":\"ok\"}", "", false));

        List<String> comments = List.of("사람: 이 부분은 X로 바꿔주세요", "사람: 테스트도 추가");
        launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "이슈 제목", "이슈 본문", comments));

        String prompt = commandExecutor.calls.get(1).command().get(2); // [claude, -p, <prompt>, ...]
        assertThat(prompt).contains("AGP-9");
        assertThat(prompt).contains("이슈 제목");
        assertThat(prompt).contains("이슈 본문");
        assertThat(prompt).contains("사람: 이 부분은 X로 바꿔주세요");
        assertThat(prompt).contains("사람: 테스트도 추가");
        assertThat(prompt).contains("get_project_context");
        assertThat(prompt).contains("report_progress(runId=" + RUN_ID);
        assertThat(prompt).contains("report_result(runId=" + RUN_ID);
        assertThat(prompt).contains("request_gate(runId=" + RUN_ID);
        assertThat(prompt).contains("runId=" + RUN_ID);

        // fix round 1 I2: 사용자 원문(이슈 내용/코멘트)은 명시적 경계로 감싸고, 규약이 그
        // 데이터보다 우선한다는 문구가 경계 다음·규약 섹션 이전에 있어야 한다.
        assertThat(prompt).contains("<이슈-내용>");
        assertThat(prompt).contains("</이슈-내용>");
        assertThat(prompt).contains("<코멘트>");
        assertThat(prompt).contains("</코멘트>");
        assertThat(prompt).contains("규약이 우선한다");
        assertThat(prompt).containsSubsequence(
                "<이슈-내용>", "이슈 제목", "</이슈-내용>",
                "<코멘트>", "사람: 이 부분은 X로 바꿔주세요", "</코멘트>",
                "규약이 우선한다", "## 작업 규약");
    }

    @Test
    void token_is_revoked_after_successful_run() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "{\"result\":\"ok\"}", "", false));

        launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "t", "b", List.of()));

        verify(patService).revoke(9L);
    }

    @Test
    void token_is_revoked_even_when_executor_throws() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.throwOnNextCall(new RuntimeException("boom"));

        assertThatThrownBy(() -> launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "t", "b", List.of())))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        verify(patService).revoke(9L);
        // F1/I4: 실행기가 던져도 mcp-config 디렉터리(토큰 포함)가 남으면 안 된다.
        assertThat(Files.exists(workDir.resolve("run-" + RUN_ID + "-cfg"))).isFalse();
    }

    @Test
    void clone_failure_returns_failure_result_without_issuing_token() {
        commandExecutor.enqueue(new CommandExecutor.ExecResult(128, "", "fatal: repository not found", false));

        WorkerResult result = launcher.launch(run(null), new WorkerJob("https://bad/repo.git", "t", "b", List.of()));

        assertThat(result.exitCode()).isEqualTo(128);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.rawTail()).contains("fatal: repository not found");
        // T6b: clone이 실패해도 워크스페이스 디렉터리 자체는 이미 만들어졌으니 경로를 싣는다
        // (거기서 git log를 돌리면 실패할 뿐 — 커밋 파서가 빈 리스트로 처리한다).
        assertThat(result.workspacePath()).isEqualTo(workDir.resolve("run-" + RUN_ID).toString());
        assertThat(commandExecutor.calls).hasSize(1); // claude는 아예 실행되지 않았다
        verify(patService, org.mockito.Mockito.never()).issue(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void nonzero_exit_from_claude_yields_failure_with_raw_tail() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(1, "not json at all", "some error on stderr", false));

        WorkerResult result = launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "t", "b", List.of()));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.resultText()).isNull();
        assertThat(result.rawTail()).contains("not json at all");
        assertThat(result.rawTail()).contains("some error on stderr");
        verify(patService).revoke(9L); // 실패해도 revoke는 됨
    }

    @Test
    void garbage_stdout_on_success_exit_falls_back_to_failure_result() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "totally not json", "", false));

        WorkerResult result = launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "t", "b", List.of()));

        assertThat(result.resultText()).isNull();
        assertThat(result.sessionId()).isNull();
        assertThat(result.rawTail()).contains("totally not json");
    }

    @Test
    void timeout_returns_failure_result_marked_timed_out() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(-1, "partial", "", true));

        WorkerResult result = launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "t", "b", List.of()));

        assertThat(result.timedOut()).isTrue();
        verify(patService).revoke(9L);
    }

    /** 실행 없이 커맨드 호출을 기록만 하는 페이크 — 큐에서 순서대로 결과를 꺼내 반환한다. */
    private static final class FakeCommandExecutor implements CommandExecutor {
        record Call(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout) {
        }

        final List<Call> calls = new ArrayList<>();
        private final List<ExecResult> queuedResults = new ArrayList<>();
        private RuntimeException throwOnNext;
        /** F1: {@code --mcp-config} 인자가 있으면 그 경로가 가리키는 파일 내용을 호출 시점에 캡처한다(그 뒤 삭제되므로 나중엔 못 읽는다). */
        String mcpConfigContentAtCall;

        void enqueue(ExecResult result) {
            queuedResults.add(result);
        }

        void throwOnNextCall(RuntimeException e) {
            this.throwOnNext = e;
        }

        @Override
        public ExecResult exec(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout) {
            calls.add(new Call(command, cwd, extraEnv, timeout));
            int mcpConfigIdx = command.indexOf("--mcp-config");
            if (mcpConfigIdx >= 0 && mcpConfigIdx + 1 < command.size()) {
                try {
                    mcpConfigContentAtCall = Files.readString(Path.of(command.get(mcpConfigIdx + 1)));
                } catch (IOException e) {
                    mcpConfigContentAtCall = null;
                }
            }
            if (calls.size() > queuedResults.size() && throwOnNext != null) {
                RuntimeException toThrow = throwOnNext;
                throwOnNext = null;
                throw toThrow;
            }
            return queuedResults.get(calls.size() - 1);
        }
    }
}
