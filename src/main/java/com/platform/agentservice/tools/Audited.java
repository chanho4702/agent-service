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

    private String errorMessage(Exception e) {
        if (e instanceof ServiceUnavailableException) {
            return "권한 서비스/다운스트림 일시 장애 — 권한 없음이 아님, 잠시 후 재시도하세요: " + e.getMessage();
        }
        return "오류: " + e.getMessage();
    }
}
