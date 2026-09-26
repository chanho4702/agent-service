-- P3a: AI 사무실 감독 API(GET /api/agent/office, /personas/{id}/activity)용 조회 인덱스. 프론트가 10초 폴링한다.
-- tool_call_audit는 도구 호출마다 쌓여 가장 빨리 크는 테이블이다. 말풍선 쿼리(최근 5분, 페르소나 무관 범위)는
--   V1의 (persona_id, created_at) 인덱스를 선두 컬럼 때문에 못 타서 폴링마다 전체 스캔이 된다 — created_at 단독 인덱스.
-- run(persona_id, id DESC): 개인 오피스의 "그 페르소나 최근 run 20건". run에는 persona 축 인덱스가 없었다.
-- gate 부분 인덱스(decision IS NULL): 폴링마다 미결 게이트를 읽는데, 결정된 게이트가 쌓일수록 전체 스캔이 커진다.
--   미결은 소수라 부분 인덱스가 작게 유지된다.
-- 넣지 않은 것: run(updated_at) — 최근 종결 10건은 status 필터 후 정렬인데 run은 이슈당 몇 건 수준으로 느리게 커서
--   정렬 비용이 작다. usage_ledger는 V3의 (scope, scope_id, created_at)을 쿼리가 scope_id까지 지정해 그대로 탄다.
CREATE INDEX idx_audit_created ON tool_call_audit (created_at);
CREATE INDEX idx_run_persona ON run (persona_id, id DESC);
CREATE INDEX idx_gate_pending ON gate (id) WHERE decision IS NULL;
