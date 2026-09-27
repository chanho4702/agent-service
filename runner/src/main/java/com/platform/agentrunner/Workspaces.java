package com.platform.agentrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 워크스페이스 보존·정리. {@code WorkerExecution}은 {@code <work>/workspaces/run-<id>}에 clone하고 형제 {@code run-<id>-cfg}에
 * mcp-config(run 토큰)를 뒀다가 지운다. TASK 워크스페이스는 run이 끝나도 지우지 않는다 — REVIEW·반려-fix가 이 러너에 고정돼 같은
 * 경로(서버가 보관한 {@code workspacePath})를 이어 쓰기 때문이다(워커 커밋은 푸시되지 않고 여기에만 있다).
 *
 * <p>보존: 마지막 사용(시작·종료 때 디렉터리 시각을 갱신)부터 N일이 지난 워크스페이스를 지운다. 진행 중 run이 쓰는 경로는 건드리지 않는다.
 * {@code -cfg} 디렉터리는 비정상 종료로 남았을 때만 보이며 run 토큰이 들어 있으므로 시작할 때 전부, 이후에는 하루 지난 것을 지운다.
 */
final class Workspaces {

    private static final Logger log = LoggerFactory.getLogger(Workspaces.class);
    static final String CFG_SUFFIX = "-cfg";

    private Workspaces() {
    }

    /**
     * @param inUse 진행 중 run이 쓰는(또는 쓸) 워크스페이스 절대 경로
     * @param startup true면 남은 {@code -cfg}를 나이와 무관하게 지운다(진행 중 run이 없을 때만 부른다)
     * @return 지운 디렉터리 수
     */
    static int sweep(Path workspacesDir, int retentionDays, Set<Path> inUse, boolean startup, Instant now) {
        if (!Files.isDirectory(workspacesDir)) {
            return 0;
        }
        List<Path> dirs;
        try (Stream<Path> list = Files.list(workspacesDir)) {
            dirs = list.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().startsWith("run-"))
                    .toList();
        } catch (IOException e) {
            log.warn("워크스페이스 목록 실패: {}", e.getMessage());
            return 0;
        }
        int removed = 0;
        for (Path dir : dirs) {
            Path abs = dir.toAbsolutePath().normalize();
            if (inUse.contains(abs)) {
                continue;
            }
            boolean cfg = abs.getFileName().toString().endsWith(CFG_SUFFIX);
            Duration age = Duration.between(mtime(abs).toInstant(), now);
            boolean expired = cfg
                    ? (startup || age.compareTo(Duration.ofDays(1)) > 0)
                    : (retentionDays > 0 && age.compareTo(Duration.ofDays(retentionDays)) > 0);
            if (expired && deleteTree(abs)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("오래된 워크스페이스 {}개 정리(보존 {}일)", removed, retentionDays);
        }
        return removed;
    }

    /** 사용 시각 갱신 — 보존 기준. */
    static void touch(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            if (Files.isDirectory(dir)) {
                Files.setLastModifiedTime(dir, FileTime.from(Instant.now()));
            }
        } catch (IOException ignored) {
            // 보존 기간 계산에만 쓰인다
        }
    }

    /**
     * 재귀 삭제. Windows에서 git 객체 파일은 읽기 전용이라 그냥 지우면 {@link AccessDeniedException} — 속성을 풀고 다시 지운다. 심볼릭
     * 링크는 따라가지 않는다(링크 자체만 지운다).
     *
     * @return 다 지웠으면 true
     */
    static boolean deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        boolean ok = true;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                ok &= deleteOne(p);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            log.warn("디렉터리 삭제 실패: {} — {}", dir, e.getMessage());
            return false;
        }
        return ok;
    }

    private static boolean deleteOne(Path p) {
        try {
            Files.deleteIfExists(p);
            return true;
        } catch (AccessDeniedException e) {
            try {
                DosFileAttributeView dos = Files.getFileAttributeView(p, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (dos != null) {
                    dos.setReadOnly(false);
                } else {
                    p.toFile().setWritable(true);
                }
                Files.deleteIfExists(p);
                return true;
            } catch (IOException retry) {
                log.warn("삭제 실패: {} — {}", p, retry.getMessage());
                return false;
            }
        } catch (IOException e) {
            log.warn("삭제 실패: {} — {}", p, e.getMessage());
            return false;
        }
    }

    private static FileTime mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return FileTime.from(Instant.now());
        }
    }
}
