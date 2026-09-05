package com.platform.agentservice.persona;

import com.platform.agentservice.client.AuthTokenClient;
import com.platform.agentservice.client.OrgClient;
import com.platform.agentservice.persona.dto.PersonaCreateRequest;
import com.platform.agentservice.persona.dto.PersonaResponse;
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
     * @param adminBearer 호출자(관리자)의 Authorization 헤더 값을 그대로 전달한다.
     *                    페르소나 자신의 토큰으로는 절대 대체하지 않는다 — auth-server의
     *                    {@code /api/auth/agents}와 org-service의 등록/grant 모두 ROLE_ADMIN
     *                    (grant는 대상 리소스 ADMIN)을 요구한다.
     */
    @Transactional
    public BootstrapResult bootstrap(PersonaCreateRequest req, String adminBearer) {
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
                authTokenClient.registerAgent(req.slug(), req.name(), email, adminBearer);
        long memberId = registration.userId();

        orgClient.registerAgentMember(memberId, req.name(), email, adminBearer);

        List<PersonaCreateRequest.GrantRequest> grants = req.grants();
        if (grants != null) {
            for (PersonaCreateRequest.GrantRequest grant : grants) {
                orgClient.grant(memberId, grant.resourceType(), grant.resourceId(), grant.role(), adminBearer);
            }
        }

        Persona saved = personaRepository.save(
                Persona.of(memberId, req.slug(), req.role(), req.name(), req.emoji(), req.voicePrompt()));
        return new BootstrapResult(PersonaResponse.from(saved), true);
    }

    @Transactional(readOnly = true)
    public List<PersonaResponse> list() {
        return personaRepository.findAll().stream().map(PersonaResponse::from).toList();
    }
}
