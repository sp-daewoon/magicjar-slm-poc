-- =====================================================================
-- V54 — trendline extend_left
--
-- TradingView 스타일 추세선 확장 옵션: 왼쪽/오른쪽/양방향/없음.
-- V53의 extend_right와 짝을 맞추기 위해 extend_left를 add-only로 추가한다.
-- =====================================================================

ALTER TABLE trendline
    ADD COLUMN IF NOT EXISTS extend_left BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN trendline.extend_left IS '추세선 왼쪽 확장 여부 — TradingView 스타일 확장 옵션';
