package com.platform.agentservice.runner;

import com.platform.agentservice.worker.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 러너용 하네스 zip — 개인 로컬 설정·워크트리 제외, 루트 파일 포함, 내용이 같으면 해시가 같다. */
class HarnessBundleServiceTest {

    @TempDir Path dir;

    @Test
    void bundle_contains_agents_skills_and_root_files_but_never_local_settings_or_worktrees() throws IOException {
        Path bundle = dir.resolve(".claude");
        Files.createDirectories(bundle.resolve("agents"));
        Files.createDirectories(bundle.resolve("skills/x"));
        Files.createDirectories(bundle.resolve("worktrees/agent-1"));
        Files.writeString(bundle.resolve("agents/backend.md"), "backend");
        Files.writeString(bundle.resolve("skills/x/SKILL.md"), "skill");
        Files.writeString(bundle.resolve("settings.local.json"), "{\"secret\":1}");
        Files.writeString(bundle.resolve("skills/x/settings.local.json"), "{}");
        Files.writeString(bundle.resolve("worktrees/agent-1/README.md"), "checkout");
        Path claudeMd = dir.resolve("CLAUDE.md");
        Files.writeString(claudeMd, "root rules");
        WorkerProperties props = new WorkerProperties(dir.toString(), bundle.toString(), List.of(claudeMd.toString()), "claude",
                80, 40, "Read", "x", Map.of(), List.of(), false);

        HarnessBundleService.Bundle first = new HarnessBundleService(props).current();
        HarnessBundleService.Bundle second = new HarnessBundleService(props).current();

        assertThat(entries(first.zip())).containsExactly(".claude/agents/backend.md", ".claude/skills/x/SKILL.md", "CLAUDE.md");
        assertThat(first.sha256()).isEqualTo(second.sha256()).hasSize(64);
    }

    private static List<String> entries(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }
}
