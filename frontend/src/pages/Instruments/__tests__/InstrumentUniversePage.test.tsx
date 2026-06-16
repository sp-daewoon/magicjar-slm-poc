import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ToastProvider } from '../../../components/ui/ToastProvider'
import { InstrumentUniversePage } from '../InstrumentUniversePage'

const instruments = [
  {
    code: '005930',
    name: '삼성전자',
    market: 'KOSPI',
    type: 'STOCK',
    sector: 'IT',
    sectorKey: 'it',
    sectorName: '전기전자',
    industryMedium: null,
    marketCapPrev: 4800000,
    tradingHalt: false,
    managementStock: false,
    active: true,
  },
  {
    code: '000660',
    name: 'SK하이닉스',
    market: 'KOSPI',
    type: 'STOCK',
    sector: 'IT',
    sectorKey: 'it',
    sectorName: '전기전자',
    industryMedium: null,
    marketCapPrev: 2500000,
    tradingHalt: false,
    managementStock: false,
    active: true,
  },
  {
    code: '035720',
    name: '카카오',
    market: 'KOSPI',
    type: 'STOCK',
    sector: 'SERVICE',
    sectorKey: 'service',
    sectorName: '서비스업',
    industryMedium: null,
    marketCapPrev: 180000,
    tradingHalt: false,
    managementStock: false,
    active: true,
  },
]

const server = setupServer(
  http.get('/api/instruments', () =>
    HttpResponse.json({
      items: instruments,
      total: instruments.length,
      limit: 5000,
      offset: 0,
    }),
  ),

  http.get('/api/watchlists', () =>
    HttpResponse.json([
      {
        id: 1,
        userId: 1,
        code: '005930',
        name: '삼성전자',
        market: 'KOSPI',
        priority: 100,
        createdAt: '2026-06-05T00:00:00Z',
      },
    ]),
  ),

  http.post('/api/watchlists', () =>
    HttpResponse.json({
      id: 2,
      userId: 1,
      code: '000660',
      name: 'SK하이닉스',
      market: 'KOSPI',
      priority: 100,
      createdAt: '2026-06-06T00:00:00Z',
    }),
  ),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

function renderPage(initialEntry = '/instruments') {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={[initialEntry]}>
          <Routes>
            <Route path="/instruments" element={<InstrumentUniversePage />} />
            <Route path="/symbol/:code" element={<div>종목 상세</div>} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

describe('InstrumentUniversePage 관심종목 필터', () => {
  it('관심종목 필터 진입 시 등록된 종목만 기존 섹터 안에 남긴다', async () => {
    renderPage('/instruments?watchlist=1')

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: '삼성전자 관심종목 해제' })).toBeInTheDocument()
    expect(screen.queryByText('SK하이닉스')).not.toBeInTheDocument()
    expect(screen.queryByText('카카오')).not.toBeInTheDocument()
  })

  it('전체 보기에서는 등록 상태에 따라 관심등록과 관심해제를 나눠 표시한다', async () => {
    renderPage()
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
      expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: '삼성전자 관심종목 해제' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'SK하이닉스 관심종목 등록' })).toBeInTheDocument()
    expect(screen.queryByText('☆ 관심등록')).not.toBeInTheDocument()
    expect(screen.queryByText('★ 관심해제')).not.toBeInTheDocument()
    expect(screen.getAllByText('★')).toHaveLength(1)
    expect(screen.getAllByText('☆')).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: '관심종목 1' }))

    expect(screen.getByText('삼성전자')).toBeInTheDocument()
    expect(screen.queryByText('SK하이닉스')).not.toBeInTheDocument()
  })

  it('카드 본문(버튼·이름 링크 외 영역)을 클릭하면 종목 상세로 이동한다', async () => {
    renderPage()
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('카카오')).toBeInTheDocument()
    })

    // 카드 어디든(관심버튼/이름 링크 제외) 클릭 시 상세로 이동
    await user.click(screen.getByRole('link', { name: '카카오 상세 보기' }))

    expect(screen.getByText('종목 상세')).toBeInTheDocument()
  })

  it('관심 버튼 클릭은 카드 네비게이션으로 전파되지 않는다', async () => {
    renderPage()
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'SK하이닉스 관심종목 등록' }))

    // 여전히 종목검색 화면에 머문다 (상세로 이동하지 않음)
    expect(screen.queryByText('종목 상세')).not.toBeInTheDocument()
    expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
  })
})
