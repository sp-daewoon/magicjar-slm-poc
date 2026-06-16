import { describe, expect, it } from 'vitest'
import {
  ONE_MINUTE_EMPTY_REFETCH_INTERVAL_MS,
  resolveOneMinuteEmptyRefetchInterval,
} from '../useSymbolCandles'

describe('useSymbolCandles', () => {
  it('1m cold-start 0건이면 자동 재조회 polling을 켠다', () => {
    expect(
      resolveOneMinuteEmptyRefetchInterval('1m', {
        pages: [
          {
            candles: [],
          },
        ],
      }),
    ).toBe(ONE_MINUTE_EMPTY_REFETCH_INTERVAL_MS)
  })

  it('1m 데이터가 생기면 자동 재조회 polling을 끈다', () => {
    expect(
      resolveOneMinuteEmptyRefetchInterval('1m', {
        pages: [
          {
            candles: [{ ts: '2026-06-10T00:00:00Z' }],
          },
        ],
      }),
    ).toBe(false)
  })

  it('1m이 아닌 차트는 empty polling을 켜지 않는다', () => {
    expect(
      resolveOneMinuteEmptyRefetchInterval('1d', {
        pages: [
          {
            candles: [],
          },
        ],
      }),
    ).toBe(false)
  })
})
