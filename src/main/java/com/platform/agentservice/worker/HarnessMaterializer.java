package com.platform.agentservice.worker;

import com.platform.agentservice.persona.PersonaSkills;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 워커 워크스페이스에 하네스(에이전트/스킬 정의 + 루트 규약 문서)를 "실체화"한다(P2a T3).
 * clone 직후의 워크스페이스는 대상 리포의 원본 상태이므로, 헤드리스 {@code claude -p}가
 * 플랫폼 규약(CLAUDE.md·AGENTS.md·`.claude/agents`·`.claude/skills`)을 보게 하려면 여기서
 * 복사해 넣어야 한다.
 *
 * <p><b>리포가 이긴다</b>: 대상 리포 자체가 이미 같은 이름의 파일/디렉터리를 갖고 있으면
 * 절대 덮어쓰지 않는다 — 각 서비스 리포의 `CLAUDE.md`/`.claude/`가 그 리포의 최신·정확한
 * 규약이고, 플랫폼 루트 번들은 "리포에 없을 때의 기본값"일 뿐이다.
 *
 * <p><b>개인 로컬 설정 제외(AGP-51)</b>: 번들 어디에 있든 {@code settings.local.json}은 복사하지 않는다 —
 * 운영자 개인의 권한 허용 목록·env·MCP 설정이 담기는 파일이라, 워커 워크스페이스에 실리면 워커가 운영자의
 * 로컬 권한으로 돌거나 워커 커밋에 섞여 나갈 수 있다. 공유 규약은 {@code settings.json}에 둔다.
 */
@Slf4j
@Component
public class HarnessMaterializer {

    static final String LOCAL_SETTINGS_FILE = "settings.local.json";

    private final WorkerProperties properties;

    public HarnessMaterializer(WorkerProperties properties) {
        this.properties = properties;
    }

    /** 실제로 복사한 경로, 리포가 이겨서 건너뛴 경로, 번들 소스 자체가 없었는지를 담는다(호출자 로그/보고서용). */
    public record MaterializeResult(List<String> copied, List<String> skippedExisting, boolean bundleSourceMissing) {
    }

    public MaterializeResult materialize(Path workspace) {
        List<String> copied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        Path bundleSource = Paths.get(properties.harnessBundlePath());
        boolean bundleMissing = !Files.isDirectory(bundleSource);
        if (!bundleMissing) {
            copyTreeNoOverwrite(bundleSource, workspace.resolve(".claude"), copied, skipped);
        }

        for (String rootFile : properties.harnessRootFiles()) {
            copyRootFileNoOverwrite(Paths.get(rootFile), workspace, copied, skipped);
        }

        return new MaterializeResult(copied, skipped, bundleMissing);
    }

    /**
     * run 페르소나의 스킬(AGP-62)을 {@code .claude/skills/persona-<slug>/SKILL.md}(Claude Code 스킬 형식 — frontmatter name/description)로
     * 쓴다. 스킬이 없으면 아무것도 만들지 않는다. 경로 탈출은 두 겹으로 막는다: slug 문자 규칙({@link PersonaSkills#dirName}) +
     * 정규화한 대상이 {@code .claude/skills} 아래인지 확인. 생성물 표식이 없는 같은 경로 파일은 리포 소유로 보고 덮지 않는다(리포가 이긴다) —
     * 표식이 있으면 페르소나 편집을 반영하도록 다시 쓴다(계보 run이 같은 워크스페이스를 이어 쓸 때). git 클론이면 {@code .git/info/exclude}에
     * 생성 디렉터리를 올려 워커의 {@code git add -A}에 실려 나가지 않게 한다.
     *
     * @return 쓴 SKILL.md 경로, 쓰지 않았으면 null
     */
    public Path materializePersonaSkill(Path workspace, WorkerJob.Expertise expertise) {
        if (expertise == null || !PersonaSkills.hasSkills(expertise.skills())) {
            return null;
        }
        String dirName = PersonaSkills.dirName(expertise.slug());
        if (dirName == null) {
            log.warn("페르소나 스킬 실체화 건너뜀 — slug가 경로 조각 규칙에 맞지 않습니다");
            return null;
        }
        Path skillsRoot = workspace.resolve(".claude").resolve("skills").toAbsolutePath().normalize();
        Path dir = skillsRoot.resolve(dirName).normalize();
        if (!dir.startsWith(skillsRoot) || dir.equals(skillsRoot)) {
            log.warn("페르소나 스킬 실체화 건너뜀 — 대상 경로가 스킬 디렉터리 밖입니다");
            return null;
        }
        Path file = dir.resolve(SKILL_FILE);
        try {
            if (Files.exists(file) && !Files.readString(file, StandardCharsets.UTF_8).contains(GENERATED_MARKER)) {
                log.info("페르소나 스킬 실체화 건너뜀 — 리포가 같은 경로를 이미 갖고 있습니다: {}", file);
                return null;
            }
            Files.createDirectories(dir);
            Files.writeString(file, skillMarkdown(dirName, expertise), StandardCharsets.UTF_8);
            excludeFromGit(workspace, dirName);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("페르소나 스킬 실체화 실패: " + file, e);
        }
    }

