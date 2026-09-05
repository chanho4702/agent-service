package com.platform.agentservice.pat;

/**
 * PatAuthFilter가 검증에 성공하면 SecurityContext에 심는 principal.
 * Task 8~10 도구는 {@code ToolActor.current()}로 이걸 꺼내 쓴다.
 *
 * @param ownerMemberId   토큰을 발급한 사람(관리자) memberId
 * @param personaId       이 토큰이 대표하는 페르소나의 로컬 PK
 * @param personaMemberId 페르소나 자신의 memberId(=Persona.memberId, org-service 등 다운스트림 호출의 actor)
 */
public record PatPrincipal(long ownerMemberId, long personaId, long personaMemberId) {
}
