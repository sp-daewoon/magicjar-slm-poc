/**
 * DisclosurePanel 단위 테스트 — 5 케이스
 *
 * ADR 0075 spec §2-3 단위 테스트 매트릭스:
 *   1. 정정공시 배지 표시 — isCorrection=true → "정정공시" aria-label 배지 렌더링
 *   2. onlyCorrection 필터 — "정정공시만" 버튼 토글
 *   3. 빈 응답 graceful — "최근 7일간 공시가 없습니다" 표시
 *   4. 공시 3건 렌더링 — 보고서명 표시
 *   5. "더 보기" 클릭 시 fetchNextPage 호출 검증
 *
 * dogfooding hotfix: useInfiniteQuery 반환 형태(data.pages) + fetchNextPage 테스트.
 * STOMP 0 검증: useSymbolDisclosures mock에 STOMP client 없음.
 * mock 합성 0: 빈 응답 시 "데이터 없음" 한국어 표시.
 */
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { DisclosurePanel } from '../components/DisclosurePanel'
import type { SymbolDisclosureListResponse, DisclosureItem } from '../api/disclosureApi'

// useSymbolDisclosures hook mock
vi.mock('../hooks/useSymbolDisclosures', () => ({
  useSymbolDisclosures: vi.fn(),
}))

import { useSymbolDisclosures } from '../hooks/useSymbolDisclosures'
const mockUseSymbolDisclosures = vi.mocked(useSymbolDisclosures)

// ──────────────────────────────────────────────
// 헬퍼
// ──────────────────────────────────────────────

function renderDisclosurePanel(code = '005930') {
  return render(<DisclosurePanel code={code} />)
}

/** useInfiniteQuery data 구조 생성 (pageParams 포함 — InfiniteData<T> 정합) */
function makeInfiniteData(
  pages: Partial<SymbolDisclosureListResponse>[],
): { pages: SymbolDisclosureListResponse[]; pageParams: number[] } {
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

function makeDisclosureItems(count: number, correctionIndices: number[] = []): DisclosureItem[] {
  return Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    rceptNo: `2026051500000${i + 1}`,
    corpCode: '00126380',
    corpName: '삼성전자',
    reportNm: correctionIndices.includes(i) ? `[기재정정]사업보고서 ${i + 1}` : `사업보고서 ${i + 1}`,
    flrNm: '삼성전자',
    rm: correctionIndices.includes(i) ? '정' : null,
    isCorrection: correctionIndices.includes(i),
    url: `https://dart.fss.or.kr/dsaf001/main.do?rcpNo=2026051500000${i + 1}`,
    rceptDt: '20260515',
    ingestedAt: '2026-05-15T09:31:00+09:00',
  }))
}

// useInfiniteQuery 반환 타입 부분 모방
type MockReturn = ReturnType<typeof useSymbolDisclosures>

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

describe('DisclosurePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('isCorrection=true 공시 항목에 "정정공시" 배지가 표시된다', () => {
    const items = makeDisclosureItems(3, [1]) // index 1 = 정정공시
    mockUseSymbolDisclosures.mockReturnValue(
      mockReturn({
        data: makeInfiniteData([{ items, totalElements: 3, totalPages: 1 }]),
      }),
    )

    renderDisclosurePanel()

    expect(screen.getByLabelText('정정공시')).toBeInTheDocument()
    expect(screen.getByText('정정공시')).toBeInTheDocument()
  })

  it('"정정공시만" 버튼이 표시되고 토글된다', () => {
    mockUseSymbolDisclosures.mockReturnValue(
      mockReturn({ data: makeInfiniteData([{ items: [], totalElements: 0 }]) }),
    )

    renderDisclosurePanel()

    const btn = screen.getByRole('button', { name: '정정공시만' })
    expect(btn).toBeInTheDocument()
    expect(btn).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(btn)
    expect(btn).toHaveAttribute('aria-pressed', 'true')
  })

  it('빈 응답 시 "최근 7일간 공시가 없습니다." 표시 (mock 합성 0 LOCK)', () => {
    mockUseSymbolDisclosures.mockReturnValue(
      mockReturn({ data: makeInfiniteData([{ items: [], totalElements: 0 }]) }),
    )

    renderDisclosurePanel()

    expect(screen.getByText('최근 7일간 공시가 없습니다.')).toBeInTheDocument()
  })

  it('공시 3건 응답 시 보고서명 3건 모두 표시', () => {
    const items = makeDisclosureItems(3)
    mockUseSymbolDisclosures.mockReturnValue(
      mockReturn({
        data: makeInfiniteData([{ items, totalElements: 3, totalPages: 1 }]),
      }),
    )

    renderDisclosurePanel()

    for (let i = 1; i <= 3; i++) {
      expect(screen.getByText(`사업보고서 ${i}`)).toBeInTheDocument()
    }
  })

  it('"더 보기" 클릭 시 fetchNextPage 호출', () => {
    const fetchNextPage = vi.fn()
    const items = makeDisclosureItems(20)
    mockUseSymbolDisclosures.mockReturnValue(
      mockReturn({
        data: makeInfiniteData([{ items, totalElements: 40, totalPages: 2 }]),
        hasNextPage: true,
        fetchNextPage,
      }),
    )

    renderDisclosurePanel()

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
    expect(fetchNextPage).toHaveBeenCalledOnce()
  })
})