    static final String SKILL_FILE = "SKILL.md";
    static final String GENERATED_MARKER = "<!-- agent-service:persona-skill -->";

    static String skillMarkdown(String dirName, WorkerJob.Expertise e) {
        String summary = PersonaSkills.summary(e.skills());
        String description = e.name() + "(" + e.role() + ")의 전문성과 작업 방식"
                + (summary == null ? "" : " — " + summary)
                + ". 이 페르소나로 작업·리뷰·회의할 때 참고한다.";
        return "---\n"
                + "name: " + dirName + "\n"
                + "description: " + yamlQuoted(description) + "\n"
                + "---\n\n"
                + GENERATED_MARKER + "\n"
                + "<!-- 페르소나 설정(AI 사무실 직원 편집)에서 생성된 파일 — 여기서 고치지 말 것(다음 run에서 다시 쓴다). -->\n\n"
                + e.skills().strip() + "\n";
    }

    /** YAML 큰따옴표 한 줄 스칼라 — 이름·요약 속 콜론·따옴표·백슬래시·줄바꿈이 frontmatter를 깨지 않게. */
    private static String yamlQuoted(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return "\"" + oneLine.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void excludeFromGit(Path workspace, String dirName) throws IOException {
        Path gitDir = workspace.resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            return; // 회의 워크스페이스 등 git 저장소가 아니다
        }
        Path exclude = gitDir.resolve("info").resolve("exclude");
        String line = "/.claude/skills/" + dirName + "/";
        String current = Files.exists(exclude) ? Files.readString(exclude, StandardCharsets.UTF_8) : "";
        if (current.lines().anyMatch(line::equals)) {
            return;
        }
        Files.createDirectories(exclude.getParent());
        String prefix = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
        Files.writeString(exclude, current + prefix + line + "\n", StandardCharsets.UTF_8);
    }

    private void copyRootFileNoOverwrite(Path source, Path workspace, List<String> copied, List<String> skipped) {
        if (!Files.isRegularFile(source)) {
            return; // 소스 자체가 없음 — 조용히 건너뛴다(no-op)
        }
        Path dest = workspace.resolve(source.getFileName());
        if (Files.exists(dest)) {
            skipped.add(dest.toString());
            return;
        }
        try {
            Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES);
            copied.add(dest.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("하네스 루트 파일 복사 실패: " + source, e);
        }
    }

    private void copyTreeNoOverwrite(Path sourceRoot, Path destRoot, List<String> copied, List<String> skipped) {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            walk.forEach(source -> copyEntryNoOverwrite(sourceRoot, destRoot, source, copied, skipped));
        } catch (IOException e) {
            throw new UncheckedIOException("하네스 번들 탐색 실패: " + sourceRoot, e);
        }
    }

    private void copyEntryNoOverwrite(Path sourceRoot, Path destRoot, Path source, List<String> copied, List<String> skipped) {
        Path relative = sourceRoot.relativize(source);
        Path dest = destRoot.resolve(relative);
        try {
            if (Files.isDirectory(source)) {
                Files.createDirectories(dest);
                return;
            }
            if (LOCAL_SETTINGS_FILE.equals(source.getFileName().toString())) {
                return;
            }
            if (Files.exists(dest)) {
                skipped.add(dest.toString());
                return;
            }
            Files.createDirectories(dest.getParent());
            Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES);
            copied.add(dest.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("하네스 번들 항목 복사 실패: " + source, e);
        }
    }
}
