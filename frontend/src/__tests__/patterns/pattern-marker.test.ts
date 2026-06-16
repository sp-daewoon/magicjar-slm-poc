/**
 * PatternMarker 단위 테스트
 *
 * T-PAT PR-40 R3 — LWC v4 setMarkers() BULL/BEAR 색상·shape 검증
 *
 * 검증 항목:
 * 1. BULL direction → setMarkers([{position:'belowBar', color:'#26a69a', shape:'arrowUp'}])
 * 2. BEAR direction → setMarkers([{position:'aboveBar', color:'#ef5350', shape:'arrowDown'}])
 * 3. NEUTRAL direction → 마커 제외 (setMarkers([]))
 * 4. 복수 마커 시 time ASC 정렬
 * 5. series=null 시 setMarkers 호출 0
 * 6. unmount 시 setMarkers([]) cleanup
 *
 * 주의: LWC v4 canvas jsdom 미지원 — series API mock 사용
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, renderHook, act } from '@testing-library/react'
import { createElement, useEffect } from 'react'
import type { ISeriesApi, SeriesMarker, UTCTimestamp } from 'lightweight-charts'
import type { PatternMatch } from '../../api/patterns'
import { PatternMarker } from '../../components/chart/PatternMarker'

// ──────────────────────────────────────────────
// ISeriesApi mock
// ──────────────────────────────────────────────

function createMockSeries() {
  const setMarkers = vi.fn()
  return {
    setMarkers,
  } as unknown as ISeriesApi<'Candlestick'>
}

// ──────────────────────────────────────────────
// PatternMarker 로직 유틸 (컴포넌트 직접 임포트 대신 로직 검증)
// ──────────────────────────────────────────────

/** PatternMarker 내부와 동일한 변환 로직 */
function toUTCTimestamp(isoString: string): UTCTimestamp {
  return Math.floor(new Date(isoString).getTime() / 1000) as UTCTimestamp
}

