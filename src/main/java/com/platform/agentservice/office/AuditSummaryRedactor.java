package com.platform.agentservice.office;

/**
 * 감사 summary를 사무실 화면(인증 사용자 누구나)에 내보내기 전에 자유 본문을 걷어낸다. 사무실은 프로젝트 권한을 보지
 * 않으므로, 권한 제한 프로젝트의 코멘트 본문이 여기로 새면 안 된다. 제목·키·id 같은 메타데이터만 남긴다.
 * 적재 시점이 아니라 노출 시점에 거르는 이유: 감사 원본은 운영자 추적용으로 그대로 보존해야 한다.
 */
final class AuditSummaryRedactor {

    static final String REDACTED = "(본문 생략)";

    private AuditSummaryRedactor() {
    }

    static String redact(String tool, String summary) {
        if (summary == null) {
            return null;
        }
        if ("add_comment".equals(tool) || tool.endsWith(".comment")) {
            // "{issueKey}: {코멘트 본문 | 오류 메시지}" — 오류 메시지도 다운스트림 응답 본문을 실을 수 있다.
            int sep = summary.indexOf(": ");
            return (sep < 0 ? summary : summary.substring(0, sep)) + " " + REDACTED;
        }
        if ("report_progress".equals(tool)) {
            // "run={id} {진행 메시지}" — 메시지는 이슈 코멘트로 그대로 올라가는 본문이다.
            int sep = summary.indexOf(' ');
            return (sep < 0 ? summary : summary.substring(0, sep)) + " " + REDACTED;
        }
        return summary;
    }
}
