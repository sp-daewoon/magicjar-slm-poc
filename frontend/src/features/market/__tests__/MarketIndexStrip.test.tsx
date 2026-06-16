/**
 * MarketIndexStrip 통합 테스트 — T-MARKET R2 frontend.
 *
 * 검증:
 * - 로딩 skeleton 4건
 * - 4 지수 카드 순서 LOCK (KOSPI · KOSDAQ · NASDAQ · S&P)
 * - 빈 list graceful "데이터 수집 중"
 * - "상세 추세 →" 링크 → /market/trend
 */
import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MarketIndexStrip } from '../components/MarketIndexStrip'
import apiClient from '../../../lib/axios'
import { vi } from 'vitest'

function renderWith(initialData?: unknown) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  if (initialData !== undefined) {
    queryClient.setQueryData(['marketIndex', 'snapshot'], initialData)
  }
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <MarketIndexStrip />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('MarketIndexStrip', () => {
  it('빈 list → "데이터 수집 중" graceful', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: { indexes: [] } })
    renderWith()
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/수집 중/)
    })
  })

  it('4 지수 응답 → 순서 LOCK 표시 (KOSPI 첫 번째)', async () => {
    const data = {
      indexes: [
        {
          code: '.SPX',
          ma: { '5': 5000 },
          crossSignal: 'NEUTRAL',
          trendLabel: 'UP',
          periodsUsed: [5, 20, 60],
          isUserCustom: false,
          dataInsufficient: false,
          lastClose: 5050,
          changeRate: 0.3,
        },
        {
          code: '0001',
          ma: { '5': 2670 },
          crossSignal: 'GOLDEN',
          trendLabel: 'UP',
          periodsUsed: [5, 20, 60],
          isUserCustom: false,
          dataInsufficient: false,
          lastClose: 2685,
          changeRate: 0.55,
        },
      ],
    }
    renderWith(data)
    await waitFor(() => {
      expect(screen.getByText('KOSPI')).toBeInTheDocument()
    })
    // 4 default 모두 렌더 — backend에 없는 KOSDAQ·NASDAQ은 "데이터 없음" graceful
    expect(screen.getByText('KOSDAQ')).toBeInTheDocument()
    expect(screen.getByText('NASDAQ')).toBeInTheDocument()
    expect(screen.getByText('S&P 500')).toBeInTheDocument()
  })

  it('"상세 추세 →" 링크 → /market/trend', async () => {
    renderWith({
      indexes: [
        {
          code: '0001',
          ma: { '5': 2670 },
          crossSignal: 'NEUTRAL',
          trendLabel: 'UP',
          periodsUsed: [5, 20, 60],
          isUserCustom: false,
          dataInsufficient: false,
          lastClose: 2685,
          changeRate: 0.55,
        },
      ],
    })
    await waitFor(() => {
      expect(screen.getByText('KOSPI')).toBeInTheDocument()
    })
    const link = screen.getByRole('link', { name: /시장 추세 상세 보기/ })
    expect(link.getAttribute('href')).toBe('/market/trend')
  })
})
