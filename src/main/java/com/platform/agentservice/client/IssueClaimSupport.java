package com.platform.agentservice.client;

import com.platform.agentservice.client.dto.IssueResponse;
import com.platform.agentservice.client.dto.IssueUpdateRequest;
import org.springframework.stereotype.Component;

/**
 * 이슈 담당자·상태를 GET+PUT(낙관적 락 409면 재조회 후 1회만 재시도)으로 바꾸는 공통 패턴
 * (P2a T4 디스패처에서 추출) — {@code IssueTools.claimIssue}/{@code updateIssueStatus}
 * 내부에 있던 것과 동일한 로직이다.
 *
 * <p><b>중복 노트(P2a T4 fix round — 의도적 최소 변경)</b>: {@code IssueTools}는 이
 * 로직을 여전히 자체적으로 갖고 있다(리팩터하지 않았다) — {@code IssueToolsTest}가
 * {@code almClient.getByKey}/{@code almClient.update}를 직접 스텁·검증하므로, {@code
 * IssueTools}가 이 헬퍼에 위임하도록 바꾸면 그 목(mock) 기반 테스트가 실제로 호출되지
 * 않는 메서드를 스텁하게 되어 깨진다. 지금은 {@link com.platform.agentservice.run.RunService}
 * (디스패처의 자동 claim)만 이 헬퍼를 쓴다 — {@code IssueTools} 쪽 중복 제거는 그
 * 테스트를 함께 다시 쓸 후속 작업으로 남겨 둔다.
 */
@Component
public class IssueClaimSupport {

    private final AlmClient almClient;

    public IssueClaimSupport(AlmClient almClient) {
        this.almClient = almClient;
    }

    /** 이슈를 GET해 assignee/status만 바꿔 PUT한다. details는 null로 보내 V2 확장 필드를 보존한다(S10). */
    public IssueResponse claim(String issueKey, Long assigneeId, String status, String bearer) {
        IssueResponse issue = almClient.getByKey(issueKey, bearer);
        return updateWithRetry(issue, assigneeId, status, bearer);
    }

    private IssueResponse updateWithRetry(IssueResponse issue, Long assigneeId, String status, String bearer) {
        try {
            return almClient.update(issue.id(), toUpdateRequest(issue, assigneeId, status), bearer);
        } catch (AlmClient.VersionConflictException e) {
            IssueResponse fresh = almClient.getByKey(issue.key(), bearer);
            return almClient.update(fresh.id(), toUpdateRequest(fresh, assigneeId, status), bearer);
        }
    }

    private IssueUpdateRequest toUpdateRequest(IssueResponse issue, Long assigneeId, String status) {
        return new IssueUpdateRequest(
                issue.title(), issue.description(), issue.type(), status, issue.priority(),
                assigneeId, null, issue.version(), null);
    }
}
