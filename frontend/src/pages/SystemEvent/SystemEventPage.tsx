/**
 * SystemEventPage — /system 운영 대시보드 (P2 Task 7)
 *
 * 시스템 운영 이벤트 (Reconcile·WebSocket·KIS Rate Limit·DB·Redis) 전체 시계열 대시보드.
 *
 * ### 기능 매트릭스
 * - 필터: unreadOnly checkbox
 * - 페이지네이션: 이전/다음 + 현재 페이지 표시 (size=20 고정)
 * - 테이블 9 컬럼: occurredAt · category · severity · action · actor · userId · correlationId(8자 truncate) · detailJson(truncate) · 작업(해결 표시)
 * - severity 색상 배지: INFO blue / WARN amber / ERROR orange / CRITICAL red+bold
 * - mutation: systemEventApi.markResolved → query invalidate (자동 새로고침)
 *
 * ### 디자인 정합
 * - magicJar 다크 테마 (gray-950 base + gray-800 hover + blue-400 accent)
 * - OrdersPage·RiskEventsPage 컨테이너 패턴 정합 (mx-auto + max-w-7xl + px-4 py-6)
 *
 * ### 데이터 흐름
 * - 1차: STOMP push (AppHeader useSystemEventStompSubscription) → invalidate → 자동 새로고침
 * - 2차: 30s polling fallback (useSystemEventList refetchInterval)
 * - 3차: 사용자 작업 (markResolved) → invalidate → 자동 새로고침
 *
 * ### Source of truth
 * - hook: useSystemEventList (Task 5)
 * - mutation: systemEventApi.markResolved (Task 3)
 *
 * spec: _workspace/specs/2026-05-20-system-event-log-design.md §6.7·§12·§13
 * plan: _workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md Task 7
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useSystemEventList } from '../../api/hooks/useSystemEventList'
import {
  systemEventApi,
  type SystemEvent,
  type SystemEventSeverity,
} from '../../api/system-event'
import { DetailCell } from '../../components/ui/DetailCell'
import { SystemEventDetailModal } from './SystemEventDetailModal'

/** 페이지 크기 — spec §13 LOCK */
const PAGE_SIZE = 20

/**
 * Severity 배지 색상 매트릭스 — dark theme.
 *
 * 시각 우선순위 (눈 attention 끄는 정도): CRITICAL > ERROR > WARN > INFO
 */
const SEVERITY_BADGE_CLASS: Record<SystemEventSeverity, string> = {
  INFO: 'bg-blue-950 text-blue-300 border-blue-800',
  WARN: 'bg-amber-950 text-amber-300 border-amber-800',
  ERROR: 'bg-orange-950 text-orange-300 border-orange-800',
  CRITICAL: 'bg-red-950 text-red-300 border-red-700 font-bold',
}

