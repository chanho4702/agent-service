-- AGP-62: 직원(페르소나) 편집 — 기본 모델·스킬·아바타 설정. 셋 다 선택이라 NULL 허용, 기존 행은 NULL(이전 동작 그대로).
ALTER TABLE persona ADD COLUMN default_model VARCHAR(60) NULL;
ALTER TABLE persona ADD COLUMN skills TEXT NULL;
ALTER TABLE persona ADD COLUMN avatar_config TEXT NULL;

COMMENT ON COLUMN persona.default_model IS '페르소나 기본 모델(claude --model) — 모델 해석: USER 요청 > 이 값 > 프로젝트 맵 > 전역 기본. run.model과 같은 폭';
COMMENT ON COLUMN persona.skills IS '페르소나 전문성(마크다운, 앱 상한 8000자) — 워커 워크스페이스 .claude/skills/persona-<slug>/SKILL.md로 실체화';
COMMENT ON COLUMN persona.avatar_config IS '사무실 아바타 설정 JSON 문자열(앱 상한 1KB, 최상위 키 화이트리스트) — 시각 파츠 값은 프론트 팔레트가 정본';
