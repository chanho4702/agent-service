package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.IssueUpdateRequest;
import com.platform.agentservice.client.dto.ProjectSettingsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 이슈 부분 수정의 공통 패턴: GET → 현재 값으로 채운 full-replace 요청
 * ({@link IssueUpdateRequest#preserving}) 위에 바꿀 필드만 덮어쓰기 → PUT(expectedVersion).
 * 409(낙관적 락 충돌)면 재조회한 최신 값 위에 같은 변경을 다시 얹어 딱 1회만 재시도한다 —
 * 재시도에서도 현재 값부터 다시 채우므로 그 사이 남이 바꾼 담당자·상태 등을 덮어쓰지 않는다.
 * 두 번째 409와 그 외 오류(400 스킴 위반 등)는 그대로 전파한다.
 *
 * <p>MCP 도구({@code claim_issue}/{@code update_issue_status}/{@code update_issue})와
 * 디스패처 자동 claim({@link com.platform.agentservice.run.RunService})이 모두 이 경로를 탄다.
 */
@Slf4j
@Component
public class IssueClaimSupport {

    /** 스킴을 못 읽었거나 진행 중 카테고리 상태가 없을 때의 폴백 — 기본 스킴의 진행 중 상태 id. */
    static final String FALLBACK_IN_PROGRESS = "inprogress";
    private static final String ACTIVE_KIND = "active";

    private final AlmClient almClient;

    public IssueClaimSupport(AlmClient almClient) {
        this.almClient = almClient;
    }

    /**
     * 이슈를 GET해 assignee/status만 바꿔 PUT한다. {@code status}가 비면 그 이슈 프로젝트의 스킴에서
     * "진행 중" 상태를 해석한다(AGP-27, {@link #resolveInProgressStatus}). 넘긴 status가 스킴에 없으면
     * alm의 400이 그대로 전파된다.
     */
    public IssueResponse claim(String issueKey, Long assigneeId, String status, String bearer) {
        IssueResponse issue = almClient.getByKey(issueKey, bearer);
        String target = status == null || status.isBlank()
                ? resolveInProgressStatus(issue.projectId(), bearer)
                : status.trim();
        return update(issue, req -> req.withAssigneeId(assigneeId).withStatus(target), bearer);
    }

    /** {@code changes}는 재시도 때 다시 적용되므로 부수효과 없는 순수 변환이어야 한다. */
    public IssueResponse update(String issueKey, UnaryOperator<IssueUpdateRequest> changes, String bearer) {
        return update(almClient.getByKey(issueKey, bearer), changes, bearer);
    }

    private IssueResponse update(IssueResponse issue, UnaryOperator<IssueUpdateRequest> changes, String bearer) {
        try {
            return almClient.update(issue.id(), changes.apply(IssueUpdateRequest.preserving(issue)), bearer);
        } catch (AlmClient.VersionConflictException e) {
            IssueResponse fresh = almClient.getByKey(issue.key(), bearer);
            return almClient.update(fresh.id(), changes.apply(IssueUpdateRequest.preserving(fresh)), bearer);
        }
    }

    /**
     * 프로젝트 유효 스킴에서 카테고리 의미(kind)가 {@code active}인 상태를 고른다 — 기본 스킴 id
     * {@code inprogress}가 그중에 있으면 그것을(기존 동작 보존), 없으면 {@code order}가 가장 앞선 것.
     * 진행 중 상태가 여럿인 스킴(예: 개발 중·리뷰 중)에서 무엇이 "착수"인지는 스킴이 말해 주지 않아
     * 순서로 정한다. 스킴을 못 읽거나(권한·장애) 진행 중 상태가 하나도 없으면 {@code inprogress}로
     * 폴백한다 — 그 상태가 스킴에 없으면 claim은 alm 400으로 실패한다(기존과 같은 실패 모양).
     * 전이 규칙(현재 상태에서 그 상태로 갈 수 있는지)은 보지 않는다 — 판정은 alm PUT이 한다.
     */
    public String resolveInProgressStatus(long projectId, String bearer) {
        List<ProjectSettingsResponse.StatusEntry> statuses;
        try {
            ProjectSettingsResponse settings = almClient.getProjectSettings(projectId, bearer);
            statuses = settings == null || settings.body() == null || settings.body().statuses() == null
                    ? List.of() : settings.body().statuses();
        } catch (RuntimeException e) {
            log.warn("프로젝트 스킴 조회 실패 — claim 상태를 '{}'로 폴백합니다: projectId={} 원인={}",
                    FALLBACK_IN_PROGRESS, projectId, e.getMessage());
            return FALLBACK_IN_PROGRESS;
        }
        List<ProjectSettingsResponse.StatusEntry> active = statuses.stream()
                .filter(s -> s.id() != null && ACTIVE_KIND.equals(s.kind()))
                .sorted(Comparator.comparing(s -> s.order() == null ? Integer.MAX_VALUE : s.order()))
                .toList();
        if (active.isEmpty()) {
            return FALLBACK_IN_PROGRESS;
        }
        return active.stream().map(ProjectSettingsResponse.StatusEntry::id)
                .filter(FALLBACK_IN_PROGRESS::equals).findFirst()
                .orElse(active.get(0).id());
    }
}
