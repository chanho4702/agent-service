package com.platform.agentservice.worker;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 */
@Component
public class HarnessMaterializer {

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
