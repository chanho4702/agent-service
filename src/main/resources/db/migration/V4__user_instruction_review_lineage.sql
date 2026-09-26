-- P2c: 사용자 지시 run(AGP-42)과 리뷰 루프(AGP-44)의 계보.
-- instruction: USER 트리거 run에 사람이 붙인 지시문. 워커 프롬프트에 데이터 경계로 감싸 들어간다 — 길이 제한을 두지 않으려고 TEXT.
-- parent_run_id: REVIEW run → 검증 대상 TASK run, 반려-fix continuation → 반려한 REVIEW run. 누가 누구를 낳았는지
--   감사 추적용이며, 재시도 continuation(같은 run의 attempt+1)에는 채우지 않는다(그건 issue_key+attempt로 이미 추적된다).
ALTER TABLE run ADD COLUMN instruction TEXT;
ALTER TABLE run ADD COLUMN parent_run_id BIGINT REFERENCES run(id);
CREATE INDEX idx_run_parent ON run (parent_run_id);
