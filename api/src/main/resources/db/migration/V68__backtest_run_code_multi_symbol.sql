-- V68 — backtest_run.code 다종목 universe 저장 허용
-- 기존 VARCHAR(8)은 단일 6자리 종목코드만 담을 수 있어,
-- POST /api/backtests universe=["005930","000660"] 저장 시 길이 초과로 500이 발생했다.

ALTER TABLE backtest_run
    ALTER COLUMN code TYPE VARCHAR(512);
