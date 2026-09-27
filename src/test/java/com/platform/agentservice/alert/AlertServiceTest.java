package com.platform.agentservice.alert;

import com.platform.agentservice.client.OrgClient;
import com.platform.agentservice.run.Run;
import com.platform.agentservice.run.RunTrigger;
import com.platform.agentservice.run.RunType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link AlertService}(P3d, D-P3d-3) — 실제 {@link OrgClient}를 MockRestServiceServer에 물려 org 메일 허브 계약
 * ({@code POST /internal/org/mail}, {@code X-Internal-Token}, body {to,subject,text,source})을 와이어 수준에서 본다.
 */
class AlertServiceTest {

    private static final String TOKEN = "org-internal-secret";
    private static final String MAIL_URL = "http://org-service/internal/org/mail";

    private MockRestServiceServer server;

    private AlertService service(String mailTo, String token) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://org-service");
        server = MockRestServiceServer.bindTo(builder).build();
        return new AlertService(new OrgClient(builder.build()), new AlertProperties(mailTo, token));
    }

    private static Run blockedRun() {
        Run run = Run.queued(RunType.TASK, "AGP-9", 1L, 5L, RunTrigger.SCHEDULER, "harness://default", null);
        ReflectionTestUtils.setField(run, "id", 17L);
        run.start("pending", null);
        run.fail("시간 초과");
        run.block("사고형 실패 — 즉시 중단: 시간 초과");
        return run;
    }

    @Test
    void blocked_alert_posts_to_the_org_mail_hub_with_the_internal_token_and_trimmed_deduped_recipients() {
        AlertService alerts = service(" ops@example.com, lead@example.com ,ops@example.com,, ", TOKEN);
        server.expect(requestTo(MAIL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", TOKEN))
                .andExpect(jsonPath("$.to.length()").value(2))
                .andExpect(jsonPath("$.to[0]").value("ops@example.com"))
                .andExpect(jsonPath("$.to[1]").value("lead@example.com"))
                .andExpect(jsonPath("$.subject").value("[AI팀] run 17 중단 — AGP-9 (사고형 실패)"))
                .andExpect(jsonPath("$.source").value("agent-service"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("사유: 사고형 실패 — 즉시 중단: 시간 초과"),
                        org.hamcrest.Matchers.containsString("POST /api/agent/runs/17/resume"),
                        org.hamcrest.Matchers.containsString("이슈: AGP-9"))))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"accepted\":true,\"disabled\":false}"));

        alerts.notifyBlocked(blockedRun(), "사고형 실패 — 즉시 중단: 시간 초과", AlertKind.INCIDENT);

        server.verify();
    }

    @Test
    void reject_threshold_alert_says_the_fix_is_still_running() {
        AlertService alerts = service("ops@example.com", TOKEN);
        server.expect(requestTo(MAIL_URL))
                .andExpect(jsonPath("$.subject").value("[AI팀] run 17 반려 2/2 — AGP-9 (반려 알림 임계)"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("수정 run 99이 리뷰 지적을 반영 중")))
                .andRespond(withSuccess("{\"accepted\":true,\"disabled\":false}", MediaType.APPLICATION_JSON));

        alerts.notifyRejectThreshold(blockedRun(), 2, 2, 99L);

        server.verify();
    }

    @Test
    void no_recipients_means_no_call() {
        AlertService alerts = service("  ", TOKEN);
        server.expect(never(), requestTo(MAIL_URL));

        alerts.notifyBlocked(blockedRun(), "x", AlertKind.INCIDENT);

        server.verify();
    }

    @Test
    void missing_internal_token_means_no_call_because_org_would_403_anyway() {
        AlertService alerts = service("ops@example.com", " ");
        server.expect(never(), requestTo(MAIL_URL));

        alerts.notifyBlocked(blockedRun(), "x", AlertKind.INCIDENT);

        server.verify();
    }

    @Test
    void org_5xx_is_fail_soft() {
        AlertService alerts = service("ops@example.com", TOKEN);
        server.expect(requestTo(MAIL_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatCode(() -> alerts.notifyBlocked(blockedRun(), "x", AlertKind.REJECT_LIMIT)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void org_mail_disabled_response_is_not_an_error() {
        AlertService alerts = service("ops@example.com", TOKEN);
        server.expect(requestTo(MAIL_URL))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"accepted\":false,\"disabled\":true}"));

        assertThatCode(() -> alerts.notifyBlocked(blockedRun(), "x", AlertKind.INCIDENT)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void a_null_run_cannot_break_the_caller() {
        AlertService alerts = service("ops@example.com", TOKEN);

        assertThatCode(() -> alerts.notifyBlocked(null, "x", AlertKind.INCIDENT)).doesNotThrowAnyException();
        assertThatCode(() -> alerts.notifyRejectThreshold(null, 1, 2, null)).doesNotThrowAnyException();
    }

    @Test
    void summarize_folds_whitespace_and_caps_length() {
        assertThat(AlertService.summarize(null)).isEqualTo("원인 미상");
        assertThat(AlertService.summarize("  line1\n\tline2  ")).isEqualTo("line1 line2");
        String longCause = "x".repeat(500);
        assertThat(AlertService.summarize(longCause)).hasSize(120).endsWith("…");
    }

    @Test
    void recipients_parsing() {
        assertThat(new AlertProperties(null, null).recipients()).isEmpty();
        assertThat(new AlertProperties("a@x.com,b@x.com", null).recipients()).isEqualTo(List.of("a@x.com", "b@x.com"));
        assertThat(new AlertProperties(null, null).token()).isEmpty();
    }
}
