package com.platform.agentservice.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HarnessMaterializer} — 번들 복사, "리포가 이긴다"(기존 파일 비덮어쓰기), 소스 없음
 * no-op을 순수 임시 디렉터리로 검증한다(프로세스·DB 없이).
 */
class HarnessMaterializerTest {

    @TempDir Path root;

    private Path bundleDir;
    private Path claudeMd;
    private Path agentsMd;
    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        bundleDir = root.resolve("bundle/.claude");
        Files.createDirectories(bundleDir.resolve("agents"));
        Files.createDirectories(bundleDir.resolve("skills/code-review"));
        Files.writeString(bundleDir.resolve("agents/backend-engineer.md"), "backend-engineer content");
        Files.writeString(bundleDir.resolve("skills/code-review/SKILL.md"), "code-review skill content");

        claudeMd = root.resolve("root-files/CLAUDE.md");
        agentsMd = root.resolve("root-files/AGENTS.md");
        Files.createDirectories(claudeMd.getParent());
        Files.writeString(claudeMd, "root CLAUDE.md content");
        Files.writeString(agentsMd, "root AGENTS.md content");

        workspace = root.resolve("workspace");
        Files.createDirectories(workspace);
    }

    private HarnessMaterializer materializerWith(Path bundle, List<String> rootFiles) {
        WorkerProperties props = new WorkerProperties(
                workspace.toString(), bundle.toString(), rootFiles,
                "claude", 80, 40, "Read,Edit", "http://localhost/api/agent/mcp", Map.of());
        return new HarnessMaterializer(props);
    }

    @Test
    void copies_bundle_tree_and_root_files_into_workspace() {
        HarnessMaterializer materializer = materializerWith(bundleDir, List.of(claudeMd.toString(), agentsMd.toString()));

        HarnessMaterializer.MaterializeResult result = materializer.materialize(workspace);

        assertThat(workspace.resolve(".claude/agents/backend-engineer.md")).exists();
        assertThat(workspace.resolve(".claude/skills/code-review/SKILL.md")).exists();
        assertThat(workspace.resolve("CLAUDE.md")).exists();
        assertThat(workspace.resolve("AGENTS.md")).exists();
        assertThat(workspace.resolve("CLAUDE.md")).hasContent("root CLAUDE.md content");
        assertThat(result.bundleSourceMissing()).isFalse();
        assertThat(result.copied()).isNotEmpty();
        assertThat(result.skippedExisting()).isEmpty();
    }

    @Test
    void repo_wins_does_not_overwrite_existing_files() throws IOException {
        // 리포가 이미 자기 CLAUDE.md와 .claude/agents/backend-engineer.md를 가진 상태를 흉내낸다.
        Files.writeString(workspace.resolve("CLAUDE.md"), "REPO OWN CLAUDE.md — 절대 안 바뀜");
        Files.createDirectories(workspace.resolve(".claude/agents"));
        Files.writeString(workspace.resolve(".claude/agents/backend-engineer.md"), "REPO OWN agent — 절대 안 바뀜");

        HarnessMaterializer materializer = materializerWith(bundleDir, List.of(claudeMd.toString(), agentsMd.toString()));
        HarnessMaterializer.MaterializeResult result = materializer.materialize(workspace);

        assertThat(workspace.resolve("CLAUDE.md")).hasContent("REPO OWN CLAUDE.md — 절대 안 바뀜");
        assertThat(workspace.resolve(".claude/agents/backend-engineer.md")).hasContent("REPO OWN agent — 절대 안 바뀜");
        // AGENTS.md와 skills/code-review는 리포에 없었으므로 정상 복사된다.
        assertThat(workspace.resolve("AGENTS.md")).exists();
        assertThat(workspace.resolve(".claude/skills/code-review/SKILL.md")).exists();
        assertThat(result.skippedExisting()).anyMatch(p -> p.endsWith("CLAUDE.md"));
        assertThat(result.skippedExisting()).anyMatch(p -> p.contains("backend-engineer.md"));
    }

    @Test
    void missing_bundle_source_is_a_silent_no_op() {
        Path missingBundle = root.resolve("does-not-exist/.claude");
        HarnessMaterializer materializer = materializerWith(missingBundle, List.of(claudeMd.toString()));

        HarnessMaterializer.MaterializeResult result = materializer.materialize(workspace);

        assertThat(result.bundleSourceMissing()).isTrue();
        assertThat(workspace.resolve(".claude")).doesNotExist();
        // 루트 파일은 번들과 무관하게 여전히 복사된다.
        assertThat(workspace.resolve("CLAUDE.md")).exists();
    }

    @Test
    void missing_root_file_source_is_a_silent_no_op() {
        Path missingRootFile = root.resolve("nope/CLAUDE.md");
        HarnessMaterializer materializer = materializerWith(bundleDir, List.of(missingRootFile.toString()));

        HarnessMaterializer.MaterializeResult result = materializer.materialize(workspace);

        assertThat(workspace.resolve("CLAUDE.md")).doesNotExist();
        assertThat(result.copied()).noneMatch(p -> p.endsWith("CLAUDE.md"));
    }
}
