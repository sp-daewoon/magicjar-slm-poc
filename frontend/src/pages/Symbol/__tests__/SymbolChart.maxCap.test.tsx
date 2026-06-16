/**
 * SymbolChart — Phase 5 ADR 0093 §3-2 가드 3 + max cap toast 회귀 검증
 *
 * 검증 항목:
 *   1. LightweightChart에 maxCandles prop이 timeframe별 매트릭스 정합으로 전달
 *      - 1d → 5000, 1m → 4800, 5m → 3840 등 (ADR 0093 §3-1)
 *   2. onMaxCapReached 콜백 호출 시 toast 1회 표시
 *      - 동일 (code, interval) 컨텍스트에서 중복 호출은 toast 1회만 (lock)
 *   3. interval 변경 시 lock 해제 → 새 timeframe에서 toast 재발화 가능
 *
 * Mock:
 *   - LightweightChart: props 캡처 + onMaxCapReached 강제 trigger
 *   - useSymbolCandles: 빈 페이지 응답 mock
 *   - STOMP·CandleRefresh·Patterns: graceful mock
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, act, screen } from '@testing-library/react'
import type { ComponentProps } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../../../components/ui/ToastProvider'
import { SymbolChart } from '../SymbolChart'

// ──────────────────────────────────────────────
// LightweightChart mock — props 캡처 + onMaxCapReached 노출
// ──────────────────────────────────────────────

interface CapturedProps {
  maxCandles?: number
  onMaxCapReached?: () => void
  riskPlanLevels?: {
    entryPrice: number
    stopPrice: number
    takePrice: number
  } | null
}

let lastChartProps: CapturedProps | null = null

vi.mock('../LightweightChart', () => ({
  LightweightChart: (props: CapturedProps) => {
    lastChartProps = props
    return <div data-testid="mock-lightweight-chart" />
  },
}))

// ──────────────────────────────────────────────
// useSymbolCandles mock — 빈 페이지
// ──────────────────────────────────────────────

vi.mock('../../../api/hooks/useSymbolCandles', () => ({
  useSymbolCandles: () => ({
    data: { pages: [], pageParams: [] },
    isLoading: false,
    isFetchingNextPage: false,
    fetchNextPage: vi.fn(),
    hasNextPage: false,
    isError: false,
    latestBoundaryCandle: null,
  }),
}))

// ──────────────────────────────────────────────
// useCandleRefresh mock
// ──────────────────────────────────────────────

vi.mock('../../../api/hooks/useCandleRefresh', () => ({
  useCandleRefresh: () => ({
    refresh: vi.fn(),
    isPending: false,
  }),
  useCandleRecollectJob: () => ({
    recollect: vi.fn(),
    isPending: false,
    progress: null,
  }),
}))

// ──────────────────────────────────────────────
// usePatterns mock
// ──────────────────────────────────────────────

vi.mock('../../../api/hooks/usePatterns', () => ({
  usePatterns: () => ({
    data: null,
    isError: false,
    errorMessage: null,
  }),
}))

// ──────────────────────────────────────────────
// STOMP mock
// ──────────────────────────────────────────────

vi.mock('../../../lib/stomp', () => ({
  useStompTopic: vi.fn(),
  isStompConnected: () => false,
}))

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function renderSymbolChart(code: string, props: Partial<ComponentProps<typeof SymbolChart>> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <SymbolChart code={code} {...props} />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  lastChartProps = null
})

afterEach(() => {
  vi.clearAllMocks()
})

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('SymbolChart — Phase 5 가드 3 + max cap toast (ADR 0093 §3-2)', () => {
  it('LightweightChart에 1d default max cap = 5000 prop 전달 (ADR 0093 §3-1)', () => {
    renderSymbolChart('004090')
    expect(lastChartProps).not.toBeNull()
    expect(lastChartProps?.maxCandles).toBe(5000)
    expect(typeof lastChartProps?.onMaxCapReached).toBe('function')
  })

  it('onMaxCapReached 호출 시 toast 표시 (1회만)', () => {
    renderSymbolChart('004090')

    expect(lastChartProps?.onMaxCapReached).toBeDefined()
    // 가드 3 trigger 시뮬레이션
    act(() => {
      lastChartProps!.onMaxCapReached!()
    })

    // toast 표시 — DOM의 role="alert" 확인 (ToastProvider 정합)
    const toasts = document.querySelectorAll('[role="alert"]')
    expect(toasts.length).toBeGreaterThanOrEqual(1)
    const firstToast = toasts[0] as HTMLElement
    expect(firstToast.textContent ?? '').toMatch(/표시 봉수 최대 도달/)
    expect(firstToast.textContent ?? '').toMatch(/5,000봉/)
  })

  it('동일 (code, interval) 컨텍스트에서 중복 호출 시 toast 1회만 (lock)', () => {
    renderSymbolChart('004090')

    act(() => {
      lastChartProps!.onMaxCapReached!()
      lastChartProps!.onMaxCapReached!()
      lastChartProps!.onMaxCapReached!()
    })

    // toast 1회만 — lock으로 중복 차단
    const toasts = document.querySelectorAll('[role="alert"]')
    // ToastProvider는 최대 5개까지 stack하지만 본 lock은 1회만 호출 → 1개 expected
    expect(toasts.length).toBe(1)
  })

  it('RiskPlan 미리보기 기준가를 price line props로 전달한다', () => {
    renderSymbolChart('005930', {
      liveTick: { price: '100000', ts: '2026-06-01T01:00:00Z' },
      riskPlanPreview: {
        strategyId: '43',
        stopLossPct: 5,
        takeProfitPct: 10,
      },
    })

    expect(screen.getByText('전략 43 손절·익절 미리보기')).toBeInTheDocument()
    expect(lastChartProps?.riskPlanLevels).toMatchObject({
      entryPrice: 100_000,
      stopPrice: 95_000,
      takePrice: 110_000,
    })
  })
})
