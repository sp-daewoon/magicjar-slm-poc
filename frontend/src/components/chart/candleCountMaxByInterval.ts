/**
 * candleCountMaxByInterval — timeframe별 lazy backfill 누적 max cap 매트릭스
 *
 * ADR 0093 §3-1 LOCK (chart-curator single owner — ADR 0085 §2-1):
 *
 * | Interval | Max cap | KIS cover (max 기준)        |
 * |----------|---------|------------------------------|
 * | 1m       | 4800    | 약 13 거래일 (분봉 KIS 30일) |
 * | 3m       | 3840    | 합산                          |
 * | 5m       | 3840    | 약 40 거래일 (합산 — ADR 0062) |
 * | 15m      | 3840    | 합산                          |
 * | 30m      | 3840    | 합산                          |
 * | 1h       | 3840    | 약 320 거래일 (합산)          |
 * | 90m      | 3840    | 합산                          |
 * | 1d       | 5000    | 약 20년                       |
 * | 1wk      | 2000    | 약 38년                       |
 * | 1mo      | 1200    | 약 100년                      |
 * | 1y       | 600     | 약 600년                      |
 *
 * 본 매트릭스 사용처:
 * - LightweightChart (chart-curator) — 가드 3 lazy backfill 호출 차단 (Phase 5)
 * - CandleCountSelector (frontend Phase 4) — 사용자 직접 입력 clamp 검증
 * - 후속 backend property와 동일 LOCK (ADR 0093 §4 backend-core 책임)
 *
 * 페르소나 lens 의무 (ADR 0085 §2-2-1):
 * - "기존 동작 영향": 변경 0 (신설 상수 매트릭스 — additive)
 * - "리스크 포인트": cap 값 잘못 산정 시 lazy backfill 조기 종료 → 사용자 인지(toast)로 안전 흡수
 * - "롤백 가능성": 본 파일 git revert 1회로 즉시 cap 무한대 동작
 *
 * 출처: ADR 0093 §3-1 (architect 합의 LOCK 2026-05-20)
 */
import type { CandleInterval } from '../../api/symbol'

/**
 * timeframe별 lazy backfill 누적 max cap.
 *
 * 사용자가 좌측 스크롤로 max cap 도달 시:
 *   - LightweightChart 가드 3 = onScrollLeft 호출 차단 (Phase 5 신설)
 *   - SymbolChart toast = "표시 봉수 최대 도달 ({max}봉)" 1회 (Phase 5 신설)
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

/**
 * 주어진 interval의 max cap을 반환. 미정의 interval은 보수적으로 1d(5000)로 fallback.
 *
 * 보수 페르소나 lens — "방어적 프로그래밍": 예외 throw 대신 fallback. fallback 발생 시 lazy backfill은
 * 정상 동작하되 cap 미설정 종목임. 운영 상 검출되면 본 매트릭스에 항목 추가 의무.
 */
export function getCandleCountMax(interval: CandleInterval): number {
  return CANDLE_COUNT_MAX_BY_INTERVAL[interval] ?? CANDLE_COUNT_MAX_BY_INTERVAL['1d']
}
