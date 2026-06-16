/**
 * BacktestList — 사용자 백테스트 목록 (페이징) (T-BT R3)
 *
 * GET /api/backtests?userId=&page=&size=
 * 목록 클릭 시 onSelect(runId) 호출 → BacktestDetail 전환
 * 삭제 버튼 → DELETE /api/backtests/{id}
 *
 * feedback_no_mock_data.md 정합: 0건 → "백테스트 없음" 안내
 * 룰 5: LLM provider import 0건
 */
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { backtestApi } from '../../api/backtest'
import type { BacktestRunSummaryDto } from '../../api/backtest'

interface BacktestListProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  onSelect: (runId: number) => void
}

/** BigDecimal string → 퍼센트 */
function fmtPct(v: string | null): string {
  if (!v) return '—'
  const n = parseFloat(v)
  if (isNaN(n)) return '—'
  const sign = n > 0 ? '+' : ''
  return `${sign}${(n * 100).toFixed(2)}%`
}

function returnColor(v: string | null): string {
  if (!v) return 'text-gray-500'
  const n = parseFloat(v)
  if (n > 0) return 'text-emerald-400'
  if (n < 0) return 'text-red-400'
  return 'text-gray-400'
}

function StatusBadge({ status }: { status: BacktestRunSummaryDto['status'] }) {
  const map = {
    PENDING: { cls: 'bg-yellow-900/50 text-yellow-300', label: '대기' },
    RUNNING: { cls: 'bg-blue-900/50 text-blue-300', label: '실행 중' },
    SUCCEEDED: { cls: 'bg-emerald-900/50 text-emerald-300', label: '완료' },
    FAILED: { cls: 'bg-red-900/50 text-red-300', label: '실패' },
  }
  const { cls, label } = map[status]
  return (
    <span className={`rounded px-1.5 py-0.5 text-xs font-semibold ${cls}`}>{label}</span>
  )
}