export function SystemEventPage() {
  const [page, setPage] = useState(0)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [detailEvent, setDetailEvent] = useState<SystemEvent | null>(null)
  const queryClient = useQueryClient()

  const { data, isLoading, isError, error } = useSystemEventList({
    unreadOnly,
    page,
    size: PAGE_SIZE,
  })

  const markResolvedMutation = useMutation({
    mutationFn: (id: number) => systemEventApi.markResolved(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['system-event'], exact: false })
    },
  })

  const items: SystemEvent[] = data?.items ?? []
  const totalCount = data?.totalCount ?? 0
  const unreadCount = data?.unreadCount ?? 0
  const hasNextPage = items.length >= PAGE_SIZE

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      {/* 헤더 */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-white">시스템 이벤트 운영 대시보드</h1>
        <p className="mt-1 text-sm text-gray-400">
          Reconcile · WebSocket · KIS Rate Limit · DB · Redis 등 시스템 운영 이벤트 시계열
          (ADR 0084 P0 system_event_log)
        </p>
      </div>

      {/* 필터 + 통계 */}
      <div className="mb-4 flex items-center justify-between gap-4 rounded-lg border border-gray-800 bg-gray-900/40 px-4 py-3">
        <label className="flex items-center gap-2 text-sm text-gray-300">
          <input
            type="checkbox"
            checked={unreadOnly}
            onChange={(e) => {
              setUnreadOnly(e.target.checked)
              setPage(0)
            }}
            className="h-4 w-4 rounded border-gray-600 bg-gray-800 text-blue-500 focus:ring-2 focus:ring-blue-400"
          />
          미확인만 (viewed_at IS NULL)
        </label>
        <div className="text-xs text-gray-400">
          총 <span className="font-semibold text-white">{totalCount.toLocaleString('ko-KR')}</span>건
          {' · '}
          미확인 <span className="font-semibold text-amber-300">{unreadCount.toLocaleString('ko-KR')}</span>건
        </div>
      </div>

      {/* 본문 */}
      {isLoading ? (
        <div className="rounded-lg border border-gray-800 bg-gray-900/40 p-12 text-center text-sm text-gray-500">
          로딩 중...
        </div>
      ) : isError ? (
        <div className="rounded-lg border border-red-800 bg-red-950/40 p-6 text-sm text-red-300">
          이벤트 조회 실패: {error instanceof Error ? error.message : 'unknown error'}
        </div>
      ) : items.length === 0 ? (
        <div className="rounded-lg border border-gray-800 bg-gray-900/40 p-12 text-center text-sm text-gray-500">
          {unreadOnly ? '미확인 이벤트가 없습니다.' : '시스템 이벤트가 없습니다.'}
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-gray-800">
          <table className="w-full border-collapse text-sm">
            <thead className="bg-gray-900 text-xs uppercase tracking-wide text-gray-400">
              <tr>
                <th className="border-b border-gray-800 p-3 text-left font-medium">발생 시각</th>
                <th className="border-b border-gray-800 p-3 text-left font-medium">카테고리</th>
                <th className="border-b border-gray-800 p-3 text-left font-medium">심각도</th>
                <th className="border-b border-gray-800 p-3 text-left font-medium">Action</th>
                <th className="border-b border-gray-800 p-3 text-left font-medium">Actor</th>
                <th className="border-b border-gray-800 p-3 text-left font-medium">사용자</th>
                <th className="border-b border-gray-800 p-3 text-left font-medium">Correlation</th>
                <th className="border-b border-gray-800 p-3 text-left font-medium">Detail</th>
                <th className="border-b border-gray-800 p-3 text-left font-medium">작업</th>
              </tr>
            </thead>
            <tbody>
              {items.map((event) => {
                const isResolved = event.resolvedAt !== null
                return (
                  <tr
                    key={event.id}
                    className={[
                      'border-b border-gray-800 transition-colors hover:bg-gray-900/60',
                      isResolved ? 'opacity-60' : '',
                    ].join(' ')}
                  >
                    <td className="p-3 text-xs text-gray-300">
                      {new Date(event.occurredAt).toLocaleString('ko-KR')}
                    </td>
                    <td className="p-3 text-xs text-gray-300">{event.category}</td>
                    <td className="p-3">
                      <span
                        className={[
                          'inline-flex rounded border px-2 py-0.5 text-xs',
                          SEVERITY_BADGE_CLASS[event.severity],
                        ].join(' ')}
                      >
                        {event.severity}
                      </span>
                    </td>
                    <td className="p-3 font-mono text-xs text-gray-200">{event.action}</td>
                    <td className="p-3 text-xs text-gray-400">{event.actor}</td>
                    <td className="p-3 text-xs text-gray-400">
                      {event.userId ?? <span className="italic text-gray-600">(전체)</span>}
                    </td>
                    <td className="p-3 font-mono text-xs text-gray-500" title={event.correlationId ?? ''}>
                      {event.correlationId ? event.correlationId.slice(0, 8) : '-'}
                    </td>
                    <td className="max-w-xs p-3">
                      <DetailCell
                        detail={event.detailJson}
                        onActivate={() => setDetailEvent(event)}
                      />
                    </td>
                    <td className="p-3">
                      {isResolved ? (
                        <span className="text-xs text-emerald-400">✓ 해결됨</span>
                      ) : (
                        <button
                          type="button"
                          onClick={() => markResolvedMutation.mutate(event.id)}
                          disabled={markResolvedMutation.isPending}
                          className="rounded px-2 py-1 text-xs text-blue-400 transition-colors hover:bg-blue-950 hover:text-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-400 disabled:opacity-50"
                        >
                          해결 표시
                        </button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* 페이지네이션 */}
      <div className="mt-4 flex items-center justify-between gap-4">
        <button
          type="button"
          disabled={page === 0}
          onClick={() => setPage((p) => Math.max(0, p - 1))}
          className="rounded-lg border border-gray-700 bg-gray-900 px-4 py-2 text-sm text-gray-300 transition-colors hover:border-gray-600 hover:text-white focus:outline-none focus:ring-2 focus:ring-blue-400 disabled:opacity-40"
        >
          ← 이전
        </button>
        <div className="text-sm text-gray-400">
          페이지 <span className="font-semibold text-white">{page + 1}</span>
        </div>
        <button
          type="button"
          disabled={!hasNextPage}
          onClick={() => setPage((p) => p + 1)}
          className="rounded-lg border border-gray-700 bg-gray-900 px-4 py-2 text-sm text-gray-300 transition-colors hover:border-gray-600 hover:text-white focus:outline-none focus:ring-2 focus:ring-blue-400 disabled:opacity-40"
        >
          다음 →
        </button>
      </div>

      {/* Detail 클릭 상세 모달 */}
      {detailEvent !== null && (
        <SystemEventDetailModal
          event={detailEvent}
          onClose={() => setDetailEvent(null)}
        />
      )}
    </div>
  )
}