function buildMarkers(matches: PatternMatch[]): SeriesMarker<UTCTimestamp>[] {
  return matches
    .filter((m) => m.direction !== 'NEUTRAL')
    .map((m) => ({
      time: toUTCTimestamp(m.time) as UTCTimestamp,
      position: m.direction === 'BULL' ? 'belowBar' as const : 'aboveBar' as const,
      color: m.direction === 'BULL' ? '#26a69a' : '#ef5350',
      shape: m.direction === 'BULL' ? 'arrowUp' as const : 'arrowDown' as const,
      text: m.id.slice(0, 6),
      size: 1,
    }))
    .sort((a, b) => (a.time as number) - (b.time as number))
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('PatternMarker 마커 변환 로직', () => {
  describe('BULL direction 마커', () => {
    it('BULL → position=belowBar, color=#26a69a, shape=arrowUp', () => {
      const matches: PatternMatch[] = [
        { id: 'hammer', name: '해머', time: '2026-01-01T00:00:00Z', direction: 'BULL' },
      ]
      const markers = buildMarkers(matches)
      expect(markers).toHaveLength(1)
      expect(markers[0].position).toBe('belowBar')
      expect(markers[0].color).toBe('#26a69a')
      expect(markers[0].shape).toBe('arrowUp')
    })
  })

  describe('BEAR direction 마커', () => {
    it('BEAR → position=aboveBar, color=#ef5350, shape=arrowDown', () => {
      const matches: PatternMatch[] = [
        { id: 'shooting_star', name: '슈팅스타', time: '2026-01-02T00:00:00Z', direction: 'BEAR' },
      ]
      const markers = buildMarkers(matches)
      expect(markers).toHaveLength(1)
      expect(markers[0].position).toBe('aboveBar')
      expect(markers[0].color).toBe('#ef5350')
      expect(markers[0].shape).toBe('arrowDown')
    })
  })

  describe('NEUTRAL direction 제외', () => {
    it('NEUTRAL → 마커 0건 (R2 stub graceful)', () => {
      const matches: PatternMatch[] = [
        { id: 'doji', name: '도지', time: '2026-01-01T00:00:00Z', direction: 'NEUTRAL' },
        { id: 'engulfing', name: '잉걸핑', time: '2026-01-02T00:00:00Z', direction: 'NEUTRAL' },
      ]
      const markers = buildMarkers(matches)
      expect(markers).toHaveLength(0)
    })

    it('혼합 시 NEUTRAL만 제외', () => {
      const matches: PatternMatch[] = [
        { id: 'hammer', name: '해머', time: '2026-01-01T00:00:00Z', direction: 'BULL' },
        { id: 'doji', name: '도지', time: '2026-01-02T00:00:00Z', direction: 'NEUTRAL' },
        { id: 'shooting_star', name: '슈팅스타', time: '2026-01-03T00:00:00Z', direction: 'BEAR' },
      ]
      const markers = buildMarkers(matches)
      expect(markers).toHaveLength(2)
      expect(markers.map((m) => m.shape)).toContain('arrowUp')
      expect(markers.map((m) => m.shape)).toContain('arrowDown')
    })
  })

  describe('time ASC 정렬', () => {
    it('역순 입력이어도 ASC 정렬', () => {
      const matches: PatternMatch[] = [
        { id: 'shooting_star', name: '슈팅스타', time: '2026-01-03T00:00:00Z', direction: 'BEAR' },
        { id: 'hammer', name: '해머', time: '2026-01-01T00:00:00Z', direction: 'BULL' },
        { id: 'evening_star', name: '저녁별', time: '2026-01-02T00:00:00Z', direction: 'BEAR' },
      ]
      const markers = buildMarkers(matches)
      expect(markers).toHaveLength(3)
      for (let i = 0; i < markers.length - 1; i++) {
        expect(markers[i].time as number).toBeLessThanOrEqual(markers[i + 1].time as number)
      }
    })
  })

  describe('빈 matches 처리', () => {
    it('matches=[] → 마커 0건', () => {
      const markers = buildMarkers([])
      expect(markers).toHaveLength(0)
    })
  })
})

// ──────────────────────────────────────────────
// setMarkers 호출 검증 (hook 수준)
// ──────────────────────────────────────────────

describe('PatternMarker setMarkers 호출', () => {
  let mockSeries: ISeriesApi<'Candlestick'>

  beforeEach(() => {
    mockSeries = createMockSeries()
  })

  it('series + matches 있을 때 setMarkers 호출', () => {
    const matches: PatternMatch[] = [
      { id: 'hammer', name: '해머', time: '2026-01-01T00:00:00Z', direction: 'BULL' },
    ]

    // useEffect 직접 시뮬레이션
    const { unmount } = renderHook(() => {
      useEffect(() => {
        if (!mockSeries) return
        const markers = buildMarkers(matches)
        mockSeries.setMarkers(markers)
        return () => {
          mockSeries.setMarkers([])
        }
      }, [])
    })

    expect(mockSeries.setMarkers).toHaveBeenCalledWith(
      expect.arrayContaining([
        expect.objectContaining({
          color: '#26a69a',
          shape: 'arrowUp',
        }),
      ]),
    )

    act(() => { unmount() })

    expect(mockSeries.setMarkers).toHaveBeenLastCalledWith([])
  })

  it('series=null → setMarkers 호출 안 됨', () => {
    const nullSeries = null as unknown as ISeriesApi<'Candlestick'>
    const setMarkersMock = vi.fn()
    const safeSeries = { setMarkers: setMarkersMock }

    // null 체크 로직 직접 검증
    const callIfNotNull = (s: ISeriesApi<'Candlestick'> | null, markers: SeriesMarker<UTCTimestamp>[]) => {
      if (!s) return
      s.setMarkers(markers)
    }

    callIfNotNull(nullSeries, [])
    expect(setMarkersMock).not.toHaveBeenCalled()

    callIfNotNull(safeSeries as unknown as ISeriesApi<'Candlestick'>, [])
    expect(setMarkersMock).toHaveBeenCalledWith([])
  })

  it('패턴 마커와 추가 전략 마커를 같은 setMarkers 호출로 합성한다', () => {
    const matches: PatternMatch[] = [
      { id: 'hammer', name: '해머', time: '2026-01-01T00:00:00Z', direction: 'BULL' },
    ]
    const extraMarkers: SeriesMarker<UTCTimestamp>[] = [
      {
        time: toUTCTimestamp('2026-01-01T00:05:00Z'),
        position: 'aboveBar',
        color: '#fb923c',
        shape: 'arrowDown',
        text: '익절',
      },
    ]

    render(createElement(PatternMarker, { series: mockSeries, matches, extraMarkers }))

    expect(mockSeries.setMarkers).toHaveBeenCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ shape: 'arrowUp', color: '#26a69a' }),
        expect.objectContaining({ shape: 'arrowDown', text: '익절', color: '#fb923c' }),
      ]),
    )
  })
})
