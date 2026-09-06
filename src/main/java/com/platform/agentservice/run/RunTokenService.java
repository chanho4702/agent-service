package com.platform.agentservice.run;

import com.platform.agentservice.pat.PatService;
import com.platform.agentservice.pat.dto.PatCreateRequest;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Run 하나에 묶인 임시(ephemeral) PAT 발급/철회 — {@link PatService} 위의 얇은 래퍼(P2a T2).
 * 워커(헤드리스 Claude Code)는 디스패처가 run마다 발급한 이 토큰으로 인증하므로,
 * {@code ToolActor.current()}가 곧 그 run의 페르소나가 된다. {@code PatToken}에는 runId
 * 컬럼을 두지 않는다 — label 규약({@code "run:" + runId})으로 어느 run의 토큰인지 식별한다
 * ({@link PatService}/{@code PatToken}은 이 서비스가 소비만 하고 건드리지 않는다).
 *
 * <p><b>ownerMemberId 설계 결정</b>: {@link PatService#issue}는 발급자(관리자) memberId를
 * 요구하지만, run 토큰의 실제 발급자는 사람이 아니라 시스템(디스패처)이다. {@code Run}
 * 엔티티는 트리거한 특정 사용자의 memberId를 보존하지 않는다({@link RunTrigger}는
 * SCHEDULER/USER 구분만 있다 — T1 확인) — 그래서 발급자 자리에는 {@link #SYSTEM_MEMBER_ID}
 * (0L) 센티널을 쓴다. 이 값은 {@code PatPrincipal.ownerMemberId()}를 거쳐 감사 로그의
 * actorMemberId로도 남으므로, run 토큰으로 실행된 도구 호출은 감사에서 "시스템이 대리
 * 발급"임을 구분할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class RunTokenService {

    /** run 토큰 발급자로 기록되는 시스템 센티널 — 실제 관리자 memberId가 아니다. */
    public static final long SYSTEM_MEMBER_ID = 0L;

    private final PatService patService;
    private final PersonaRepository personaRepository;

    public record IssuedRunToken(long patId, String token) {
    }

    /**
     * {@code run.personaId}(로컬 PK)로 {@link Persona}를 찾아 slug를 resolve하고,
     * label={@code "run:"+run.getId()}로 PAT를 발급한다. 만료는 두지 않는다 — run
     * 종료 시 {@link #revoke}로 명시적으로 철회한다(디스패처/워커 런처 책임, T3).
     */
    public IssuedRunToken issueFor(Run run) {
        Persona persona = personaRepository.findById(run.getPersonaId())
                .orElseThrow(() -> new NotFoundException("run의 페르소나를 찾을 수 없습니다: " + run.getPersonaId()));
        PatCreatedResponse response = patService.issue(
                new PatCreateRequest("run:" + run.getId(), persona.getSlug(), null), SYSTEM_MEMBER_ID);
        return new IssuedRunToken(response.id(), response.token());
    }

    /** 멱등 — {@link PatService#revoke}를 그대로 위임한다(없는 id·이미 철회된 토큰이어도 조용히 끝남). */
    public void revoke(long patId) {
        patService.revoke(patId);
    }
}
