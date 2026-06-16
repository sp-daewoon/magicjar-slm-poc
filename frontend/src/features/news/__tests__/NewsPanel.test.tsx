/**
 * NewsPanel 단위 테스트 — 5 케이스
 *
 * ADR 0075 spec §2-3 단위 테스트 매트릭스:
 *   1. 빈 응답 graceful — "최근 7일간 뉴스가 없습니다" 표시
 *   2. items 5건 render — 각 카드 제목 표시
 *   3. 페이지네이션 — hasNextPage=true 시 "더 보기" 버튼 표시
 *   4. 페이지네이션 — hasNextPage=false 시 "더 보기" 버튼 미표시
 *   5. "더 보기" 클릭 시 fetchNextPage 호출 검증
 *
 * dogfooding hotfix: useInfiniteQuery 반환 형태(data.pages) + fetchNextPage 테스트.
 * STOMP 0 검증: useSymbolNews mock에 STOMP client 없음.
 * mock 합성 0: 빈 응답 시 "데이터 없음" 한국어 표시.
 */
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { NewsPanel } from '../components/NewsPanel'
import type { SymbolNewsListResponse, NewsItem } from '../api/newsApi'

// useSymbolNews hook mock
vi.mock('../hooks/useSymbolNews', () => ({
  useSymbolNews: vi.fn(),
}))

import { useSymbolNews } from '../hooks/useSymbolNews'
const mockUseSymbolNews = vi.mocked(useSymbolNews)

// ──────────────────────────────────────────────
// 헬퍼 — NewsPanel 래퍼
// ──────────────────────────────────────────────

function renderNewsPanel(code = '005930') {
  return render(<NewsPanel code={code} />)
}

/** useInfiniteQuery data 구조 생성 (pageParams 포함 — InfiniteData<T> 정합) */
function makeInfiniteData(
  pages: Partial<SymbolNewsListResponse>[],
): { pages: SymbolNewsListResponse[]; pageParams: number[] } {
  return {
    pages: pages.map((p) => ({
      stockCode: '005930',
      items: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 1,
      ...p,
    })),
    pageParams: pages.map((_, i) => i),
  }
}

function makeNewsItems(count: number): NewsItem[] {
  return Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    source: 'NAVER',
    title: `뉴스 제목 ${i + 1}`,
    bodySummary: `요약 ${i + 1}`,
    url: `https://n.news.naver.com/article/${i + 1}`,
    publishedAt: '2026-05-15T09:00:00+09:00',
    sentiment: null,
  }))
}

// useInfiniteQuery 반환 타입 부분 모방
type MockReturn = ReturnType<typeof useSymbolNews>

function mockReturn(overrides: Partial<MockReturn>): MockReturn {
  return {
    data: undefined,
    isLoading: false,
    isError: false,
    fetchNextPage: vi.fn(),
    hasNextPage: false,
    isFetchingNextPage: false,
    ...overrides,
  } as unknown as MockReturn
}

// ──────────────────────────────────────────────
// 테스트
// ──────────────────────────────────────────────

describe('NewsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('빈 응답 시 "최근 7일간 뉴스가 없습니다" 한국어 표시 (mock 합성 0 LOCK)', () => {
    mockUseSymbolNews.mockReturnValue(
      mockReturn({ data: makeInfiniteData([{ items: [], totalElements: 0 }]) }),
    )

    renderNewsPanel()

    expect(screen.getByText('최근 7일간 뉴스가 없습니다.')).toBeInTheDocument()
    expect(screen.getByText(/NAVER_OPEN_API_CLIENT_ID/)).toBeInTheDocument()
  })

  it('뉴스 5건 응답 시 5개 제목 모두 렌더링', () => {
    const items = makeNewsItems(5)
    mockUseSymbolNews.mockReturnValue(
      mockReturn({
        data: makeInfiniteData([{ items, totalElements: 5, totalPages: 1 }]),
      }),
    )

    renderNewsPanel()

    for (let i = 1; i <= 5; i++) {
      expect(screen.getByText(`뉴스 제목 ${i}`)).toBeInTheDocument()
    }
  })

  it('hasNextPage=true 시 "더 보기" 버튼 표시', () => {
    const items = makeNewsItems(20)
    mockUseSymbolNews.mockReturnValue(
      mockReturn({
        data: makeInfiniteData([{ items, totalElements: 40, totalPages: 2 }]),
        hasNextPage: true,
      }),
    )

    renderNewsPanel()

    expect(screen.getByRole('button', { name: '더 보기' })).toBeInTheDocument()
  })

  it('hasNextPage=false 시 "더 보기" 버튼 미표시', () => {
    const items = makeNewsItems(3)
    mockUseSymbolNews.mockReturnValue(
      mockReturn({
        data: makeInfiniteData([{ items, totalElements: 3, totalPages: 1 }]),
        hasNextPage: false,
      }),
    )

    renderNewsPanel()

    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('"더 보기" 클릭 시 fetchNextPage 호출', () => {
    const fetchNextPage = vi.fn()
    const items = makeNewsItems(20)
    mockUseSymbolNews.mockReturnValue(
      mockReturn({
        data: makeInfiniteData([{ items, totalElements: 40, totalPages: 2 }]),
        hasNextPage: true,
        fetchNextPage,
      }),
    )

    renderNewsPanel()

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
    expect(fetchNextPage).toHaveBeenCalledOnce()
  })
})
