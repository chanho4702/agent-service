package com.platform.agentservice.authz;

import com.platform.agentservice.TestAuth;
import com.platform.agentservice.pat.PatTokenRepository;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.agentservice.persona.dto.PersonaCreateRequest;
import com.platform.agentservice.run.GateRepository;
import com.platform.agentservice.run.RunRepository;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.org.v1.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 판정 규칙(D-P3f-1·2·4) 단위 — 컨트롤러 배선은 각 *ControllerTest가 본다. */
class AgentAuthzTest {

    PermissionClient permissions = mock(PermissionClient.class);
    PersonaRepository personas = mock(PersonaRepository.class);
    AgentAuthz authz;

    static final AgentCaller GLOBAL = new AgentCaller(1L, true);
    static final AgentCaller BOB = new AgentCaller(2L, false);

    @BeforeEach
    void setUp() {
        authz = new AgentAuthz(permissions, personas, mock(RunRepository.class), mock(GateRepository.class),
                mock(PatTokenRepository.class));
    }

    @Test
    void 전역_관리자는_org를_부르지_않고_통과한다() {
        assertThat(authz.canManage(GLOBAL, 7L)).isTrue();
        authz.requireManageProject(GLOBAL, 7L);
        authz.requireManagePersona(GLOBAL, Persona.of(1L, "s", PersonaRole.BACKEND, "S", null, null));

        verifyNoInteractions(permissions);
    }

    @Test
    void 프로젝트_ADMIN은_그_프로젝트만_관리한다() {
        given(permissions.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());
        given(permissions.checkAdmin(2L, ResourceType.PROJECT, "8")).willReturn(PermissionDecision.deny("NO_GRANT"));

        assertThat(authz.canManage(BOB, 7L)).isTrue();
        assertThat(authz.canManage(BOB, 8L)).isFalse();
        assertThatThrownBy(() -> authz.requireManageProject(BOB, 8L))
                .isInstanceOf(ForbiddenException.class).hasMessage(AgentAuthz.PROJECT_ADMIN_ONLY);
    }

    @Test
    void org_판정_실패는_거부이고_사유는_판정_실패로_말한다() {
        given(permissions.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.orgFailure());

        assertThat(authz.canManage(BOB, 7L)).isFalse();
        assertThatThrownBy(() -> authz.requireManageProject(BOB, 7L))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("권한 판정에 실패해");
    }

    /** org 가용성 장애는 "권한 없음"(403)이 아니라 "지금 판정 불가"(503)로 그대로 올라간다. */
    @Test
    void org_가용성_장애는_503으로_올라간다() {
        given(permissions.checkAdmin(2L, ResourceType.PROJECT, "7"))
                .willThrow(new ServiceUnavailableException("권한 서비스에 연결할 수 없어 거부했습니다 — 잠시 후 다시 시도하세요"));

        assertThatThrownBy(() -> authz.requireManageProject(BOB, 7L)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void 계정_상태로_막히면_그_사실을_말한다() {
        given(permissions.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.deny("SUSPENDED"));

        assertThatThrownBy(() -> authz.requireManageProject(BOB, 7L)).hasMessage("정지된 계정입니다");
    }

    @Test
    void 공용_페르소나와_없는_대상은_전역_관리자만() {
        assertThatThrownBy(() -> authz.requireManagePersona(BOB, Persona.of(1L, "s", PersonaRole.BACKEND, "S", null, null)))
                .hasMessage(AgentAuthz.SHARED_PERSONA_GLOBAL_ONLY);
        assertThatThrownBy(() -> authz.requireManagePersona(BOB, null))
                .hasMessage(AgentAuthz.SHARED_PERSONA_GLOBAL_ONLY);
        verifyNoInteractions(permissions);
    }

    @Test
    void 부여_상한_관리하는_스페이스는_허용하고_모르는_종류는_거부한다() {
        given(personas.findBySlug("p7")).willReturn(Optional.empty());
        given(permissions.checkAdmin(2L, ResourceType.PROJECT, "7")).willReturn(PermissionDecision.allow());
        given(permissions.checkAdmin(2L, ResourceType.SPACE, "3")).willReturn(PermissionDecision.allow());

        assertThat(authz.canBootstrapPersona(TestAuth.user(2L, "Bob"), request(
                new PersonaCreateRequest.GrantRequest("space", " 3 ", "EDITOR")))).isTrue();
        assertThatThrownBy(() -> authz.canBootstrapPersona(TestAuth.user(2L, "Bob"), request(
                new PersonaCreateRequest.GrantRequest("TEAM", "1", "EDITOR"))))
                .hasMessage(AgentAuthz.UNMANAGED_GRANT);
    }

    @Test
    void 전역_관리자는_GLOBAL_grant도_공용_페르소나도_만든다() {
        given(personas.findBySlug("g")).willReturn(Optional.empty());
        PersonaCreateRequest shared = new PersonaCreateRequest("g", PersonaRole.BACKEND, "G", null, null, null,
                List.of(new PersonaCreateRequest.GrantRequest("GLOBAL", "-", "ADMIN")), null);

        assertThat(authz.canBootstrapPersona(TestAuth.admin(1L, "Admin"), shared)).isTrue();
        verifyNoInteractions(permissions);
    }

    private static PersonaCreateRequest request(PersonaCreateRequest.GrantRequest grant) {
        return new PersonaCreateRequest("p7", PersonaRole.BACKEND, "P", null, null, null, List.of(grant), 7L);
    }
}
