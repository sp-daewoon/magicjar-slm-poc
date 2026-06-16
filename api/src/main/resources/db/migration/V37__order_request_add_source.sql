-- V37: order_request.source 컬럼 add-only — T-MAN B-2-extended LOCK (ADR 0082)
--
-- ### 배경 (ADR 0082 §3-4 + §3-5)
-- 사용자 결정 정합 (2026-05-18 최고 관리자):
--   "데이터 상에선 사용자 주문이랑 자동매매주문의 타입이 구분되어야하는거 필수"
-- AUTO  : 자동매매 path (signal.generated.v1 → T-RISK ALLOWED → order.intent.v1)
-- MANUAL: 사용자 수동 주문 (POST /api/orders/manual → order.intent.v1)
--
-- ### add-only 안전 정합
-- - ADD COLUMN IF NOT EXISTS (V18 ADR 0042 충돌 패턴 재발 회피 — §15-1 LOCK)
-- - DEFAULT 'AUTO' → 기존 row는 모두 자동 attribution (T-O 본 트랙은 signal_id != 0 → 'AUTO' 의미적 정확)
-- - CHECK 제약 (AUTO|MANUAL 한정) → 비정상 값 차단
-- - INDEX add-only → source filter 쿼리 효율화 (frontend GET /api/orders?source=MANUAL)
--
-- ### 채번 사유 (V37 — spec §3-5는 V34 명시지만 main 기준 V35·V36 점유 발견)
-- 2026-05-18 main 시점 점유: V31·V32·V33 + V35·V36 (V34는 공백). T-MAN 신규 migration은 다음 비점유 번호 V37·V38.
-- spec §3-5 LOCK은 baseline 명세 — 실제 채번은 main 점유 검증 후 backend-core 자율 결정 (§3-5 본문 절차 정합).
--
-- ### KIS-first raw 출처 (헌법 §3)
-- 본 migration은 KIS endpoint 신규 호출 0건 — 데이터 분류 column add-only (raw 인용 N/A).
-- 단 source=MANUAL 주문의 KIS 호출은 ADR 0068 §14-5 베이스라인 재사용
-- (TR_ID TTTC0012U/VTTC0012U/TTTC0011U/VTTC0011U · /uapi/domestic-stock/v1/trading/order-cash).
--
-- AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082)

ALTER TABLE order_request
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'AUTO'
        CHECK (source IN ('AUTO','MANUAL'));

CREATE INDEX IF NOT EXISTS idx_order_request_source ON order_request (source);

COMMENT ON COLUMN order_request.source IS
    'ADR 0082 T-MAN B-2-extended LOCK — AUTO(자동매매 signal path) vs MANUAL(사용자 수동 주문 POST /api/orders/manual). 사용자 결정 2026-05-18 정합.';
COMMENT ON INDEX idx_order_request_source IS
    'ADR 0082 T-MAN B-2-extended — GET /api/orders?source=MANUAL filter 효율화 + audit 집계 path.';
