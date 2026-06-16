-- V13 — backtest_trade + backtest_position (ADR 0061 §3 결정: 3 테이블 분리 B-1 채택)
-- 백테스트 매매 시계열 + 포지션 시계열 저장 테이블
-- CASCADE DELETE: backtest_run 삭제 시 연관 trade·position 일괄 삭제 (C-1 DELETE endpoint 정합)

CREATE TABLE backtest_trade (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT          NOT NULL REFERENCES backtest_run (id) ON DELETE CASCADE,
    ts              TIMESTAMPTZ     NOT NULL,
    side            VARCHAR(4)      NOT NULL,  -- BUY/SELL
    price           NUMERIC(19, 4)  NOT NULL,
    quantity        BIGINT          NOT NULL,
    fee             NUMERIC(19, 4),
    rule_signal     VARCHAR(80)
);

CREATE INDEX idx_backtest_trade_run_ts ON backtest_trade (run_id, ts);

CREATE TABLE backtest_position (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT          NOT NULL REFERENCES backtest_run (id) ON DELETE CASCADE,
    open_ts         TIMESTAMPTZ     NOT NULL,
    close_ts        TIMESTAMPTZ,
    entry_price     NUMERIC(19, 4)  NOT NULL,
    exit_price      NUMERIC(19, 4),
    quantity        BIGINT          NOT NULL,
    pnl             NUMERIC(19, 4),
    return_pct      NUMERIC(7, 4)
);

CREATE INDEX idx_backtest_position_run_open ON backtest_position (run_id, open_ts);
