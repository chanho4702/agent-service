package com.platform.agentservice.run;

import com.platform.agentservice.pat.PatService;
import com.platform.agentservice.pat.dto.PatCreateRequest;
import com.platform.agentservice.pat.dto.PatCreatedResponse;
import com.platform.agentservice.persona.Persona;
import com.platform.agentservice.persona.PersonaRepository;
import com.platform.agentservice.persona.PersonaRole;
import com.platform.common.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RunTokenService} — {@link PatService} 위 얇은 래퍼의 label 규약("run:"+runId)과
 * 시스템 발급자 센티널({@link RunTokenService#SYSTEM_MEMBER_ID}), revoke pass-through를
 * 순수 Mockito로 검증한다(DB 없이).
 */
@ExtendWith(MockitoExtension.class)
class RunTokenServiceTest {

    private static final long PERSONA_ID = 5L;

    @Mock PatService patService;
    @Mock PersonaRepository personaRepository;

    private RunTokenService runTokenService;

    private RunTokenService service() {
        return new RunTokenService(patService, personaRepository);
    }

    private Run runFor(long id, long personaId) {
        Run run = Run.queued(RunType.TASK, "AGP-9", 1L, personaId, RunTrigger.USER, "harness://local", null);
        org.springframework.test.util.ReflectionTestUtils.setField(run, "id", id);
        return run;
    }

    @Test
    void issueFor_resolves_persona_slug_and_labels_token_with_run_id() {
        runTokenService = service();
        Run run = runFor(42L, PERSONA_ID);
        Persona persona = Persona.of(77L, "bot-a", PersonaRole.BACKEND, "봇A", null, null);
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.of(persona));
        when(patService.issue(new PatCreateRequest("run:42", "bot-a", null), RunTokenService.SYSTEM_MEMBER_ID))
                .thenReturn(new PatCreatedResponse("agp_xyz", 9L, "run:42", "bot-a"));

        RunTokenService.IssuedRunToken issued = runTokenService.issueFor(run);

        assertThat(issued.patId()).isEqualTo(9L);
        assertThat(issued.token()).isEqualTo("agp_xyz");
        verify(patService).issue(eq(new PatCreateRequest("run:42", "bot-a", null)), eq(RunTokenService.SYSTEM_MEMBER_ID));
    }

    @Test
    void issueFor_throws_not_found_when_persona_missing() {
        runTokenService = service();
        Run run = runFor(42L, PERSONA_ID);
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runTokenService.issueFor(run)).isInstanceOf(NotFoundException.class);
        verify(patService, never()).issue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void revoke_delegates_to_patService() {
        runTokenService = service();
        runTokenService.revoke(9L);
        verify(patService).revoke(9L);
    }
}
