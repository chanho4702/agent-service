-- P3f(AGP-64): 페르소나의 소속 프로젝트. 관리 권한(활성 토글·PAT 발급/철회)을 이 프로젝트의 ADMIN에게 위임하는 기준이다.
-- NULL = 전사 공용 페르소나 — 생성·관리는 전역 관리자만. 기존 행은 전부 전역 관리자가 만든 것이라 NULL(공용)로 둔다.
-- alm 프로젝트 id를 가리키지만 서비스 경계를 넘으므로 FK는 없다(프로젝트 삭제 시 고아로 남고, 관리 판정은 org grant가 정한다).
ALTER TABLE persona ADD COLUMN project_id BIGINT NULL;
COMMENT ON COLUMN persona.project_id IS '소속 ALM 프로젝트 id — NULL이면 전사 공용(전역 관리자만 관리)';
