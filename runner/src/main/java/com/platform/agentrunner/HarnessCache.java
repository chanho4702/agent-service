package com.platform.agentrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 하네스 번들 캐시 — 서버 {@code GET /api/agent/runners/harness}의 zip({@code .claude/…} + 루트 CLAUDE.md·AGENTS.md)을 SHA-256 이름
 * 디렉터리({@code <work>/harness/<sha>/})에 풀어 둔다. claim의 {@code harness.sha256}이 이미 풀어 둔 것과 같으면 내려받지 않고, 다르면
 * {@code If-None-Match}로 받는다. 최근 {@value #KEEP}개만 남긴다.
 *
 * <p>압축 해제는 zip-slip을 막는다: 절대 경로·드라이브 문자·{@code ..} 조각·정규화 뒤 대상 밖을 가리키는 항목이 하나라도 있으면 번들 전체를
 * 거부한다(부분 해제도 남기지 않는다 — 임시 디렉터리에 풀고 다 끝나야 옮긴다). 항목 수·총 크기 상한도 둔다(압축 폭탄).
 */
class HarnessCache {

    private static final Logger log = LoggerFactory.getLogger(HarnessCache.class);

    static final int KEEP = 3;
    static final int MAX_ENTRIES = 20_000;
    static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final String COMPLETE_MARKER = ".complete";

    /** 풀어 둔 하네스 — {@code bundle()}은 {@code HarnessMaterializer}의 번들 경로, {@code rootFiles()}는 루트 파일 절대 경로. */
    record Harness(String sha256, Path dir) {
        Path bundle() {
            return dir.resolve(".claude");
        }

        List<String> rootFiles() {
            try (Stream<Path> list = Files.list(dir)) {
                return list.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().equals(COMPLETE_MARKER))
                        .sorted()
                        .map(p -> p.toAbsolutePath().toString())
                        .toList();
            } catch (IOException e) {
                throw new UncheckedIOException("하네스 루트 파일 목록 실패: " + dir, e);
            }
        }
    }

    private final Path root;
    private final RunnerApi api;
    private Harness current;
    int downloads;

    HarnessCache(Path root, RunnerApi api) {
        this.root = root;
        this.api = api;
    }

    /**
     * {@code expectedSha}(claim 응답 값)에 맞는 하네스를 돌려준다. 내려받기가 실패하면 풀어 둔 가장 최근 하네스로 대신한다(warn) — 그것도
     * 없으면 던진다(호출자가 run을 실패로 보고한다).
     */
    synchronized Harness ensure(String expectedSha) throws InterruptedException {
        if (expectedSha != null) {
            Harness cached = completed(expectedSha);
            if (cached != null) {
                current = cached;
                touch(cached.dir());
                return cached;
            }
        }
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return download();
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                log.warn("하네스 내려받기 실패({}/3): {}", attempt, e.getMessage());
                Thread.sleep(1000L * attempt);
            }
        }
        Harness fallback = current != null ? current : newestCompleted();
        if (fallback != null) {
            log.warn("하네스를 새로 받지 못해 캐시된 번들({})로 실행합니다", shortSha(fallback.sha256()));
            return fallback;
        }
        throw new IllegalStateException("하네스 번들을 내려받지 못했습니다: " + (last == null ? "알 수 없음" : last.getMessage()));
    }

    private Harness download() throws Exception {
        String known = current != null ? current.sha256() : null;
        RunnerApi.HarnessDownload res = api.harness(known);
        downloads++;
        if (res.notModified() && current != null) {
            touch(current.dir());
            return current;
        }
        if (res.zip() == null) {
            throw new IllegalStateException("하네스 응답이 비었습니다");
        }
        String sha = sha256(res.zip());
        if (res.etag() != null && !res.etag().equalsIgnoreCase(sha)) {
            // 전송 중 손상 — 서버 ETag는 zip 바이트의 SHA-256이다
            throw new IllegalStateException("하네스 해시가 ETag와 다릅니다");
        }
        Harness existing = completed(sha);
        if (existing == null) {
            Files.createDirectories(root);
            Path tmp = Files.createTempDirectory(root, "tmp-");
            try {
                extract(res.zip(), tmp);
                Files.writeString(tmp.resolve(COMPLETE_MARKER), sha);
                Path target = root.resolve(sha);
                deleteTree(target);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                deleteTree(tmp);
            }
            existing = completed(sha);
            log.info("하네스 번들 갱신 — {}", shortSha(sha));
        }
        current = existing;
        prune();
        return existing;
    }

    private Harness completed(String sha) {
        if (!sha.matches("[0-9a-fA-F]{64}")) {
            return null;
        }
        Path dir = root.resolve(sha.toLowerCase());
        return Files.isRegularFile(dir.resolve(COMPLETE_MARKER)) ? new Harness(sha.toLowerCase(), dir) : null;
    }

    private Harness newestCompleted() {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(p -> Files.isRegularFile(p.resolve(COMPLETE_MARKER)))
                    .max(Comparator.comparing(HarnessCache::mtime))
                    .map(p -> new Harness(p.getFileName().toString(), p))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void prune() {
        try (Stream<Path> list = Files.list(root)) {
            List<Path> dirs = new ArrayList<>(list.filter(Files::isDirectory).toList());
            dirs.sort(Comparator.comparing(HarnessCache::mtime).reversed());
            for (int i = 0; i < dirs.size(); i++) {
                Path d = dirs.get(i);
                boolean keep = i < KEEP || (current != null && d.equals(current.dir()));
                if (!keep || d.getFileName().toString().startsWith("tmp-")) {
                    deleteTree(d);
                }
            }
        } catch (IOException e) {
            log.debug("하네스 캐시 정리 실패: {}", e.getMessage());
        }
    }

    /** zip을 {@code target} 아래에 푼다 — 대상 밖을 가리키는 항목이 하나라도 있으면 {@link IllegalArgumentException}. */
    static void extract(byte[] zip, Path target) throws IOException {
        Path base = target.toAbsolutePath().normalize();
        int entries = 0;
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("하네스 항목이 너무 많습니다");
                }
                Path dest = safeResolve(base, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (OutputStream out = Files.newOutputStream(dest)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_TOTAL_BYTES) {
                            throw new IllegalArgumentException("하네스 번들이 너무 큽니다");
                        }
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    static Path safeResolve(Path base, String name) {
        String n = name.replace('\\', '/');
        if (n.isEmpty() || n.startsWith("/") || n.matches("^[A-Za-z]:.*") || n.contains("\0")) {
            throw new IllegalArgumentException("하네스 항목 경로 거부: " + name);
        }
        for (String part : n.split("/")) {
            if (part.equals("..")) {
                throw new IllegalArgumentException("하네스 항목 경로 거부: " + name);
            }
        }
        Path dest = base.resolve(n).normalize();
        if (!dest.startsWith(base) || dest.equals(base)) {
            throw new IllegalArgumentException("하네스 항목 경로 거부: " + name);
        }
        return dest;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(12, sha.length()));
    }

    private static FileTime mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static void touch(Path dir) {
        try {
            Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // 정리 순서에만 쓰인다
        }
    }

    static void deleteTree(Path dir) {
        Workspaces.deleteTree(dir);
    }
}
