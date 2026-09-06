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

import java.math.BigDecimal;
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
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0,
                "{\"result\":\"done\",\"session_id\":\"sess-1\",\"usage\":{\"total_cost_usd\":0.5}}", "", false));

        WorkerJob job = new WorkerJob("https://example.com/repo.git", "제목", "본문", List.of());
        WorkerResult result = launcher.launch(run(null), job);

        assertThat(commandExecutor.calls).hasSize(2);
        FakeCommandExecutor.Call cloneCall = commandExecutor.calls.get(0);
        assertThat(cloneCall.command()).containsExactly("git", "clone", "https://example.com/repo.git", ".");

        FakeCommandExecutor.Call claudeCall = commandExecutor.calls.get(1);
        List<String> cmd = claudeCall.command();
        assertThat(cmd.get(0)).isEqualTo("claude");
        assertThat(cmd.get(1)).isEqualTo("-p");
        assertThat(cmd).contains("--permission-mode", "dontAsk");
        assertThat(cmd).contains("--permission-prompts", "none");
        assertThat(cmd).contains("--allowedTools", "Read,Edit,Write,Bash(git *)");
        assertThat(cmd).contains("--strict-mcp-config");
        assertThat(cmd).contains("--output-format", "json");
        assertThat(cmd).contains("--max-turns", "80");
        assertThat(cmd).doesNotContain("--model"); // model 미지정이면 플래그 자체가 없어야 함

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.resultText()).isEqualTo("done");
        assertThat(result.sessionId()).isEqualTo("sess-1");
        assertThat(result.costUsd()).isEqualByComparingTo(BigDecimal.valueOf(0.5));
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

    @Test
    void mcp_config_json_contains_mcp_url_and_bearer_token() {
        stubTokenIssuance();
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "cloned", "", false));
        commandExecutor.enqueue(new CommandExecutor.ExecResult(0, "{\"result\":\"ok\"}", "", false));

        launcher.launch(run(null), new WorkerJob("https://example.com/repo.git", "t", "b", List.of()));

        List<String> cmd = commandExecutor.calls.get(1).command();
        int idx = cmd.indexOf("--mcp-config");
        String json = cmd.get(idx + 1);
        assertThat(json).contains("http://localhost/api/agent/mcp");
        assertThat(json).contains("Bearer agp_secret-token");
        assertThat(json).contains("agent-platform");
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
    }

    @Test
    void clone_failure_returns_failure_result_without_issuing_token() {
        commandExecutor.enqueue(new CommandExecutor.ExecResult(128, "", "fatal: repository not found", false));

        WorkerResult result = launcher.launch(run(null), new WorkerJob("https://bad/repo.git", "t", "b", List.of()));

        assertThat(result.exitCode()).isEqualTo(128);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.rawTail()).contains("fatal: repository not found");
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

        void enqueue(ExecResult result) {
            queuedResults.add(result);
        }

        void throwOnNextCall(RuntimeException e) {
            this.throwOnNext = e;
        }

        @Override
        public ExecResult exec(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout) {
            calls.add(new Call(command, cwd, extraEnv, timeout));
            if (calls.size() > queuedResults.size() && throwOnNext != null) {
                RuntimeException toThrow = throwOnNext;
                throwOnNext = null;
                throw toThrow;
            }
            return queuedResults.get(calls.size() - 1);
        }
    }
}
