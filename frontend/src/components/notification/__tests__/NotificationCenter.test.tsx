/**
 * NotificationCenter 단위 테스트 (P2 Task 6 Step 3)
 *
 * 검증 매트릭스 (plan Task 6):
 * 1. 벨 클릭 시 드롭다운 펼침 + unread count badge + 이벤트 항목 노출
 *
 * mock 전략:
 * - useSystemEventList mock: fixed 데이터 (RECONCILE_FALLBACK_FIRED 1건)
 * - useMutation 은 실제 사용 → BrowserRouter + QueryClientProvider 통한 통합 렌더
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { NotificationCenter } from '../NotificationCenter'

// useSystemEventList mock — fixed unread 1건
vi.mock('../../../api/hooks/useSystemEventList', () => ({
  useSystemEventList: () => ({
    data: {
      items: [
        {
          id: 1,
          category: 'RECONCILE',
          severity: 'WARN',
          actor: 'OrderReconciliationWorker',
          action: 'RECONCILE_FALLBACK_FIRED',
          userId: '74683223',
          correlationId: null,
          detailJson: '{}',
          occurredAt: '2026-05-20T15:00:00Z',
          viewedAt: null,
          resolvedAt: null,
        },
      ],
      totalCount: 1,
      unreadCount: 1,
      page: 0,
      size: 10,
    },
  }),
}))

// systemEventApi.markViewed mock — useMutation 가 호출하므로 응답만 fixed 처리
vi.mock('../../../api/system-event', async () => {
  const actual = await vi.importActual<typeof import('../../../api/system-event')>(
    '../../../api/system-event',
  )
  return {
    ...actual,
    systemEventApi: {
      list: vi.fn(),
      markViewed: vi.fn().mockResolvedValue({}),
      markResolved: vi.fn(),
    },
  }
})

describe('NotificationCenter', () => {
  it('벨 클릭 시 드롭다운 + unread count 표시 + 이벤트 항목 노출', () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <NotificationCenter userId="74683223" />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    // 1. 벨 unread badge — 닫혀있는 상태에서도 노출
    const bell = screen.getByLabelText('시스템 이벤트 알림')
    expect(bell).toBeInTheDocument()
    expect(screen.getByLabelText('미확인 1건')).toHaveTextContent('1')

    // 2. 벨 클릭 → 드롭다운 펼침
    fireEvent.click(bell)
    expect(screen.getByRole('dialog', { name: '시스템 이벤트 알림 목록' })).toBeInTheDocument()

    // 3. 드롭다운 헤더 "시스템 이벤트" + "미확인 1건"
    expect(screen.getByText('시스템 이벤트')).toBeInTheDocument()
    expect(screen.getByText('미확인 1건')).toBeInTheDocument()

    // 4. 이벤트 항목 노출 — RECONCILE_FALLBACK_FIRED action 가시
    expect(screen.getByText(/RECONCILE_FALLBACK_FIRED/)).toBeInTheDocument()

    // 5. /system 전체 보기 링크 존재
    expect(screen.getByText(/전체 보기/)).toBeInTheDocument()
  })
})
