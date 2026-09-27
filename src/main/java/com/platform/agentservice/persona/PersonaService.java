package com.platform.agentservice.persona;

import com.platform.agentservice.client.AuthTokenClient;
import com.platform.agentservice.client.OrgClient;
import com.platform.agentservice.persona.dto.PersonaCreateRequest;
import com.platform.agentservice.persona.dto.PersonaResponse;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PersonaService {

    private static final String SYNTHETIC_EMAIL_DOMAIN = "@platform.local";

    private final AuthTokenClient authTokenClient;
    private final OrgClient orgClient;
    private final PersonaRepository personaRepository;

    /** created=true면 컨트롤러가 201을, false(기존 슬러그 갱신)면 200을 돌려준다. */
    public record BootstrapResult(PersonaResponse persona, boolean created) {}

    /**
     * ① auth-server에 페르소나 사용자를 등록해 memberId를 얻고 ② org-service 멤버로
     * 등록하고 ③ 요청된 grant를 org-service에 부여한 뒤 ④ 로컬 Persona로 저장한다.
     *
     * <p>슬러그가 이미 존재하면 위 다운스트림 호출을 전부 건너뛰고(멱등) 표시용 필드만
     * 갱신한다 — 재호출마다 org 멤버·grant를 다시 만들려 들지 않는다.
     *
     * <p>관리 권한은 컨트롤러 앞단({@code AgentAuthz})이 이미 판정했다(P3f). 그래서 ①② 등록은 내부 경로(시크릿·토큰)로
     * 하고, ③ grant만 호출자 bearer로 보내 org가 자원 ADMIN을 다시 판정하게 둔다(이중 방어).
     *
     * @param callerBearer 호출자의 Authorization 헤더 값 — grant에만 쓴다. 페르소나 자신의 토큰으로는 절대 대체하지 않는다.
     * @param actorId      호출자 JWT sub — org 멤버 재활성 이력의 행위자.
     */
    @Transactional
    public BootstrapResult bootstrap(PersonaCreateRequest req, String callerBearer, long actorId) {
        var existing = personaRepository.findBySlug(req.slug());
        if (existing.isPresent()) {
            Persona persona = existing.get();
            persona.refresh(req.name(), req.emoji(), req.voicePrompt());
            return new BootstrapResult(PersonaResponse.from(persona), false);
        }

        String email = (req.email() == null || req.email().isBlank())
                ? "agents+" + req.slug() + SYNTHETIC_EMAIL_DOMAIN
                : req.email();

        AuthTokenClient.AgentRegistration registration =
                authTokenClient.registerAgent(req.slug(), req.name(), email);
        long memberId = registration.userId();

        orgClient.registerAgentMember(memberId, req.name(), email, actorId);

        List<PersonaCreateRequest.GrantRequest> grants = req.grants();
        if (grants != null) {
            for (PersonaCreateRequest.GrantRequest grant : grants) {
                orgClient.grant(memberId, grant.resourceType(), grant.resourceId(), grant.role(), callerBearer);
            }
        }

        Persona saved = personaRepository.save(
                Persona.of(memberId, req.slug(), req.role(), req.name(), req.emoji(), req.voicePrompt(), req.projectId()));
        return new BootstrapResult(PersonaResponse.from(saved), true);
    }

    /**
     * 활성/비활성 전환(AGP-29). 비활성화는 새 인증만 막는다 — 사람용 PAT은 {@code PatService.validate}가
     * 거부하고 새 run 토큰 발급도 막히지만, 이미 발급된 run 토큰은 run이 끝날 때까지 유효하다. 토큰을
     * 철회하지 않으므로 다시 활성화하면 기존 PAT이 그대로 살아난다(영구 차단은 토큰 철회로).
     */
    @Transactional
    public PersonaResponse changeActive(long id, boolean active) {
        Persona persona = personaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("페르소나를 찾을 수 없습니다: " + id));
        persona.changeActive(active);
        return PersonaResponse.from(persona);
    }

    @Transactional(readOnly = true)
    public List<PersonaResponse> list() {
        return personaRepository.findAll().stream().map(PersonaResponse::from).toList();
    }
}
