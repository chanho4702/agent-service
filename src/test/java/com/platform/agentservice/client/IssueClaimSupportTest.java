package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.ProjectSettingsResponse;
import com.platform.agentservice.client.dto.ProjectSettingsResponse.StatusEntry;
import com.platform.common.error.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** AGP-27 — claim 상태 해석: 스킴의 active 카테고리 우선, 못 읽거나 없으면 inprogress 폴백. */
@ExtendWith(MockitoExtension.class)
class IssueClaimSupportTest {

    private static final String BEARER = "Bearer t";

    @Mock AlmClient almClient;

    @Test
    void prefers_builtin_inprogress_when_it_is_one_of_the_active_statuses() {
        when(almClient.getProjectSettings(1L, BEARER)).thenReturn(settings(
                new StatusEntry("develop", "active", 1),
                new StatusEntry("inprogress", "active", 5)));

        assertThat(new IssueClaimSupport(almClient).resolveInProgressStatus(1L, BEARER)).isEqualTo("inprogress");
    }

    @Test
    void picks_lowest_order_active_status_for_custom_scheme() {
        when(almClient.getProjectSettings(1L, BEARER)).thenReturn(settings(
                new StatusEntry("todo", "new", 0),
                new StatusEntry("qa", "active", 7),
                new StatusEntry("doing", "active", 2),
                new StatusEntry("done", "complete", 9)));

        assertThat(new IssueClaimSupport(almClient).resolveInProgressStatus(1L, BEARER)).isEqualTo("doing");
    }

    @Test
    void falls_back_to_inprogress_when_scheme_has_no_active_status() {
        when(almClient.getProjectSettings(1L, BEARER)).thenReturn(settings(
                new StatusEntry("todo", "new", 0), new StatusEntry("done", "complete", 1)));

        assertThat(new IssueClaimSupport(almClient).resolveInProgressStatus(1L, BEARER)).isEqualTo("inprogress");
    }

    @Test
    void falls_back_to_inprogress_when_scheme_lookup_fails() {
        when(almClient.getProjectSettings(1L, BEARER)).thenThrow(new ServiceUnavailableException("다운"));

        assertThat(new IssueClaimSupport(almClient).resolveInProgressStatus(1L, BEARER)).isEqualTo("inprogress");
    }

    private static ProjectSettingsResponse settings(StatusEntry... statuses) {
        return new ProjectSettingsResponse(new ProjectSettingsResponse.SettingsBody(
                List.of(statuses), List.of(), List.of(), List.of(), "medium", List.of(), Map.of()));
    }
}
