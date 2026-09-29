package com.platform.agentservice.persona;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonaRepository extends JpaRepository<Persona, Long> {
    Optional<Persona> findBySlug(String slug);

    /** 리뷰어 자동 선택(D-P4b-1) — 활성 페르소나를 롤로, id 오름차순. */
    List<Persona> findByRoleAndActiveTrueOrderByIdAsc(PersonaRole role);
}
