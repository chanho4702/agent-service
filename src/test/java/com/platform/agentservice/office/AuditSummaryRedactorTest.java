package com.platform.agentservice.office;

import org.junit.jupiter.api.Test;

import static com.platform.agentservice.office.AuditSummaryRedactor.REDACTED;
import static com.platform.agentservice.office.AuditSummaryRedactor.redact;
import static org.assertj.core.api.Assertions.assertThat;

class AuditSummaryRedactorTest {

    @Test
    void 코멘트_본문은_이슈키만_남긴다() {
        assertThat(redact("add_comment", "AGP-1: 본문: 콜론 포함")).isEqualTo("AGP-1 " + REDACTED);
    }

    @Test
    void 코멘트_실패_노트의_오류_메시지도_가린다() {
        assertThat(redact("report_result.comment", "AGP-1: 500 {\"detail\":\"...\"}")).isEqualTo("AGP-1 " + REDACTED);
    }

    @Test
    void 진행_메시지는_run_id만_남긴다() {
        assertThat(redact("report_progress", "run=7 테스트 작성 중")).isEqualTo("run=7 " + REDACTED);
    }

    @Test
    void 메타데이터만_담는_도구는_그대로다() {
        assertThat(redact("update_issue_status", "AGP-1 -> done")).isEqualTo("AGP-1 -> done");
        assertThat(redact("create_page", "spaceId=1 title=설계")).isEqualTo("spaceId=1 title=설계");
    }

    @Test
    void null은_null() {
        assertThat(redact("add_comment", null)).isNull();
    }
}