export function BacktestList({ userId, onSelect }: BacktestListProps) {
  const [page, setPage] = useState(0)
  const PAGE_SIZE = 20
  const queryClient = useQueryClient()

  const { data, isLoading, isError } = useQuery({
    queryKey: ['backtests', userId, page],
    queryFn: () => backtestApi.list(userId, page, PAGE_SIZE),
    staleTime: 10_000,
  })

  const { mutate: deleteRun, isPending: isDeleting } = useMutation({
    mutationFn: (id: number) => backtestApi.delete(id, userId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['backtests', userId] })
    },
  })

  const handleDelete = (e: React.MouseEvent, id: number) => {
    e.stopPropagation()
    if (confirm('이 백테스트 기록을 삭제하시겠습니까?')) {
      deleteRun(id)
    }
  }

  if (isLoading) {
    return (
      <div className="flex h-32 items-center justify-center">
        <div className="flex flex-col items-center gap-2">
          <div className="h-5 w-5 animate-spin rounded-full border-2 border-gray-700 border-t-blue-500" />
          <p className="text-xs text-gray-600">목록 불러오는 중...</p>
        </div>
      </div>
    )
  }

  if (isError) {
    return (
      <div className="rounded-xl border border-red-900/50 bg-red-900/20 p-4 text-center">
        <p className="text-sm text-red-400">백테스트 목록을 불러올 수 없습니다</p>
      </div>
    )
  }

  const items = data?.content ?? []

  return (
    <div className="space-y-2">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-gray-300">
          실행 이력
          {data && (
            <span className="ml-2 text-xs font-normal text-gray-600">
              (총 {data.totalElements.toLocaleString('ko-KR')}건)
            </span>
          )}
        </h2>
        {data && data.totalPages > 1 && (
          <div className="flex items-center gap-1 text-xs text-gray-500">
            <button
              className="rounded px-2 py-0.5 hover:bg-gray-800 disabled:opacity-40"
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
            >
              이전
            </button>
            <span>
              {page + 1} / {data.totalPages}
            </span>
            <button
              className="rounded px-2 py-0.5 hover:bg-gray-800 disabled:opacity-40"
              disabled={page >= data.totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
            >
              다음
            </button>
          </div>
        )}
      </div>

      {/* 0건 */}
      {items.length === 0 && (
        <div className="flex h-24 items-center justify-center rounded-xl border border-gray-800 bg-gray-900">
          <p className="text-sm text-gray-500">아직 실행된 백테스트가 없습니다</p>
        </div>
      )}

      {/* 목록 */}
      <div className="overflow-hidden rounded-xl border border-gray-800">
        {items.map((item, idx) => (
          <div
            key={item.id}
            className={[
              'flex cursor-pointer items-center gap-3 px-4 py-3 transition-colors hover:bg-gray-800/60',
              idx !== items.length - 1 ? 'border-b border-gray-800' : '',
              isDeleting ? 'pointer-events-none opacity-50' : '',
            ].join(' ')}
            onClick={() => onSelect(item.id)}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => e.key === 'Enter' && onSelect(item.id)}
          >
            {/* 왼쪽: 번호 + 메타 */}
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <span className="text-xs text-gray-600">#{item.id}</span>
                <span className="truncate text-sm font-medium text-gray-200">{item.code}</span>
                <span className="text-xs text-gray-600">{item.interval}</span>
                <StatusBadge status={item.status} />
              </div>
              <div className="mt-0.5 text-xs text-gray-600">
                {item.rangeFrom?.slice(0, 10)} ~ {item.rangeTo?.slice(0, 10)}
              </div>
            </div>

            {/* 오른쪽: KPI */}
            <div className="flex shrink-0 items-center gap-4 text-right">
              <div className="hidden sm:block">
                <div className="text-xs text-gray-600">총수익</div>
                <div className={`text-sm font-bold tabular-nums ${returnColor(item.totalReturn)}`}>
                  {fmtPct(item.totalReturn)}
                </div>
              </div>
              <div className="hidden md:block">
                <div className="text-xs text-gray-600">승률</div>
                <div className="text-sm tabular-nums text-blue-300">{fmtPct(item.winRate)}</div>
              </div>
              <div className="hidden lg:block">
                <div className="text-xs text-gray-600">거래수</div>
                <div className="text-sm tabular-nums text-gray-300">
                  {item.tradeCount !== null ? item.tradeCount : '—'}
                </div>
              </div>

              {/* 삭제 버튼 */}
              <button
                className="ml-2 rounded p-1.5 text-gray-600 hover:bg-red-900/30 hover:text-red-400"
                onClick={(e) => handleDelete(e, item.id)}
                title="삭제"
                aria-label="백테스트 삭제"
              >
                <svg className="h-3.5 w-3.5" viewBox="0 0 16 16" fill="currentColor">
                  <path d="M6.5 1h3a.5.5 0 0 1 .5.5v1H6v-1a.5.5 0 0 1 .5-.5M11 2.5v-1A1.5 1.5 0 0 0 9.5 0h-3A1.5 1.5 0 0 0 5 1.5v1H1.5a.5.5 0 0 0 0 1h.538l.853 10.66A2 2 0 0 0 4.885 16h6.23a2 2 0 0 0 1.994-1.84l.853-10.66h.538a.5.5 0 0 0 0-1zm1.958 1-.846 10.58a1 1 0 0 1-.997.92h-6.23a1 1 0 0 1-.997-.92L3.042 3.5zm-7.487 1a.5.5 0 0 1 .528.47l.5 8.5a.5.5 0 0 1-.998.06L5 5.03a.5.5 0 0 1 .47-.53Zm5.058 0a.5.5 0 0 1 .47.53l-.5 8.5a.5.5 0 1 1-.998-.06l.5-8.5a.5.5 0 0 1 .528-.47M8 4.5a.5.5 0 0 1 .5.5v8.5a.5.5 0 0 1-1 0V5a.5.5 0 0 1 .5-.5" />
                </svg>
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
