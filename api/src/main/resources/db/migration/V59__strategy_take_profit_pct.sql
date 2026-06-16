ALTER TABLE strategy
    ADD COLUMN IF NOT EXISTS take_profit_pct NUMERIC(9, 4);

COMMENT ON COLUMN strategy.take_profit_pct IS 'Position-aware take-profit exit threshold in percent. Separate from candle-only DSL sell_condition.';
