-- P3d(AGP-55): 리뷰 반려 예산을 인프라 재시도(attempt)와 분리한다.
-- reject_count: 이 작업 계보가 리뷰에서 반려된 누적 횟수. 반려-fix run은 원 TASK의 값 +1을, 재시도·게이트 승인·사람 재개
--   continuation과 REVIEW run은 직전 run의 값을 그대로 승계한다. REVIEW_REJECT_MAX를 넘는 반려는 fix 대신 BLOCKED로 사람에게 넘긴다.
--   attempt는 이제 계보 순번일 뿐 예산이 아니다 — 사고형 실패는 재시도 없이 즉시 BLOCKED(재시도 3회 정책 폐기).
-- 기존 행은 0 — 과거 반려 이력을 attempt에서 역산하지 않는다(재시도와 반려가 섞여 있어 구분할 수 없다).
ALTER TABLE run ADD COLUMN reject_count INT NOT NULL DEFAULT 0;
