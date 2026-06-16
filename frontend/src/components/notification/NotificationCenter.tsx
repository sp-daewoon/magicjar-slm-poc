/**
 * NotificationCenter — 시스템 이벤트 알림센터 (P2 Task 6)
 *
 * AppHeader 통합 위젯 — 벨 아이콘 + unread badge + 드롭다운 + /system 전체 보기 링크.
 *
 * ### 동작 매트릭스
 * - userId === null → unreadOnly 호출은 backend 가 0 반환 → badge 미표시
 * - userId 정상   → useSystemEventList({ userId, unreadOnly:true, page:0, size:10 })
 * - 벨 클릭     → 드롭다운 toggle
 * - row 클릭    → systemEventApi.markViewed(id) mutation → query invalidate (벨 badge 자동 감소)
 * - 99+ 초과    → '99+' 표기 (badge overflow 가드)
 *
 * ### 데이터 흐름
 * STOMP push (useSystemEventStompSubscription, Header 에서 호출) → invalidate
 *   ↓
 * useSystemEventList refetch → unreadCount 업데이트 → badge 실시간 갱신
 *
 * ### 디자인 정합
 * - magicJar 다크 테마 (gray-950 base + gray-800 hover + blue-400 accent)
 * - SettingsModal·AppHeader 와 동일 z-index·shadow 패턴 (z-50)
 *
 * ### Source of truth
 * - hook: useSystemEventList (Task 5)
 * - mutation: systemEventApi.markViewed (Task 3)
 * - sub-component: NotificationItem (Task 6 Step 1)
 *
 * spec: _workspace/specs/2026-05-20-system-event-log-design.md §6.5·§6.6
 * plan: _workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md Task 6 Step 2
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useSystemEventList } from '../../api/hooks/useSystemEventList'
import { systemEventApi } from '../../api/system-event'
import { NotificationItem } from './NotificationItem'

interface Props {
  /** 사용자 ID (null = 미등록 — broadcast 만 broadcast 이벤트 노출, user-specific unread badge 비활성) */
  userId: string | null
}

export function NotificationCenter({ userId }: Props) {
  const [open, setOpen] = useState(false)
  const queryClient = useQueryClient()

  // userId 있을 때만 사용자별 unread, 없으면 broadcast only(unreadCount=0 backend default)
  const { data } = useSystemEventList({
    userId: userId ?? undefined,
    unreadOnly: true,
    page: 0,
    size: 10,
  })

  const markViewedMutation = useMutation({
    mutationFn: (id: number) => systemEventApi.markViewed(id),
    onSuccess: () => {
      // STOMP push 와 동일 invalidate — 벨 badge·드롭다운·dashboard 모두 자동 새로고침
      queryClient.invalidateQueries({ queryKey: ['system-event'], exact: false })
    },
  })

  const unreadCount = data?.unreadCount ?? 0
  const items = data?.items ?? []

  return (
    <div className="relative">
      <button
        type="button"
        aria-label="시스템 이벤트 알림"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
        className="relative rounded-lg p-2 text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-400"
      >
        <span aria-hidden className="text-base leading-none">🔔</span>
        {unreadCount > 0 && (
          <span
            aria-label={`미확인 ${unreadCount}건`}
            className="absolute -right-0.5 -top-0.5 inline-flex min-w-[1.25rem] items-center justify-center rounded-full bg-red-600 px-1.5 text-[10px] font-bold leading-tight text-white"
          >
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </button>

      {open && (
        <>
          {/* backdrop — 바깥 클릭 시 닫기 (modal-less 패턴) */}
          <div
            aria-hidden
            className="fixed inset-0 z-40"
            onClick={() => setOpen(false)}
          />
          <div
            role="dialog"
            aria-label="시스템 이벤트 알림 목록"
            className="absolute right-0 z-50 mt-2 w-80 rounded-lg border border-gray-800 bg-gray-950 shadow-xl"
          >
            <div className="flex items-center justify-between border-b border-gray-800 p-3 text-sm font-semibold text-white">
              <span>시스템 이벤트</span>
              <span className="text-xs font-normal text-gray-400">
                미확인 {unreadCount}건
              </span>
            </div>

            <div className="max-h-96 overflow-y-auto">
              {items.length === 0 ? (
                <div className="p-6 text-center text-sm text-gray-500">
                  새 이벤트가 없습니다.
                </div>
              ) : (
                items.map((event) => (
                  <NotificationItem
                    key={event.id}
                    event={event}
                    onClick={markViewedMutation.mutate}
                  />
                ))
              )}
            </div>

            <div className="border-t border-gray-800 p-2 text-center">
              <Link
                to="/system"
                onClick={() => setOpen(false)}
                className="text-xs text-blue-400 hover:text-blue-300 hover:underline"
              >
                전체 보기 →
              </Link>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
