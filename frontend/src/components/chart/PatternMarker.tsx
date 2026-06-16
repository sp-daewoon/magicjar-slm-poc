/**
 * PatternMarker — LWC v4 캔들스틱 패턴 마커 overlay 컴포넌트
 *
 * PR-38 spec R3 §3 LOCK:
 *   - LWC v4 ISeriesApi.setMarkers() API 정합
 *   - direction BULL → 상승 마커 (녹색 #26a69a · triangleUp · "▲")
 *   - direction BEAR → 하락 마커 (빨강 #ef5350 · triangleDown · "▼")
 *   - direction NEUTRAL → 마커 제외 (R2 stub graceful)
 *   - 마커 text: 패턴 한국어 약어
 *   - DOM 렌더링 없음 — LWC API 직접 호출
 *
 * ADR 0025 §2-1 LWC v4 공식 API 한정
 * 룰 5 (W2): LLM provider import 0건
 *
 * props:
 *   series: ISeriesApi<'Candlestick'>  캔들스틱 시리즈 참조
 *   matches: PatternMatch[]            패턴 인식 결과 (direction=NEUTRAL 제외)
 */
import { useEffect } from 'react'
import type { ISeriesApi, SeriesMarker, UTCTimestamp } from 'lightweight-charts'
import type { PatternMatch } from '../../api/patterns'
import { PATTERN_CATALOG_MAP } from '../../api/patterns'

// ──────────────────────────────────────────────
// 마커 색상·모양 상수
// ──────────────────────────────────────────────

const BULL_COLOR = '#26a69a'  // 상승 녹색
const BEAR_COLOR = '#ef5350'  // 하락 빨강

// ──────────────────────────────────────────────
// 유틸 함수
// ──────────────────────────────────────────────

/** ISO 8601 → UTCTimestamp 변환 */
function toUTCTimestamp(isoString: string): UTCTimestamp {
  return Math.floor(new Date(isoString).getTime() / 1000) as UTCTimestamp
}

/** 패턴 한국어 이름을 마커 텍스트로 축약 */
function toMarkerText(match: PatternMatch): string {
  const catalog = PATTERN_CATALOG_MAP.get(match.id)
  if (catalog) {
    // 한국어 이름이 있으면 앞 2글자 + 방향 기호
    const korName = catalog.nameKo
    return korName.length > 3 ? korName.slice(0, 3) : korName
  }
  // catalog 없으면 id 앞 6자
  return match.id.slice(0, 6)
}

/** PatternMatch → LWC SeriesMarker 변환 */
function toSeriesMarker(match: PatternMatch): SeriesMarker<UTCTimestamp> | null {
  if (match.direction === 'NEUTRAL') return null

  const isBull = match.direction === 'BULL'

  return {
    time: toUTCTimestamp(match.time),
    position: isBull ? 'belowBar' : 'aboveBar',
    color: isBull ? BULL_COLOR : BEAR_COLOR,
    shape: isBull ? 'arrowUp' : 'arrowDown',
    text: toMarkerText(match),
    size: 1,
  }
}

// ──────────────────────────────────────────────
// PatternMarker 컴포넌트
// ──────────────────────────────────────────────

export interface PatternMarkerProps {
  /** LWC v4 캔들스틱 시리즈 참조 */
  series: ISeriesApi<'Candlestick'> | null
  /** 패턴 인식 결과 — NEUTRAL direction은 자동 제외 */
  matches: PatternMatch[]
  /** 패턴 외 차트 마커. 같은 candle series의 setMarkers 충돌을 피하기 위해 함께 합성한다. */
  extraMarkers?: SeriesMarker<UTCTimestamp>[]
}

/**
 * LWC v4 setMarkers()를 이용하여 캔들스틱 시리즈 위에 패턴 마커를 표시.
 *
 * - 마운트/matches 변경 시 setMarkers() 호출
 * - 언마운트 또는 series null 시 마커 제거
 * - DOM 렌더링 없음 (null 반환)
 */
export function PatternMarker({ series, matches, extraMarkers = [] }: PatternMarkerProps) {
  useEffect(() => {
    if (!series) return

    // NEUTRAL direction 제외 후 마커 변환
    const patternMarkers: SeriesMarker<UTCTimestamp>[] = matches
      .map(toSeriesMarker)
      .filter((m): m is SeriesMarker<UTCTimestamp> => m !== null)
    const markers: SeriesMarker<UTCTimestamp>[] = [...patternMarkers, ...extraMarkers]
      // LWC v4 정합: time ASC 정렬 필요
      .sort((a, b) => (a.time as number) - (b.time as number))

    try {
      series.setMarkers(markers)
    } catch {
      // LWC series 파괴된 경우 graceful 무시
    }

    return () => {
      // cleanup: 마커 제거
      try {
        series.setMarkers([])
      } catch {
        // graceful
      }
    }
  }, [series, matches, extraMarkers])

  return null // DOM 렌더링 없음 — LWC API 직접 호출
}
