/**
 * SignalTracePanel 단위 테스트 (ADR 0115 §2-B 불변식 #4 — 키 사슬 추적성)
 *
 * 검증 매트릭스:
 *  1. 사인→주문→체결→보유 요약 + 정합 badge(정합) 렌더
 *  2. 불일치 시 "보정 대기" badge (strategyHeldQty ≠ authorityHeldQty)
 *  3. 부분체결 N건 타임라인 각각 수량·가격 노출
 *  4. 주문 0건 시 "발사된 주문이 없습니다" 명시 (mock 합성 0건)
 *
 * mock: useSignalTrace 고정 데이터.
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { SignalTraceDto } from '../../../api/signal'

const mockTrace = vi.fn()
vi.mock('../../../api/hooks/useSignal', () => ({
  useSignalTrace: () => mockTrace(),
}))

import { SignalTracePanel } from '../SignalTracePanel'

function renderPanel() {
  return render(
    <MemoryRouter>
      <SignalTracePanel signalId="709" userId={1} enabled />
    </MemoryRouter>,
  )
}

const baseSignal = {
  id: '709',
  strategyId: '8',
  userId: 1,
  code: '005930',
  interval: '1m',
  side: 'BUY' as const,
  price: '73000',
  ts: '2026-06-10T05:29:00Z',
}

describe('SignalTracePanel', () => {
  it('정합(reconciled) 시 사인→주문→체결→보유 요약 + "정합" badge 렌더', () => {
    const data: SignalTraceDto = {
      signal: baseSignal,
      orders: [
        {
          id: 223,
          code: '005930',
          side: 'BUY',
          qty: 15,
          priceType: 'MARKET',
          limitPrice: null,
          status: 'FILLED',
          mode: 'MOCK',
          kisOrderNo: '0000123456',
          rejectReason: null,
          requestedAt: '2026-06-10T05:29:00Z',
          completedAt: '2026-06-10T05:29:14Z',
          source: 'AUTO',
          orderTrigger: 'STRATEGY_SIGNAL',
          triggerRefId: 709,
          filledQty: 15,
          executions: [
            { id: 1, orderId: 223, executedQty: 3, executedPrice: '73000', executedAt: '2026-06-10T05:29:10Z', fee: '0', tax: '0' },
            { id: 2, orderId: 223, executedQty: 5, executedPrice: '73100', executedAt: '2026-06-10T05:29:12Z', fee: '0', tax: '0' },
            { id: 3, orderId: 223, executedQty: 7, executedPrice: '73200', executedAt: '2026-06-10T05:29:14Z', fee: '0', tax: '0' },
          ],
        },
      ],
      summary: {
        orderCount: 1,
        orderedQty: 15,
        filledQty: 15,
        strategyHeldQty: 15,
        authorityHeldQty: 15,
        reconciled: true,
        strategyAvgPrice: '73100.0000',
        strategyFilledLots: 1,
        strategyRealizedPnl: '0.0000',
        strategyPositionUpdatedAt: '2026-06-10T05:29:15Z',
      },
    }
    mockTrace.mockReturnValue({ data, isLoading: false, isError: false })
    renderPanel()

    expect(screen.getByText('정합')).toBeInTheDocument()
    expect(screen.getByText('이 사인이 발사한 주문')).toBeInTheDocument()
    // 부분체결 3건 타임라인 (3·5·7주)
    expect(screen.getByText('3주')).toBeInTheDocument()
    expect(screen.getByText('5주')).toBeInTheDocument()
    expect(screen.getByText('7주')).toBeInTheDocument()
  })

  it('전략 보유 ≠ KIS 권위 시 "보정 대기" badge (G4 미귀속)', () => {
    const data: SignalTraceDto = {
      signal: baseSignal,
      orders: [],
      summary: {
        orderCount: 0,
        orderedQty: 15,
        filledQty: 11,
        strategyHeldQty: 11,
        authorityHeldQty: 16,
        reconciled: false,
        strategyAvgPrice: null,
        strategyFilledLots: null,
        strategyRealizedPnl: null,
        strategyPositionUpdatedAt: null,
      },
    }
    mockTrace.mockReturnValue({ data, isLoading: false, isError: false })
    renderPanel()

    expect(screen.getByText('보정 대기')).toBeInTheDocument()
    // 주문 0건 명시 (mock 합성 0건 — feedback_no_mock_data)
    expect(screen.getByText(/이 사인으로 발사된 주문이 없습니다/)).toBeInTheDocument()
  })

  it('로딩 중 스켈레톤, 에러 시 안내 메시지', () => {
    mockTrace.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    const { rerender } = renderPanel()
    // 로딩 스켈레톤(animate-pulse) 존재
    expect(document.querySelector('.animate-pulse')).toBeTruthy()

    mockTrace.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    rerender(
      <MemoryRouter>
        <SignalTracePanel signalId="709" userId={1} enabled />
      </MemoryRouter>,
    )
    expect(screen.getByText(/불러오지 못했습니다/)).toBeInTheDocument()
  })
})
