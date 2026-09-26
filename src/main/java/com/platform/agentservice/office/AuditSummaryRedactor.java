package com.platform.agentservice.office;

import java.util.Map;

/**
 * 감사 summary를 사무실 화면(인증 사용자 누구나)에 내보내기 전에 실질 내용을 걷어낸다. 사무실은 ALM 프로젝트·위키
 * 스페이스 권한을 보지 않으므로 코멘트 본문뿐 아니라 제목·검색어가 새도 그 권한을 우회하는 표면이 된다. 말풍선·활동
 * 로그는 도구명과 식별자(이슈키·run·프로젝트/스페이스/페이지 id)로 충분하다.
 * 적재 시점이 아니라 노출 시점에 거르는 이유: 감사 원본은 운영자 추적용으로 그대로 보존해야 한다.
 */
final class AuditSummaryRedactor {

    static final String BODY_REDACTED = "(본문 생략)";
    static final String TITLE_REDACTED = "(제목 생략)";
    static final String QUERY_REDACTED = "(검색어 생략)";
    static final String LINK_REDACTED = "(링크 생략)";

    /** summary 첫 토큰이 식별자인 도구 — "run={id} 메시지", "projectId={id} title=…", "spaceId={id} query=…" 등. */
    private static final Map<String, String> FIRST_TOKEN_TOOLS = Map.of(
            "report_progress", BODY_REDACTED,
            "create_issue", TITLE_REDACTED,
            "create_page", TITLE_REDACTED,
            "update_page", TITLE_REDACTED,
            "search_issues", QUERY_REDACTED,
            "find_pages", QUERY_REDACTED);

    private AuditSummaryRedactor() {
    }

    static String redact(String tool, String summary) {
        if (summary == null) {
            return null;
        }
        if ("add_comment".equals(tool) || tool.endsWith(".comment")) {
            // "{issueKey}: {코멘트 본문 | 오류 메시지}" — 오류 메시지도 다운스트림 응답 본문을 실을 수 있다.
            return prefixBefore(summary, ": ") + " " + BODY_REDACTED;
        }
        if ("link_pr".equals(tool)) {
            // "{issueKey}: {PR URL}" — 온프렘 비공개 리포면 URL의 조직·리포명 자체가 노출 대상이다.
            return prefixBefore(summary, ": ") + " " + LINK_REDACTED;
        }
        String label = FIRST_TOKEN_TOOLS.get(tool);
        if (label != null) {
            return prefixBefore(summary, " ") + " " + label;
        }
        return summary;
    }

    private static String prefixBefore(String summary, String separator) {
        int sep = summary.indexOf(separator);
        return sep < 0 ? summary : summary.substring(0, sep);
    }
}
