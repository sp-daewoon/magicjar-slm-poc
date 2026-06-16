ALTER TABLE strategy
    ADD COLUMN IF NOT EXISTS fixed_stop_loss_pct NUMERIC(9, 4);

COMMENT ON COLUMN strategy.fixed_stop_loss_pct IS 'Mandatory auto-trading hard stop threshold in percent from entry price. Used as the default RiskPlan stop-loss when no chart-range stop is configured.';
