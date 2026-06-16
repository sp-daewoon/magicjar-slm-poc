import { describe, expect, it } from 'vitest'
import type { IndicatorResultDto } from '../../../api/indicator'
import { buildSmartMoneyZones } from '../SmartMoneyZoneOverlay'

function result(series: IndicatorResultDto['series']): IndicatorResultDto {
  return {
    spec: { name: 'fvg', params: {} },
    source: 'smart-money',
    series,
  }
}

describe('buildSmartMoneyZones', () => {
  it('같은 FVG 구간을 하나의 우측 확장 박스로 압축한다', () => {
    const zones = buildSmartMoneyZones(
      result([
        {
          time: '2026-05-29T00:00:00Z',
          values: {
            bullish_active: '1',
            bullish_top: '100',
            bullish_bottom: '95',
            bullish_mid: '97.5',
            bullish_fill_pct: '0',
          },
        },
        {
          time: '2026-05-29T00:01:00Z',
          values: {
            bullish_active: '1',
            bullish_top: '100',
            bullish_bottom: '95',
            bullish_mid: '97.5',
            bullish_fill_pct: '25',
          },
        },
      ]),
      'fvg',
    )

    expect(zones).toHaveLength(1)
    expect(zones[0]).toMatchObject({
      kind: 'fvg',
      direction: 'bullish',
      startTime: '2026-05-29T00:00:00Z',
      endTime: '2026-05-29T00:01:00Z',
      top: 100,
      bottom: 95,
      fillPct: 25,
      extendsRight: true,
    })
  })

  it('비활성 값이 나오면 Order Block 박스를 종료한다', () => {
    const zones = buildSmartMoneyZones(
      result([
        {
          time: '2026-05-29T00:00:00Z',
          values: {
            bearish_active: '1',
            bearish_top: '110',
            bearish_bottom: '105',
            bearish_mid: '107.5',
            bearish_fill_pct: '10',
          },
        },
        {
          time: '2026-05-29T00:01:00Z',
          values: {
            bearish_active: '0',
            bearish_top: '0',
            bearish_bottom: '0',
            bearish_mid: '0',
            bearish_fill_pct: '0',
          },
        },
      ]),
      'order_block',
    )

    expect(zones).toHaveLength(1)
    expect(zones[0]).toMatchObject({
      kind: 'order_block',
      direction: 'bearish',
      startTime: '2026-05-29T00:00:00Z',
      endTime: '2026-05-29T00:00:00Z',
      top: 110,
      bottom: 105,
      extendsRight: false,
    })
  })
})
