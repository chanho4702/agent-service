package com.platform.agentservice.office;

import org.junit.jupiter.api.Test;

import static com.platform.agentservice.office.AuditSummaryRedactor.BODY_REDACTED;
import static com.platform.agentservice.office.AuditSummaryRedactor.LINK_REDACTED;
import static com.platform.agentservice.office.AuditSummaryRedactor.QUERY_REDACTED;
import static com.platform.agentservice.office.AuditSummaryRedactor.TITLE_REDACTED;
import static com.platform.agentservice.office.AuditSummaryRedactor.redact;
import static org.assertj.core.api.Assertions.assertThat;

/** 입력 summary는 각 도구(IssueTools·WikiTools·RunTools)가 실제로 만드는 형식 그대로다. */
class AuditSummaryRedactorTest {

    @Test
    void 코멘트_본문은_이슈키만_남긴다() {
        assertThat(redact("add_comment", "AGP-1: 본문: 콜론 포함")).isEqualTo("AGP-1 " + BODY_REDACTED);
    }

    @Test
    void 코멘트_실패_노트의_오류_메시지도_가린다() {
        assertThat(redact("report_result.comment", "AGP-1: 500 {\"detail\":\"...\"}")).isEqualTo("AGP-1 " + BODY_REDACTED);
    }

    @Test
    void 진행_메시지는_run_id만_남긴다() {
        assertThat(redact("report_progress", "run=7 테스트 작성 중")).isEqualTo("run=7 " + BODY_REDACTED);
    }

    @Test
    void PR_링크는_이슈키만_남긴다() {
        assertThat(redact("link_pr", "AGP-1: https://git.internal.example/secret-org/secret-repo/pull/7"))
                .isEqualTo("AGP-1 " + LINK_REDACTED);
    }

    @Test
    void 제목은_식별자만_남긴다() {
        assertThat(redact("create_issue", "projectId=3 title=비밀 프로젝트 이슈")).isEqualTo("projectId=3 " + TITLE_REDACTED);
        assertThat(redact("create_page", "spaceId=2 title=인사 평가")).isEqualTo("spaceId=2 " + TITLE_REDACTED);
        assertThat(redact("update_page", "pageId=41 title=연봉 테이블")).isEqualTo("pageId=41 " + TITLE_REDACTED);
    }

    @Test
    void 검색어는_식별자만_남긴다() {
        assertThat(redact("search_issues", "projectId=3 text=해고 assignee=jiho page=0"))
                .isEqualTo("projectId=3 " + QUERY_REDACTED);
        assertThat(redact("find_pages", "spaceId=2 query=M&A")).isEqualTo("spaceId=2 " + QUERY_REDACTED);
    }

    @Test
    void 식별자만_담는_도구는_그대로다() {
        assertThat(redact("update_issue_status", "AGP-1 -> done")).isEqualTo("AGP-1 -> done");
        assertThat(redact("get_page", "pageId=41")).isEqualTo("pageId=41");
        assertThat(redact("claim_issue", "claim AGP-1")).isEqualTo("claim AGP-1");
    }

    @Test
    void null은_null() {
        assertThat(redact("add_comment", null)).isNull();
    }
}
