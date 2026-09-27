package com.platform.agentservice.tools;

import com.platform.agentservice.audit.AuditOrigin;
import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.audit.ToolCallAudit;
import com.platform.agentservice.audit.ToolCallAuditRepository;
import com.platform.agentservice.pat.PatPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** AGP-63 — 감사 출처 판정: run 토큰=WORKER(+run id), 사람용 PAT=EXTERNAL, PAT 없는 서버 내부 기록=SYSTEM. */
class AuditedTest {

    ToolCallAuditRepository repository = mock(ToolCallAuditRepository.class);
    Audited audited = new Audited(new AuditService(repository));

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(PatPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private ToolCallAudit saved() {
        ArgumentCaptor<ToolCallAudit> captor = ArgumentCaptor.forClass(ToolCallAudit.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void run_token_call_is_worker_with_run_id() {
        authenticate(new PatPrincipal(0L, 5L, 42L, true, 17L));

        audited.run("get_issue", "AGP-1", () -> "ok");

        ToolCallAudit a = saved();
        assertThat(a.getOrigin()).isEqualTo(AuditOrigin.WORKER);
        assertThat(a.getRunId()).isEqualTo(17L);
        assertThat(a.getPersonaId()).isEqualTo(5L);
        assertThat(a.getStatus()).isEqualTo(AuditStatus.OK);
    }

    @Test
    void human_pat_call_is_external_without_run_id_even_on_error() {
        authenticate(new PatPrincipal(100L, 5L, 42L));

        audited.run("get_issue", "AGP-1", () -> { throw new IllegalStateException("x"); });

        ToolCallAudit a = saved();
        assertThat(a.getOrigin()).isEqualTo(AuditOrigin.EXTERNAL);
        assertThat(a.getRunId()).isNull();
        assertThat(a.getStatus()).isEqualTo(AuditStatus.ERROR);
    }

    @Test
    void note_follows_the_same_origin_rule() {
        authenticate(new PatPrincipal(0L, 5L, 42L, true, 9L));

        audited.note("report_result.comment", "AGP-1: 실패", AuditStatus.ERROR);

        ToolCallAudit a = saved();
        assertThat(a.getOrigin()).isEqualTo(AuditOrigin.WORKER);
        assertThat(a.getRunId()).isEqualTo(9L);
    }

    @Test
    void server_internal_record_without_pat_is_system() {
        new AuditService(repository).record(null, 7L, "credential.put", "scope=PLATFORM", AuditStatus.OK);

        ToolCallAudit a = saved();
        assertThat(a.getOrigin()).isEqualTo(AuditOrigin.SYSTEM);
        assertThat(a.getRunId()).isNull();
        assertThat(a.getPersonaId()).isNull();
    }
}
