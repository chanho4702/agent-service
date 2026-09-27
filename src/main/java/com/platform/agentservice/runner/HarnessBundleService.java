package com.platform.agentservice.runner;

import com.platform.agentservice.worker.HarnessMaterializer;
import com.platform.agentservice.worker.WorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 러너에 내려줄 하네스 번들 zip(P4a) — 서버의 {@code worker.harness-bundle-path}(.claude 번들)와 {@code harness-root-files}
 * (CLAUDE.md·AGENTS.md)를 러너가 그대로 {@code HarnessMaterializer}에 먹일 수 있는 모양으로 묶는다: {@code .claude/...} 항목 + 루트 파일.
 * {@code settings.local.json}(운영자 개인 권한·env·MCP 설정)은 어느 깊이에 있든 싣지 않는다 — 워크스페이스 실체화와 같은 제외 규칙(AGP-51).
 *
 * <p>번들은 운영 중 바뀔 수 있어 요청 때 다시 만들되 60초 캐시한다(claim마다 해시를 싣기 때문). 러너는 해시로 캐시하고 바뀌었을 때만
 * 내려받는다. 항목 순서를 고정하고 타임스탬프를 0으로 둬 내용이 같으면 해시가 같다.
 */
@Slf4j
@Service
public class HarnessBundleService {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final long FIXED_ENTRY_TIME = 0L;
    /** 메모리에 통째로 만드는 zip이라 상한을 둔다 — 번들 경로를 잘못 잡아 리포 체크아웃째 묶는 사고를 막는다. */
    static final long MAX_BUNDLE_BYTES = 64L * 1024 * 1024;
    /**
     * 번들 최상위에서 하네스가 아닌 디렉터리 — Claude Code가 {@code .claude/worktrees/}에 리포 체크아웃(워크트리)을 만든다. 러너 PC로
     * 내보낼 이유가 없고 크기가 GB 단위일 수 있다.
     */
    static final java.util.Set<String> EXCLUDED_TOP_DIRS = java.util.Set.of("worktrees");

    private final WorkerProperties properties;
    private final Clock clock;
    private volatile Bundle cached;

    @Autowired
    public HarnessBundleService(WorkerProperties properties) {
        this(properties, Clock.systemUTC());
    }

    HarnessBundleService(WorkerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** zip 바이트와 SHA-256(hex). */
    public record Bundle(byte[] zip, String sha256, Instant builtAt) {
    }

    public Bundle current() {
        Bundle b = cached;
        Instant now = clock.instant();
        if (b != null && b.builtAt().plus(CACHE_TTL).isAfter(now)) {
            return b;
        }
        Bundle fresh = build(now);
        cached = fresh;
        return fresh;
    }

    private Bundle build(Instant now) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            String bundlePath = properties.harnessBundlePath();
            if (bundlePath != null && !bundlePath.isBlank()) {
                Path root = Paths.get(bundlePath);
                if (Files.isDirectory(root)) {
                    List<Path> files = new java.util.ArrayList<>();
                    List<Path> top;
                    try (Stream<Path> list = Files.list(root)) {
                        // 제외 디렉터리는 순회 자체를 하지 않는다(워크트리는 수만 개 파일일 수 있다).
                        top = list.filter(p -> !EXCLUDED_TOP_DIRS.contains(p.getFileName().toString())).toList();
                    }
                    for (Path entry : top) {
                        try (Stream<Path> walk = Files.walk(entry)) {
                            walk.filter(Files::isRegularFile)
                                    .filter(p -> !HarnessMaterializer.LOCAL_SETTINGS_FILE.equals(p.getFileName().toString()))
                                    .forEach(files::add);
                        }
                    }
                    files.sort(null);
                    for (Path file : files) {
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        put(zip, ".claude/" + relative, file);
                        if (out.size() > MAX_BUNDLE_BYTES) {
                            throw new IllegalStateException("하네스 번들이 " + (MAX_BUNDLE_BYTES / (1024 * 1024))
                                    + "MB를 넘습니다 — worker.harness-bundle-path에 에이전트·스킬 정의만 두세요: " + root);
                        }
                    }
                }
            }
            List<String> rootFiles = properties.harnessRootFiles() == null ? List.of() : properties.harnessRootFiles();
            for (String rootFile : rootFiles) {
                Path file = Paths.get(rootFile);
                if (Files.isRegularFile(file)) {
                    put(zip, file.getFileName().toString(), file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("하네스 번들 zip 생성 실패", e);
        }
        byte[] bytes = out.toByteArray();
        return new Bundle(bytes, sha256(bytes), now);
    }

    private static void put(ZipOutputStream zip, String name, Path file) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_ENTRY_TIME);
        zip.putNextEntry(entry);
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }
}
