-- V35: order_execution.event_id 컬럼 add-only — ADR 0084 P0-3 (외부 코드 리뷰 결함 #4 WS race + 재연결 backfill)
--
-- ### 배경 (ADR 0084 §2-3 P0-3)
-- KIS WS H0STCNI0/H0STCNI9 체결 통보가 REST submit ACK 응답보다 먼저 도달 가능 (race condition).
-- + 재연결 시점에 누락된 체결 통보 backfill 메커니즘 부재 → 자본 audit 깨짐 risk.
--
-- ### event_id UNIQUE 의미
-- KIS WS 체결 통보 1건 = `{kis_order_no}:{stck_cntg_hour}:{cntg_qty}` 또는 `{kis_order_no}:seq` 합성 키.
-- 재연결 backfill 시 동일 체결이 두 경로(WS + REST inquire-daily-ccld)로 들어와도
-- event_id UNIQUE constraint가 중복 INSERT 자동 차단 (멱등 LOCK).
--
-- ### NULL 허용 사유
-- 기존 V21 시점 합류 row(미보강 운영 데이터)는 event_id NULL. add-only 정합 + 후속 backfill cron 가능.
-- 신규 listener 진입 row는 항상 set.
--
-- ### IF NOT EXISTS 의무 (§15-1 LOCK 정합)
-- 컬럼 ADD COLUMN IF NOT EXISTS · INDEX IF NOT EXISTS — V18 ADR 0042 충돌 패턴 재발 회피.
--
-- ### KIS-first raw 출처 (헌법 §3)
-- - WS 체결 통보 26 컬럼: examples_user/domestic_stock/domestic_stock_functions_ws.py L389~L446 (def ccnl_notice)
-- - 재연결 backfill REST: examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py
--   endpoint: /uapi/domestic-stock/v1/trading/inquire-daily-ccld
--   tr_id 3개월 이내: TTTC0081R(REAL) / VTTC0081R(MOCK)
--
-- AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0084 §2-8)

ALTER TABLE order_execution
    ADD COLUMN IF NOT EXISTS event_id VARCHAR(64);

-- UNIQUE 부분 인덱스 (NULL 허용 + non-null만 UNIQUE) — 멱등성 LOCK
CREATE UNIQUE INDEX IF NOT EXISTS uq_execution_event_id
    ON order_execution (event_id)
    WHERE event_id IS NOT NULL;

COMMENT ON COLUMN order_execution.event_id IS
    'KIS 체결 1건 멱등 키 (ADR 0084 P0-3) — {kis_order_no}:{stck_cntg_hour}:{cntg_qty} 합성. WS + REST backfill 양 경로 dedup LOCK.';
