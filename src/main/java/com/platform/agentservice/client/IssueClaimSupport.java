package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.IssueUpdateRequest;
import org.springframework.stereotype.Component;

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
@Component
public class IssueClaimSupport {

    private final AlmClient almClient;

    public IssueClaimSupport(AlmClient almClient) {
        this.almClient = almClient;
    }

    /** 이슈를 GET해 assignee/status만 바꿔 PUT한다. */
    public IssueResponse claim(String issueKey, Long assigneeId, String status, String bearer) {
        return update(issueKey, req -> req.withAssigneeId(assigneeId).withStatus(status), bearer);
    }

    /** {@code changes}는 재시도 때 다시 적용되므로 부수효과 없는 순수 변환이어야 한다. */
    public IssueResponse update(String issueKey, UnaryOperator<IssueUpdateRequest> changes, String bearer) {
        IssueResponse issue = almClient.getByKey(issueKey, bearer);
        try {
            return almClient.update(issue.id(), changes.apply(IssueUpdateRequest.preserving(issue)), bearer);
        } catch (AlmClient.VersionConflictException e) {
            IssueResponse fresh = almClient.getByKey(issue.key(), bearer);
            return almClient.update(fresh.id(), changes.apply(IssueUpdateRequest.preserving(fresh)), bearer);
        }
    }
}
