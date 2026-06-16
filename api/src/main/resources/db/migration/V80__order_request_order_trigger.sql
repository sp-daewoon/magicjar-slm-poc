-- V80: order_request — order_trigger 발사 출처 enum + trigger_ref_id 유발 근거 실키
--      (ADR 0115 §2-P2-⑥ 키 사슬 무결성 · spec 2026-06-10-execution-ledger-integrity §2-B-2 불변식 1)
--
-- ### 배경 (최고 관리자 직접 요구 2026-06-10)
-- *"전략이 사인 내면 몇 주 주문 내고, 몇 주가 그 전략으로 사지고 팔아졌는지를 키로 매핑 관리해야 한다.
--   매도했다는데 포지션 남고 매수했다는데 포지션 안 보이면 자동매매 시스템 실격."*
--
-- 현행 단절(spec §2-B-1): 자동청산 SELL이 signal_id=0 sentinel, 백필 stub이 strategy_id=0 sentinel로 발사
-- 출처를 데이터 차원에서 소실 → drift 부족 방향 귀속 판정이 "모호"로 빠짐(G7). sentinel(0)은 "키 없음을
-- 키 값으로 위장"하는 안티패턴. 본 migration은 발사 출처를 명시 enum + 실키로 보존한다.
--
-- ### 변경 (add-only · nullable 컬럼 2개 + 기존 행 best-effort backfill)
--   order_trigger VARCHAR(24) NULL  — STRATEGY_SIGNAL/AUTO_EXIT/STOP_LOSS/PARTIAL_TAKE_PROFIT/MANUAL/EXTERNAL
--   trigger_ref_id BIGINT     NULL  — 유발 근거 실키 (signal id / strategy id 등)
-- 두 컬럼 모두 nullable — 기존 sentinel 컬럼(signal_id·strategy_id·source) 병존 유지(ADR 0115 §2-P2-X rollback:
-- 읽기 경로만 enum 우선·미존재 시 legacy fallback, 롤백 시 enum 무시). signal_id 컬럼 폐기 0(점진 전환).
--
-- ### 기존 행 backfill 분류 규칙 (best-effort — ADR 0115 §2-P2-⑥-c)
--   source='EXTERNAL'                         → EXTERNAL          (외부 흡수 — trigger_ref_id null)
--   source='MANUAL'                           → MANUAL            (수동 — trigger_ref_id null)
--   source='AUTO' AND signal_id > 0           → STRATEGY_SIGNAL   (시그널 파생 — trigger_ref_id = signal_id)
--   source='AUTO' AND signal_id = 0 AND
--     idempotency_key LIKE 'auto-sl:%'        → STOP_LOSS         (손절 watcher — trigger_ref_id = strategy_id)
--   source='AUTO' AND signal_id = 0 (그 외)   → AUTO_EXIT         (자동 익절 추정 — trigger_ref_id = strategy_id)
-- strategy_id=0 stub(legacy 백필)은 AUTO_EXIT 분류되나 trigger_ref_id는 NULLIF(strategy_id,0)으로 0 미저장.
--
-- ### 자본 안전 (헌법 §6·§11)
-- 본 migration은 주문 로직 변경 0 — 키 컬럼 add + 분류만. 자본 흐름·체결·잔고 영향 없음.
--
-- ### KIS-first raw 출처 (헌법 §3)
-- KIS endpoint 신규 호출 0건 — 데이터 키 정비만 (raw 인용 N/A).
--
-- AI 호출 0 — T-O 트랙 정책 (ADR 0115 §2-P2-⑥)

-- 1. 컬럼 추가 (add-only · nullable)
ALTER TABLE order_request
    ADD COLUMN IF NOT EXISTS order_trigger VARCHAR(24),
    ADD COLUMN IF NOT EXISTS trigger_ref_id BIGINT;

-- 2. CHECK 제약 — 허용 enum 값 (NULL 허용 = legacy 미분류 backward compat)
ALTER TABLE order_request
    DROP CONSTRAINT IF EXISTS chk_order_request_order_trigger;
ALTER TABLE order_request
    ADD CONSTRAINT chk_order_request_order_trigger
        CHECK (order_trigger IS NULL OR order_trigger IN
            ('STRATEGY_SIGNAL', 'AUTO_EXIT', 'STOP_LOSS', 'PARTIAL_TAKE_PROFIT', 'MANUAL', 'EXTERNAL'));

-- 3. 기존 행 best-effort backfill (sentinel·source 조합 → trigger 분류)
UPDATE order_request
SET order_trigger =
        CASE
            WHEN source = 'EXTERNAL' THEN 'EXTERNAL'
            WHEN source = 'MANUAL' THEN 'MANUAL'
            WHEN source = 'AUTO' AND signal_id > 0 THEN 'STRATEGY_SIGNAL'
            WHEN source = 'AUTO' AND signal_id = 0 AND idempotency_key LIKE 'auto-sl:%' THEN 'STOP_LOSS'
            ELSE 'AUTO_EXIT'
        END,
    trigger_ref_id =
        CASE
            WHEN source = 'AUTO' AND signal_id > 0 THEN NULLIF(signal_id, 0)
            WHEN source = 'AUTO' AND signal_id = 0 THEN NULLIF(strategy_id, 0)
            ELSE NULL
        END
WHERE order_trigger IS NULL;

-- 4. 조회 인덱스 (매매일지·주문 조회 API trigger 필터 — add-only)
CREATE INDEX IF NOT EXISTS idx_order_request_order_trigger
    ON order_request (order_trigger);

COMMENT ON COLUMN order_request.order_trigger IS
    'ADR 0115 §2-P2-⑥ 키 사슬 무결성 — 발사 출처 enum. STRATEGY_SIGNAL/AUTO_EXIT/STOP_LOSS/PARTIAL_TAKE_PROFIT/MANUAL/EXTERNAL. 0-sentinel 폐지: "signal 없는 주문"은 허용하되 "출처 모르는 주문"은 금지. NULL=legacy 미분류(V80 이전 행).';
COMMENT ON COLUMN order_request.trigger_ref_id IS
    'ADR 0115 §2-P2-⑥ — 유발 근거 실키 (signal id / strategy id / position id 등). 자동청산도 청산 대상 근거 키 보존.';
