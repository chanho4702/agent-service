package com.platform.agentservice.tools;

import com.platform.agentservice.audit.AuditService;
import com.platform.agentservice.audit.AuditStatus;
import com.platform.agentservice.pat.PatPrincipal;
import com.platform.common.error.ServiceUnavailableException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * MCP 도구 실행을 감싸는 공통 틀: (1) 호출자(페르소나)를 확인하고, (2) 실제 동작을
 * 수행하고, (3) 결과와 무관하게 audit 행을 남긴다({@code personaId}=페르소나,
 * {@code actorMemberId}=PAT를 발급한 관리자). 도구는 MCP 프로토콜상 예외를 던지는
 * 대신 오류를 사람이 읽을 텍스트로 돌려주는 편이 낫다 — 그래서 여기서 예외를 잡아
 * 문자열로 변환한다. 다운스트림 가용성 장애({@link ServiceUnavailableException})는
 * "권한 없음"과 혼동되지 않게 별도 문구로 구분한다.
 */
@Component
public class Audited {

    private final AuditService auditService;

    public Audited(AuditService auditService) {
        this.auditService = auditService;
    }

    public String run(String tool, String summary, Supplier<String> action) {
        PatPrincipal actor = ToolActor.current();
        try {
            String result = action.get();
            auditService.record(actor.personaId(), actor.ownerMemberId(), tool, summary, AuditStatus.OK);
            return result;
        } catch (Exception e) {
            auditService.record(actor.personaId(), actor.ownerMemberId(), tool, summary, AuditStatus.ERROR);
            return errorMessage(e);
        }
    }

    /**
     * {@link #run}이 감싼 주 동작(예: run 상태 전이)이 이미 성공적으로 커밋된 뒤,
     * 부수적인 후속 단계(예: ALM 이슈 코멘트)가 실패했을 때 그 실패만 별도 감사 행으로
     * 남긴다(P2a T2 fix round 1 — 커밋된 상태가 진실의 원천이고 알림은 best-effort라는
     * 컨트롤러 판정). 주 동작의 audit 행({@code tool}, OK)과는 별개로 {@code tool+".comment"}
     * 같은 하위 도구명으로 ERROR 행을 추가한다 — 도구 호출 자체는 성공(state 반영됨)했지만
     * 사람 알림이 누락됐다는 간극을 감사에서 추적할 수 있다.
     */
    public void note(String tool, String summary, AuditStatus status) {
        PatPrincipal actor = ToolActor.current();
        auditService.record(actor.personaId(), actor.ownerMemberId(), tool, summary, status);
    }

    private String errorMessage(Exception e) {
        if (e instanceof ServiceUnavailableException) {
            return "권한 서비스/다운스트림 일시 장애 — 권한 없음이 아님, 잠시 후 재시도하세요: " + e.getMessage();
        }
        return "오류: " + e.getMessage();
    }
}
