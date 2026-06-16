# Candle Operating Policy Options

Date: 2026-06-06 KST

This note answers the current operational question: AI can find a valid stock, but the selected interval/window may have zero stored candles. The goal is to keep strategy research usable while preventing the candle table from becoming the whole system.

## Current Evidence

Current local DB snapshot before V69:

| Metric | Value |
| --- | ---: |
| `candle` total size | 849 MB |
| `candle` heap | 288 MB |
| `candle` indexes | 561 MB |
| estimated rows | 2,409,352 |
| 1m rows | 1,369,230 |
| 1m codes | 20 |
| 1m range | 2025-12-08 to 2026-06-05 |

After V69 was applied locally:

| Metric | Value |
| --- | ---: |
| `candle` total size | 719 MB |
| `candle` heap | 288 MB |
| `candle` indexes | 415 MB |
| removed duplicate index payload | about 167 MB |

Index state:

| Index | Shape | Size | Reads | Decision |
| --- | --- | ---: | ---: | --- |
| `uq_candle_code_interval_ts` | `(code, interval, ts)` unique | 167 MB | high | keep, required by uniqueness and `ON CONFLICT` |
| `idx_candle_code_interval_ts_v8` | `(code, interval, ts DESC)` | 167 MB | active | keep for latest-candle reads |
| `idx_candle_code_interval_ts` | `(code, interval, ts DESC)` | 167 MB | 0 | drop via V69 |

The duplicate index is not the only future risk, but dropping it is a clean first move: less disk, less write amplification, and no functional loss.

## Key Conclusion

Storing 1m as the raw source and precomputing `3m/5m/15m/30m/90m/1h/4h/12h` is the right architecture for the managed universe.

Why it is good:

- One raw source of truth keeps KIS data reconciliation simple.
- Backtests, strategy research, screeners, and realtime signals read the same materialized candles.
- Read-time aggregation would move cost into user clicks and realtime evaluation.
- Current derived intraday rows are smaller than 1m rows; the table grows because the universe widens, not because 5m or 15m exist.

Where it becomes dangerous:

- All KRX stocks with 1m for 6 months is a different scale. Current 20-code 1m data is about 1.37M rows. At 2,500 stocks, that is roughly 170M 1m rows before derived intervals.
- With derived intervals and current index density, this becomes tens of GB to 100GB-class storage in Docker/Postgres. That is too heavy for the current system unless partitioning, retention, and dedicated ops are added.

## Operating Cases

### Case A: Current Narrow Universe

Keep intraday candles only for watchlist, holdings, active strategies, and manually opened symbols.

Pros:

- Lowest storage pressure.
- Works well for automatic trading on a controlled set.
- Easy to backup and migrate.

Cons:

- AI may find valid symbols with zero candles.
- User must collect candles before research.

Best use:

- Current stage and first real-trade rollout.

### Case B: Recommended Tiered Universe

Use two layers:

| Layer | Symbols | Intervals | Retention |
| --- | --- | --- | --- |
| Market baseline | all listed common stocks | `1d`, maybe `1wk/1mo` | long term |
| Managed intraday | watchlist, holdings, active strategies, kept AI candidates | `1m` + derived intervals | bounded |

Recommended initial retention:

| Interval | Retention |
| --- | --- |
| `1m` | 90 trading days for normal managed universe; manual 6 months only for active research |
| `3m`, `5m` | 180 trading days |
| `15m`, `30m`, `90m`, `1h`, `4h`, `12h` | 1 year |
| `1d`, `1wk`, `1mo`, `1y` | long term |

Pros:

- AI discovery can cover all stocks via daily data.
- Intraday load stays bounded.
- Works with current 1m source + aggregate design.

Cons:

- Needs an explicit "collect candles for this candidate" workflow.
- Needs retention cleanup jobs before the universe grows.

Best use:

- Recommended path for long-term magicJar.

### Case C: All Stocks 1m Intraday

Collect 1m for every KRX stock and derive all intervals.

Pros:

- Any AI candidate is instantly research-ready.
- No missing-candle surprise.

Cons:

- Storage and write amplification become the main system.
- KIS rate limits and reconciliation windows become operational blockers.
- Docker volume loss and migration become high-risk.

Best use:

- Not recommended until the system has partitioning, retention automation, external backups, and a dedicated data-ops budget.

### Case D: Store Only 1m, Aggregate On Read

Keep only 1m in DB and build `5m/15m/...` when the UI or strategy asks.

Pros:

- Less DB storage.
- No derived interval write amplification.

Cons:

- Slow for chart/backtest/research clicks.
- Realtime signal paths become CPU-sensitive.
- Different features may aggregate slightly differently unless heavily guarded.
- Backtest repeatability gets more fragile.

Best use:

- Not ideal for this project. It trades storage cost for latency and consistency risk.

### Case E: Store 1m + Precomputed Aggregates For Managed Universe

This is the current direction with a stricter universe policy.

Pros:

- Fast reads.
- Deterministic research/backtest/realtime behavior.
- Bounded if retention and managed universe rules are enforced.

Cons:

- Requires cleanup jobs.
- Requires candidate readiness UI.

Best use:

- Recommended production posture.

## AI Discovery Readiness Model

AI discovery should not mean "ready to research." It should produce a candidate with readiness:

| State | Meaning | UI behavior |
| --- | --- | --- |
| `MATCHED_READY` | instrument matched and selected window has candles | selectable |
| `MATCHED_NO_CANDLES` | instrument matched but candle count is 0 | show `candle 0`, block research selection |
| `UNMATCHED` | AI output did not map to instrument master | show verification state |
| `COLLECTING` | candle backfill queued | disable research until ready |
| `STALE` | candles exist but latest candle is too old | allow chart, warn before research |

Immediate implementation should show candle count on AI candidate cards. The next step should add a `collect candles` action that queues 1m backfill, then regenerates aggregates.

## Query Contract

Safe query contract:

- Strategy evaluation and realtime signal history: `WHERE code=? AND interval=? ORDER BY ts DESC LIMIT N`
- Research and backtest ranges: `WHERE code=? AND interval=? AND ts BETWEEN ? AND ? ORDER BY ts ASC`
- Full `findByCodeAndInterval`: only for aggregation rebuild/admin jobs, not normal user evaluation paths

This keeps high-frequency paths from reading tens of thousands of rows per request.

## Decision Proposal

Adopt Case B + Case E:

1. Keep all-stock daily candles as the broad discovery baseline.
2. Keep intraday candles only for managed symbols.
3. When AI finds a symbol with no candles, show it as valid but not research-ready.
4. Add on-demand collection for kept/selected AI candidates.
5. Enforce retention before expanding beyond roughly 50 intraday symbols.
6. Use partitioning only after retention is working and the candle table crosses a sustained multi-GB threshold.

## Near-Term Work Plan

1. V69: drop `idx_candle_code_interval_ts`.
2. Replace remaining evaluation full-fetch paths with latest-N queries.
3. Add AI candidate candle count and block selection when count is 0.
4. Add a candidate-level candle collection action.
5. Add retention cleanup for 1m/3m/5m before expanding the managed universe.
6. Re-run DB size/index audit after one week of real usage.
