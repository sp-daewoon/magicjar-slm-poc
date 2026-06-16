ALTER TABLE candle DROP CONSTRAINT IF EXISTS chk_candle_interval;

ALTER TABLE candle
    ADD CONSTRAINT chk_candle_interval CHECK (
        interval IN ('1m', '2m', '3m', '5m', '15m', '30m', '90m', '1h', '4h', '12h', '1d', '1wk', '1mo', '1y')
    );
