package com.platform.agentservice.persona.dto;

import com.platform.agentservice.persona.PersonaSkills;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/agent/personas/{id}} 요청(AGP-62) — 필드 전부 선택. {@code null}(생략·JSON null)은 그대로, 빈 문자열(공백뿐 포함)은
 * 지움. {@code name}은 지울 수 없다(400).
 *
 * <p>길이 상한은 persona 컬럼폭(name 80 · emoji 16 · default_model 60 — {@code RequestDtoColumnWidthTest})과 스킬 앱 상한
 * {@value PersonaSkills#MAX_LENGTH}자. {@code avatarConfig}는 JSON 문자열(정본)이나 객체를 받아 서비스가 형태를 검증한다 —
 * 요청 바인딩 Jackson 버전에 묶이지 않게 {@link Object}(문자열·Map·그 밖 스칼라/배열)로 받는다. 모델 문자 규칙·인코딩 검사도
 * 서비스가 한다(오류 문구에 입력을 싣지 않게).
 */
public record PersonaUpdateRequest(
        @Size(max = 80, message = "name은 80자 이하여야 합니다") String name,
        @Size(max = 16, message = "emoji는 16자 이하여야 합니다") String emoji,
        String voicePrompt,
        @Size(max = 60, message = "defaultModel은 60자 이하여야 합니다") String defaultModel,
        @Size(max = PersonaSkills.MAX_LENGTH, message = "skills는 " + PersonaSkills.MAX_LENGTH + "자 이하여야 합니다") String skills,
        Object avatarConfig) {
}
