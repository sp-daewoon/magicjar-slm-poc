-- V64: 전략별 포지션 귀속 — strategy_position 테이블 (ADR 0110, 분할매수 R1)
--
-- spec 2026-06-05-scale-in-exit-ownership-spec.md §4 (본문 V65 → orchestrator 확정 V64로 당김.
-- 근거: origin/main 최신 V63, V64(market_calendar)는 미머지 별 브랜치 → V64 가용. ADR 0102 재확인 완료).
--
-- 기존 position(user+code 합산, KIS 잔고 권위)은 유지. 그 아래 전략별 lot ledger를 둔다.
-- 불변식: sum(strategy_position.quantity WHERE user+code) == position.quantity (정합 검증 — drift 시 KIS 권위).

CREATE TABLE IF NOT EXISTS strategy_position (
    id                     BIGSERIAL      NOT NULL,
    user_id                VARCHAR(20)    NOT NULL,
    strategy_id            BIGINT         NOT NULL,
    code                   VARCHAR(8)     NOT NULL,
    quantity               INTEGER        NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    avg_price              NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (avg_price >= 0),
    filled_lots            INTEGER        NOT NULL DEFAULT 0 CHECK (filled_lots >= 0),
    realized_pnl           NUMERIC(19, 4) NOT NULL DEFAULT 0,
    executed_exit_stages   JSONB          NOT NULL DEFAULT '[]',
    opened_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_strategy_position PRIMARY KEY (id),
    CONSTRAINT uq_strategy_position_user_strategy_code UNIQUE (user_id, strategy_id, code),
    CONSTRAINT fk_strategy_position_user FOREIGN KEY (user_id)
        REFERENCES user_account (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_strategy_position_strategy FOREIGN KEY (strategy_id)
        REFERENCES strategy (id) ON DELETE CASCADE,
    CONSTRAINT fk_strategy_position_instrument FOREIGN KEY (code)
        REFERENCES instrument (code)
);

-- 정합 검증용(user+code 합산 vs position) — drift 탐지 쿼리 패턴.
CREATE INDEX IF NOT EXISTS idx_strategy_position_user_code ON strategy_position (user_id, code);
-- 전략 단위 조회(전략 청산·삭제 시 lot 일괄).
CREATE INDEX IF NOT EXISTS idx_strategy_position_strategy ON strategy_position (strategy_id);

COMMENT ON TABLE strategy_position IS 'ADR 0110 — 전략별 포지션 귀속 lot ledger. position(KIS 권위 합산) 아래의 전략 단위 평단/수량/실현손익 추적.';
COMMENT ON COLUMN strategy_position.quantity IS '이 전략이 현재 보유한 수량 (분할 누적 - 청산). 0이면 전량 청산(행 유지, 재진입 시 새 사이클).';
COMMENT ON COLUMN strategy_position.avg_price IS '이 전략의 평단 (분할 매수로 가중평균 갱신). quantity=0이면 reset.';
COMMENT ON COLUMN strategy_position.filled_lots IS 'scale-in 진행도 — 지금까지 체결된 분할 회차 수. totalSlices 도달 시 추가 진입 무시.';
COMMENT ON COLUMN strategy_position.realized_pnl IS '이 전략의 누적 실현 손익 (부분/전량 청산 누적).';
COMMENT ON COLUMN strategy_position.executed_exit_stages IS '발화된 PartialExit 단계 인덱스 배열 — 부분 익절 중복 발화 방지(체결 시점 마킹). filledLots(진입 카운터)와 대칭 구조. quantity=0이면 reset.';
