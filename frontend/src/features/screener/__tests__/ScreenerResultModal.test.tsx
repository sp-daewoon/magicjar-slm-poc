/**
 * ScreenerResultModal 단위 테스트 — 4 케이스
 *
 * spec §2-4 + ADR 0071 §9-4 정합:
 *   1. loading 상태 — Spinner + 평가 중 메시지
 *   2. matches 렌더 — 테이블 + 종목 표시
 *   3. skip badge — skippedReasons 표시
 *   4. 기준봉 선택 — default 1d, 변경 시 3m로 재평가 요청
 *   5. userId null — setup 안내 메시지 (graceful)
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { ScreenerResultModal } from '../components/ScreenerResultModal'
import type { ScreenerRunResponse } from '../api/screenerApi'

type EvaluateBody = {
  userId: number
  strategyId: number
  interval?: string
  limit: number
}

// MSW 서버
const mockResult: ScreenerRunResponse = {
  cacheKey: 'screener:cache:abc123:xyz789',
  universeSize: 2847,
  evaluatedCount: 2814,
  matchedCount: 3,
  skippedReasons: {
    CandleMissing: 12,
    CandleInsufficient: 21,
  },
  matches: [
    {
      rank: 1,
      instrumentCode: '005930',
      instrumentName: '삼성전자',
      score: 0.854,
      scoreComponents: { signal: 0.4, volume: 0.25, atr: 0.15, marketCap: 0.08 },
      reason: 'RSI(14) < 30',
    },
    {
      rank: 2,
      instrumentCode: '000660',
      instrumentName: 'SK하이닉스',
      score: 0.762,
      scoreComponents: { signal: 0.35, volume: 0.2, atr: 0.18, marketCap: 0.07 },
      reason: 'MACD bullish cross',
    },
    {
      rank: 3,
      instrumentCode: '035420',
      instrumentName: 'NAVER',
      score: 0.631,
      scoreComponents: { signal: 0.3, volume: 0.18, atr: 0.1, marketCap: 0.06 },
      reason: 'SMA 골든크로스',
    },
  ],
  elapsedMs: 4521,
  cacheHit: false,
}

const evaluateBodies: EvaluateBody[] = []

const server = setupServer(
  http.post('/api/screener/evaluate', async ({ request }) => {
    evaluateBodies.push(await request.json() as EvaluateBody)
    return HttpResponse.json(mockResult)
  }),
)

beforeAll(() => server.listen())
afterEach(() => {
  evaluateBodies.length = 0
  server.resetHandlers()
})
afterAll(() => server.close())

function Wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

describe('ScreenerResultModal', () => {
  it('1. loading 상태 — Spinner aria-label 표시', async () => {
    // 응답을 지연시켜 pending 상태 확인
    server.use(
      http.post('/api/screener/evaluate', async () => {
        await new Promise((r) => setTimeout(r, 2000))
        return HttpResponse.json(mockResult)
      }),
    )

    render(
      <ScreenerResultModal strategyId={42} userId={12345678} onClose={() => {}} />,
      { wrapper: Wrapper },
    )

    // useEffect → mutate 호출 후 isPending=true → Spinner 렌더
    await waitFor(() => {
      expect(screen.getByRole('status', { name: 'universe 전체 평가 중' })).toBeInTheDocument()
    })
  })

  it('2. matches 렌더 — 테이블 + 종목 표시', async () => {
    render(
      <ScreenerResultModal strategyId={42} userId={12345678} onClose={() => {}} />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
    expect(screen.getByText('NAVER')).toBeInTheDocument()
    // 종목 코드
    expect(screen.getByText('005930')).toBeInTheDocument()
    // 통계 요약
    expect(screen.getByText(/2,847/)).toBeInTheDocument() // universeSize
  })

  it('3. skip badge — skippedReasons 표시', async () => {
    render(
      <ScreenerResultModal strategyId={42} userId={12345678} onClose={() => {}} />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(screen.getByText('캔들 미수집')).toBeInTheDocument()
    })
    expect(screen.getByText('캔들 부족')).toBeInTheDocument()
  })

  it('4. 기준봉 default 1d, 변경 시 3m로 재평가 요청', async () => {
    render(
      <ScreenerResultModal strategyId={42} userId={12345678} onClose={() => {}} />,
      { wrapper: Wrapper },
    )

    await waitFor(() => {
      expect(evaluateBodies[0]?.interval).toBe('1d')
    })

    fireEvent.change(screen.getByLabelText('기준봉'), { target: { value: '3m' } })

    await waitFor(() => {
      expect(evaluateBodies.some((body) => body.interval === '3m')).toBe(true)
    })
  })

  it('5. userId null — setup 안내 메시지 (graceful)', () => {
    render(
      <ScreenerResultModal strategyId={42} userId={null} onClose={() => {}} />,
      { wrapper: Wrapper },
    )

    expect(screen.getByText(/사용자 설정이 완료되지 않았습니다/)).toBeInTheDocument()
    expect(screen.getByText(/KIS 키 등록 후 다시 시도/)).toBeInTheDocument()
  })
})
