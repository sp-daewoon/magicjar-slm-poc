ALTER TABLE backtest_run
    ADD COLUMN IF NOT EXISTS strategy_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_backtest_run_strategy_ready
    ON backtest_run (strategy_id, user_id, status, completed_at DESC);
