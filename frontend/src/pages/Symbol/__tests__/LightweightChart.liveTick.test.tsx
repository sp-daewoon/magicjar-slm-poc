import { render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LightweightChart } from '../LightweightChart'

const chartMocks = vi.hoisted(() => {
  const candleSetData = vi.fn()
  const candleUpdate = vi.fn()
  const volumeSetData = vi.fn()
  const volumeUpdate = vi.fn()
  const lineSetData = vi.fn()
  return {
    candleSetData,
    candleUpdate,
    volumeSetData,
    volumeUpdate,
    lineSetData,
    series: {
      setData: candleSetData,
      update: candleUpdate,
      priceScale: () => ({ applyOptions: vi.fn() }),
      coordinateToPrice: vi.fn(() => 100),
      createPriceLine: vi.fn(() => ({})),
      removePriceLine: vi.fn(),
    },
    volumeSeries: {
      setData: volumeSetData,
      update: volumeUpdate,
      priceScale: () => ({ applyOptions: vi.fn() }),
    },
    lineSeries: {
      setData: lineSetData,
      update: vi.fn(),
      priceScale: () => ({ applyOptions: vi.fn() }),
    },
  }
})

vi.mock('lightweight-charts', () => ({
  createChart: vi.fn(() => ({
    addCandlestickSeries: vi.fn(() => chartMocks.series),
    addHistogramSeries: vi.fn(() => chartMocks.volumeSeries),
    addLineSeries: vi.fn(() => chartMocks.lineSeries),
    applyOptions: vi.fn(),
    removeSeries: vi.fn(),
    remove: vi.fn(),
    subscribeClick: vi.fn(),
    unsubscribeClick: vi.fn(),
    subscribeCrosshairMove: vi.fn(),
    timeScale: () => ({
      fitContent: vi.fn(),
      subscribeVisibleLogicalRangeChange: vi.fn(),
    }),
  })),
  LineStyle: { Solid: 0, Dashed: 2 },
}))

describe('LightweightChart liveTick current candle update', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    class ResizeObserverMock {
      observe = vi.fn()
      disconnect = vi.fn()
    }
    vi.stubGlobal('ResizeObserver', ResizeObserverMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('1d에서 현재 bar가 없으면 tick 가격 하나로 새 일봉을 만들지 않는다', async () => {
    const { rerender } = render(
      <LightweightChart
        interval="1d"
        candles={[
          {
            ts: '2026-06-08T15:00:00Z',
            open: '21300',
            high: '22000',
            low: '21000',
            close: '21450',
            volume: 900000,
          },
        ]}
      />,
    )

    await waitFor(() => expect(chartMocks.candleSetData).toHaveBeenCalled())

    rerender(
      <LightweightChart
        interval="1d"
        candles={[
          {
            ts: '2026-06-08T15:00:00Z',
            open: '21300',
            high: '22000',
            low: '21000',
            close: '21450',
            volume: 900000,
          },
        ]}
        liveTick={{
          price: '20950',
          ts: '2026-06-10T10:15:00+09:00',
        }}
      />,
    )

    await new Promise((resolve) => window.setTimeout(resolve, 0))
    expect(chartMocks.candleUpdate).not.toHaveBeenCalled()
  })
})
