/**
 * candleCountMaxByInterval — timeframe별 차트 봉수 max cap 매트릭스 + clamp 헬퍼
 *
 * ADR 0093 §3-1 결정 LOCK (timeframe별 max cap 매트릭스):
 *   - 1m: 4800 (약 13 거래일 — KIS 분봉 distinct 한도 30 거래일 정합)
 *   - 3m·5m·15m·30m·90m·1h: 3840 (합산 가공 — ADR 0062)
 *   - 1d: 5000 (약 20년)
 *   - 1wk: 2000 (약 38년)
 *   - 1mo: 1200 (약 100년)
 *   - 1y: 600 (약 600년)
 *
 * Phase 4 (frontend) — 사용자 입력 봉수 clamp 정합 단일 출처
 *   - CandleCountSelector 직접입력 input clamp
 *   - useSymbolCandles pageSize prop clamp 정합
 *   - 위반 시 max로 자동 정정 + (UI 측에서) toast 권고
 *
 * backend cap (ADR 0093 §결정 LOCK + Phase 3 backend):
 *   - api endpoint `/candles?limit=<n>` cap 5000 (api/controller/SymbolController.kt)
 *   - 어떤 timeframe도 5000 초과 불가 (안전망)
 */
import type { CandleInterval } from '../../api/symbol'

/**
 * 전역 default (모든 timeframe 공통) — 사용자 조작 전 초기 진입 봉수.
 *
 * ADR 0120 §6-2 (2026-06-10 사용자 결재): 조작 전 default 200.
 *   사용자가 콤보/직접입력으로 조작한 마지막 값만 localStorage에 저장되며, 저장값이 있으면 그 값을
 *   복원하고 없으면 본 default(200)로 진입한다. (직전 ADR 0093 §Phase 4-B 의 600 default 는
 *   "마지막 사용값 우선" 정책으로 일관 정리 — 저장값 부재 시에만 200.)
 */
export const DEFAULT_CANDLE_COUNT = 200

/**
 * timeframe별 max cap (ADR 0093 §3-1 매트릭스 LOCK).
 *
 * 직접입력 시 이 값을 초과하면 max로 clamp.
 */
export const CANDLE_COUNT_MAX_BY_INTERVAL: Readonly<Record<CandleInterval, number>> = Object.freeze({
  '1m': 4800,
  '3m': 3840,
  '5m': 3840,
  '15m': 3840,
  '30m': 3840,
  '90m': 3840,
  '1h': 3840,
  '4h': 2000,
  '12h': 1200,
  '1d': 5000,
  '1wk': 2000,
  '1mo': 1200,
  '1y': 600,
})

/** 최소 봉수 — 1 미만은 의미 없음. */
export const MIN_CANDLE_COUNT = 1

/**
 * 사용자 입력 봉수 clamp.
 *
 * @param value 사용자 입력값 (정수)
 * @param interval timeframe
 * @returns [MIN_CANDLE_COUNT, max(interval)] 범위로 정정한 값
 *
 * NaN/음수/0 입력 시 default 600 회귀 안전.
 */
export function clampCandleCount(value: number, interval: CandleInterval): number {
  if (!Number.isFinite(value) || value <= 0) return DEFAULT_CANDLE_COUNT
  const max = CANDLE_COUNT_MAX_BY_INTERVAL[interval]
  const intValue = Math.floor(value)
  if (intValue < MIN_CANDLE_COUNT) return MIN_CANDLE_COUNT
  if (intValue > max) return max
  return intValue
}

/** localStorage key — 전역 1개 (symbol/timeframe 무관). ADR 0093 §결정 LOCK Phase 4-B. */
export const CANDLE_COUNT_STORAGE_KEY = 'magicjar:chart:candleCount'

/**
 * localStorage에서 저장된 사용자 선택 봉수 읽기.
 *
 * @returns 저장값 (없거나 invalid 시 DEFAULT_CANDLE_COUNT)
 *
 * SSR 안전 — typeof window 가드.
 */
export function readStoredCandleCount(): number {
  if (typeof window === 'undefined') return DEFAULT_CANDLE_COUNT
  try {
    const raw = window.localStorage.getItem(CANDLE_COUNT_STORAGE_KEY)
    if (!raw) return DEFAULT_CANDLE_COUNT
    const parsed = Number.parseInt(raw, 10)
    if (!Number.isFinite(parsed) || parsed <= 0) return DEFAULT_CANDLE_COUNT
    return parsed
  } catch {
    // localStorage 접근 차단 (private mode 등) — default 회귀
    return DEFAULT_CANDLE_COUNT
  }
}

/**
 * localStorage에 사용자 선택 봉수 저장.
 *
 * 실패(quota·private mode) 시 silent skip — 차트 동작은 props로 즉시 reflect 되므로 graceful.
 */
export function writeStoredCandleCount(value: number): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(CANDLE_COUNT_STORAGE_KEY, String(value))
  } catch {
    // ignore — props 경로로 즉시 reflect
  }
}

/** preset 옵션 (Phase 4-A select dropdown) — ADR 0093 §결정 LOCK + ADR 0120 §6-2 default 200 추가. */
export const CANDLE_COUNT_PRESETS: ReadonlyArray<number> = [200, 300, 600, 1200, 3000]
