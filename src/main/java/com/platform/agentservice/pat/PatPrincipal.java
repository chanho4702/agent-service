package com.platform.agentservice.pat;

import com.platform.agentservice.audit.AuditOrigin;

/**
 * PatAuthFilter가 검증에 성공하면 SecurityContext에 심는 principal.
 * Task 8~10 도구는 {@code ToolActor.current()}로 이걸 꺼내 쓴다.
 *
 * @param ownerMemberId   토큰을 발급한 사람(관리자) memberId
 * @param personaId       이 토큰이 대표하는 페르소나의 로컬 PK
 * @param personaMemberId 페르소나 자신의 memberId(=Persona.memberId, org-service 등 다운스트림 호출의 actor)
 * @param runToken        디스패처가 run에 묶어 발급한 토큰인가({@link PatToken#isRunToken()}) — 감사 출처 WORKER/EXTERNAL 판정(AGP-63)
 * @param runId           run 토큰이면 그 run id(label {@code run:<id>}에서 해석), 사람용 PAT·해석 불가면 null
 */
public record PatPrincipal(long ownerMemberId, long personaId, long personaMemberId, boolean runToken, Long runId) {

    /** 사람용 PAT(외부 MCP 연결). */
    public PatPrincipal(long ownerMemberId, long personaId, long personaMemberId) {
        this(ownerMemberId, personaId, personaMemberId, false, null);
    }

    public AuditOrigin origin() {
        return runToken ? AuditOrigin.WORKER : AuditOrigin.EXTERNAL;
    }
}
