package com.platform.agentservice.worker;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 워커 워크스페이스에 남은 로컬 커밋(아직 원격에 없는)에서 ALM 이슈 키를 찾아 커밋 링크로
 * 추출한다(P2a T6b) — {@code RunService}가 run 종결 시 이 결과로 {@code AlmClient.addWebLink}
 * (kind=COMMIT)를 호출해 이슈에 연결한다. 이 클래스 자신은 ALM을 호출하지 않는다(순수 git
 * 파싱만) — 이슈 키 해석·웹링크 등록은 호출자 책임.
 *
 * <p><b>범위 고정(브리핑 확정)</b>: {@code origin/main..HEAD} 고정 — 워커가 clone한 기본
 * 브랜치가 항상 main이라는 전제이며, upstream 추적 브랜치({@code @{u}}) 폴백은 두지 않는다.
 * {@code origin/main} 참조가 없거나(예: 클론 실패로 워크스페이스가 비정상) git 명령이 실패하면
 * 예외를 던지지 않고 빈 리스트를 돌려준다 — best-effort 기능이라 실패가 run 처리 흐름을 막으면
 * 안 된다.
 */
@Component
public class CommitLinkParser {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    /** 예: AGP-8, PROJ-123. 접두사 1~12자(영문 대문자/숫자/언더스코어, 첫 글자는 영문)+하이픈+숫자. */
    private static final Pattern ISSUE_KEY_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9_]{1,11}-\\d+)\\b");

    private final CommandExecutor commandExecutor;

    public CommitLinkParser(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    /** 커밋 한 건에서 뽑아낸 이슈 연결 정보. {@code url}은 원격 URL을 정규화하지 못하면 null일 수 있다. */
    public record CommitLink(String issueKey, String sha, String subject, String url) {
    }

    public List<CommitLink> parse(Path workspace) {
        List<CommitLink> links = new ArrayList<>();
        if (workspace == null || !Files.isDirectory(workspace)) {
            return links;
        }

        String canonicalRepoUrl = canonicalizeRepoUrl(remoteUrl(workspace));

        CommandExecutor.ExecResult log = safeExec(workspace,
                List.of("git", "log", "--format=%H%x09%s", "origin/main..HEAD"));
        if (log == null || log.timedOut() || log.exitCode() != 0 || log.stdout() == null) {
            return links;
        }

        for (String line : log.stdout().split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String sha = line.substring(0, tab).trim();
            String subject = line.substring(tab + 1).trim();
            Matcher matcher = ISSUE_KEY_PATTERN.matcher(subject);
            if (!matcher.find()) {
                continue;
            }
            String issueKey = matcher.group(1);
            String url = canonicalRepoUrl != null ? canonicalRepoUrl + "/commit/" + sha : null;
            links.add(new CommitLink(issueKey, sha, subject, url));
        }
        return links;
    }

    private String remoteUrl(Path workspace) {
        CommandExecutor.ExecResult result = safeExec(workspace,
                List.of("git", "config", "--get", "remote.origin.url"));
        if (result == null || result.timedOut() || result.exitCode() != 0 || result.stdout() == null) {
            return null;
        }
        String trimmed = result.stdout().trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private CommandExecutor.ExecResult safeExec(Path workspace, List<String> command) {
        try {
            return commandExecutor.exec(command, workspace, Map.of(), GIT_TIMEOUT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 원격 URL을 커밋 링크 조립용으로 정규화한다: {@code git@host:owner/repo.git} 형태를
     * {@code https://host/owner/repo}로 바꾸고, {@code .git} 접미사와 끝의 {@code /}를 제거하고,
     * host를 소문자로 낮춘다. alm-backend의 웹링크 중복 판정이 URL 정확 일치(exact-string)라서
     * (핸드오프 메모, 커밋 파서 재실행 시 dedup이 되려면) 같은 리포는 항상 같은 문자열로
     * 조립돼야 한다. 정규화할 수 없으면 null.
     */
    static String canonicalizeRepoUrl(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }
        String url = remoteUrl.trim();
        if (url.startsWith("git@")) {
            String rest = url.substring("git@".length());
            int colon = rest.indexOf(':');
            if (colon < 0) {
                return null;
            }
            url = "https://" + rest.substring(0, colon) + "/" + rest.substring(colon + 1);
        }
        // 트레일링 슬래시를 먼저 걷어내야 ".git/" 형태(clone URL 뒤에 슬래시가 붙는 경우)도
        // ".git" 접미사로 인식된다 — 순서를 바꾸면 "o/r.git/" 같은 입력에서 .git이 안 걸린다.
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - ".git".length());
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT) + path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
