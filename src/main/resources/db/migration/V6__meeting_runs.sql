-- P3b: 회의 run(MEETING/RETRO/ESCALATION — run.type VARCHAR(20)에 그대로 들어가 타입 컬럼 변경은 없다).
-- output_page_id: 워커가 report_result(pageId=)로 보고한 산출물 위키 페이지(회의록·보고서). 사무실 게시판과 감독 화면이
--   위키 조회 없이 이 값으로 회의록 링크를 만든다. 위키는 다른 서비스 DB라 FK는 두지 않는다.
-- attendee_persona_ids: 회의 참석 페르소나 id를 쉼표로 이은 목록(첫 번째 = 진행자 = run.persona_id). 참석자는 소집 시점에
--   정해지는데 워커 프롬프트는 비동기 실행 시점에 조립되므로 run에 남겨야 한다 — 재시도·게이트 승인 continuation도 승계한다.
--   페르소나는 소수(≤20)라 조인 테이블 대신 목록 컬럼으로 둔다.
-- idx_run_board: 게시판 "최근 회의록 5건"(DONE + output_page_id 있음, ended_at 최신순) — 산출물이 있는 run만 담는 부분 인덱스.
ALTER TABLE run ADD COLUMN output_page_id BIGINT;
ALTER TABLE run ADD COLUMN attendee_persona_ids VARCHAR(400);
CREATE INDEX idx_run_board ON run (ended_at DESC) WHERE output_page_id IS NOT NULL;
