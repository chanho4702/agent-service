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
                "claude", 80, 40, "Read,Edit", "http://localhost/api/agent/mcp", Map.of(), List.of(), false);
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

    /** AGP-51 — 개인 로컬 설정은 어느 깊이에 있든 워크스페이스로 새지 않는다. 공유 settings.json은 그대로 간다. */
    @Test
    void settings_local_json_is_never_copied_but_shared_settings_is() throws IOException {
        Files.writeString(bundleDir.resolve("settings.local.json"), "{\"permissions\":{\"allow\":[\"Bash(*)\"]}}");
        Files.writeString(bundleDir.resolve("settings.json"), "{}");
        Files.writeString(bundleDir.resolve("skills/code-review/settings.local.json"), "{}");

        HarnessMaterializer.MaterializeResult result = materializerWith(bundleDir, List.of()).materialize(workspace);

        assertThat(workspace.resolve(".claude/settings.local.json")).doesNotExist();
        assertThat(workspace.resolve(".claude/skills/code-review/settings.local.json")).doesNotExist();
        assertThat(workspace.resolve(".claude/settings.json")).exists();
        assertThat(result.copied()).noneMatch(path -> path.endsWith("settings.local.json"));
        assertThat(result.skippedExisting()).noneMatch(path -> path.endsWith("settings.local.json"));
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

    // ---- AGP-62: 페르소나 스킬 실체화 ----

    private static final WorkerJob.Expertise JIHO = new WorkerJob.Expertise("jiho", "지호", "BACKEND",
            "## 백엔드: 장애를 \"조용히\" 삼키지 않는다\n- 409/503 계약을 지킨다\n");

    @Test
    void persona_skill_is_written_as_claude_code_skill_with_frontmatter() throws IOException {
        Path file = materializerWith(bundleDir, List.of()).materializePersonaSkill(workspace, JIHO);

        assertThat(file).isEqualTo(workspace.resolve(".claude/skills/persona-jiho/SKILL.md").toAbsolutePath().normalize());
        String md = Files.readString(file);
        assertThat(md).startsWith("---\nname: persona-jiho\ndescription: \"지호(BACKEND)의 전문성과 작업 방식 — "
                + "백엔드: 장애를 \\\"조용히\\\" 삼키지 않는다. 이 페르소나로 작업·리뷰·회의할 때 참고한다.\"\n---\n\n");
        assertThat(md).contains(HarnessMaterializer.GENERATED_MARKER);
        assertThat(md).endsWith("## 백엔드: 장애를 \"조용히\" 삼키지 않는다\n- 409/503 계약을 지킨다\n");
    }

    @Test
    void no_skills_creates_nothing() {
        HarnessMaterializer m = materializerWith(bundleDir, List.of());

        assertThat(m.materializePersonaSkill(workspace, null)).isNull();
        assertThat(m.materializePersonaSkill(workspace, new WorkerJob.Expertise("jiho", "지호", "BACKEND", "  \n"))).isNull();
        assertThat(workspace.resolve(".claude/skills")).doesNotExist();
    }

    @Test
    void unsafe_slug_cannot_escape_the_skills_directory() {
        HarnessMaterializer m = materializerWith(bundleDir, List.of());

        for (String slug : List.of("../../evil", "..", "a/b", "A-B", "x", "a\\b")) {
            assertThat(m.materializePersonaSkill(workspace, new WorkerJob.Expertise(slug, "n", "BACKEND", "skill"))).isNull();
        }
        assertThat(workspace.resolve(".claude/skills")).doesNotExist();
        assertThat(root.resolve("evil")).doesNotExist();
    }

    @Test
    void repo_owned_file_at_the_same_path_wins_but_generated_file_is_refreshed() throws IOException {
        HarnessMaterializer m = materializerWith(bundleDir, List.of());
        Path file = workspace.resolve(".claude/skills/persona-jiho/SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "repo skill");

        assertThat(m.materializePersonaSkill(workspace, JIHO)).isNull();
        assertThat(Files.readString(file)).isEqualTo("repo skill");

        Files.delete(file);
        m.materializePersonaSkill(workspace, JIHO);
        m.materializePersonaSkill(workspace, new WorkerJob.Expertise("jiho", "지호", "BACKEND", "새 스킬"));
        assertThat(Files.readString(file)).endsWith("새 스킬\n");
    }

    @Test
    void git_clone_excludes_the_generated_skill_dir_once() throws IOException {
        Files.createDirectories(workspace.resolve(".git/info"));
        Files.writeString(workspace.resolve(".git/info/exclude"), "# git ls-files --others --exclude-from=.git/info/exclude");
        HarnessMaterializer m = materializerWith(bundleDir, List.of());

        m.materializePersonaSkill(workspace, JIHO);
        m.materializePersonaSkill(workspace, JIHO);

        assertThat(Files.readString(workspace.resolve(".git/info/exclude")))
                .isEqualTo("# git ls-files --others --exclude-from=.git/info/exclude\n/.claude/skills/persona-jiho/\n");
    }
}