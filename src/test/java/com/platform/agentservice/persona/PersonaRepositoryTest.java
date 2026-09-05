package com.platform.agentservice.persona;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class PersonaRepositoryTest {

    @Autowired PersonaRepository personas;

    @BeforeEach
    void clean() {
        personas.deleteAllInBatch();
    }

    @Test
    void 슬러그로_페르소나를_조회한다() {
        Persona saved = personas.save(Persona.of(1L, "planner-01", PersonaRole.PLANNER, "기획 에이전트", "🧭", "너는 기획자다"));

        Persona found = personas.findBySlug("planner-01").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getMemberId()).isEqualTo(1L);
        assertThat(found.getRole()).isEqualTo(PersonaRole.PLANNER);
        assertThat(found.getName()).isEqualTo("기획 에이전트");
        assertThat(found.isActive()).isTrue();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 존재하지_않는_슬러그는_빈값이다() {
        assertThat(personas.findBySlug("no-such-slug")).isEmpty();
    }
}
