package com.platform.agentservice.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link CommitLinkParser} — 실제 git 프로세스 없이 {@link FakeCommandExecutor}로 로그 파싱·
 * 이슈 키 매칭·URL 정규화·실패 시 빈 리스트를 검증한다(P2a T6b).
 */
class CommitLinkParserTest {

    @TempDir Path workspace;

    @Test
    void parses_two_commits_with_issue_keys_and_builds_canonical_commit_urls() {
        FakeCommandExecutor executor = new FakeCommandExecutor();
        executor.stubRemoteUrl("git@github.com:Owner/Repo.git");
        executor.stubLog(0, "aaa1111\tAGP-8: fix login bug\nbbb2222\t[AGP-9] add tests\n");
        CommitLinkParser parser = new CommitLinkParser(executor);

        List<CommitLinkParser.CommitLink> links = parser.parse(workspace);

        assertThat(links).hasSize(2);
        assertThat(links.get(0).issueKey()).isEqualTo("AGP-8");
        assertThat(links.get(0).sha()).isEqualTo("aaa1111");
        assertThat(links.get(0).url()).isEqualTo("https://github.com/Owner/Repo/commit/aaa1111");
        assertThat(links.get(1).issueKey()).isEqualTo("AGP-9");
        assertThat(links.get(1).url()).isEqualTo("https://github.com/Owner/Repo/commit/bbb2222");
    }

    @Test
    void commits_without_an_issue_key_are_skipped() {
        FakeCommandExecutor executor = new FakeCommandExecutor();
        executor.stubRemoteUrl("https://github.com/o/r.git");
        executor.stubLog(0, "aaa1111\tjust a chore commit\nbbb2222\tAGP-1: real fix\n");
        CommitLinkParser parser = new CommitLinkParser(executor);

        List<CommitLinkParser.CommitLink> links = parser.parse(workspace);

        assertThat(links).hasSize(1);
        assertThat(links.get(0).issueKey()).isEqualTo("AGP-1");
    }

    @Test
    void git_log_failure_returns_empty_list_without_throwing() {
        FakeCommandExecutor executor = new FakeCommandExecutor();
        executor.stubRemoteUrl("https://github.com/o/r.git");
        executor.stubLog(128, "");
        CommitLinkParser parser = new CommitLinkParser(executor);

        List<CommitLinkParser.CommitLink> links = assertDoesNotThrow(() -> parser.parse(workspace));

        assertThat(links).isEmpty();
    }

    @Test
    void executor_throwing_returns_empty_list_without_propagating() {
        FakeCommandExecutor executor = new FakeCommandExecutor();
        executor.throwOnExec(new RuntimeException("git not found"));
        CommitLinkParser parser = new CommitLinkParser(executor);

        List<CommitLinkParser.CommitLink> links = assertDoesNotThrow(() -> parser.parse(workspace));

        assertThat(links).isEmpty();
    }

    @Test
    void missing_workspace_directory_returns_empty_list() {
        FakeCommandExecutor executor = new FakeCommandExecutor();
        CommitLinkParser parser = new CommitLinkParser(executor);

        List<CommitLinkParser.CommitLink> links = parser.parse(workspace.resolve("does-not-exist"));

        assertThat(links).isEmpty();
        assertThat(executor.calls).isEmpty();
    }

    @Test
    void null_workspace_returns_empty_list() {
        CommitLinkParser parser = new CommitLinkParser(new FakeCommandExecutor());

        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void unresolvable_remote_url_yields_null_commit_url_but_still_links_issue_key() {
        FakeCommandExecutor executor = new FakeCommandExecutor();
        executor.stubRemoteUrl(""); // remote 없음
        executor.stubLog(0, "aaa1111\tAGP-8: fix\n");
        CommitLinkParser parser = new CommitLinkParser(executor);

        List<CommitLinkParser.CommitLink> links = parser.parse(workspace);

        assertThat(links).hasSize(1);
        assertThat(links.get(0).issueKey()).isEqualTo("AGP-8");
        assertThat(links.get(0).url()).isNull();
    }

    // ---- canonicalizeRepoUrl ----

    @Test
    void canonicalizeRepoUrl_normalizes_ssh_git_url() {
        assertThat(CommitLinkParser.canonicalizeRepoUrl("git@GitHub.com:Owner/Repo.git"))
                .isEqualTo("https://github.com/Owner/Repo");
    }

    @Test
    void canonicalizeRepoUrl_strips_dot_git_and_trailing_slash_and_lowercases_host() {
        assertThat(CommitLinkParser.canonicalizeRepoUrl("https://GitHub.com/o/r.git/"))
                .isEqualTo("https://github.com/o/r");
    }

    @Test
    void canonicalizeRepoUrl_returns_null_for_blank() {
        assertThat(CommitLinkParser.canonicalizeRepoUrl(null)).isNull();
        assertThat(CommitLinkParser.canonicalizeRepoUrl("  ")).isNull();
    }

    /** 실행 없이 커맨드를 구분해 응답만 큐잉하는 페이크. */
    private static final class FakeCommandExecutor implements CommandExecutor {
        final List<List<String>> calls = new java.util.ArrayList<>();
        private CommandExecutor.ExecResult remoteUrlResult;
        private CommandExecutor.ExecResult logResult;
        private RuntimeException throwOnExec;

        void stubRemoteUrl(String url) {
            remoteUrlResult = new ExecResult(0, url, "", false);
        }

        void stubLog(int exitCode, String stdout) {
            logResult = new ExecResult(exitCode, stdout, "", false);
        }

        void throwOnExec(RuntimeException e) {
            this.throwOnExec = e;
        }

        @Override
        public ExecResult exec(List<String> command, Path cwd, Map<String, String> extraEnv, Duration timeout) {
            calls.add(command);
            if (throwOnExec != null) {
                throw throwOnExec;
            }
            if (command.contains("config")) {
                return remoteUrlResult != null ? remoteUrlResult : new ExecResult(1, "", "no remote", false);
            }
            return logResult != null ? logResult : new ExecResult(1, "", "no log stub", false);
        }
    }
}
