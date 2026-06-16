import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { scenarioReplayApi } from '../../api/scenarioReplay'
import type { ScenarioRunSummaryDto } from '../../api/scenarioReplay'
import { formatKst, formatMoney, statusClass, statusLabel } from './scenarioReplayFormat'

interface ScenarioReplayListProps {
  userId: string
  onSelect: (runId: number) => void
}

function StatusBadge({ status }: { status: ScenarioRunSummaryDto['status'] }) {
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-semibold ${statusClass(status)}`}>
      {statusLabel(status)}
    </span>
  )
}

export function ScenarioReplayList({ userId, onSelect }: ScenarioReplayListProps) {
  const [page, setPage] = useState(0)
  const queryClient = useQueryClient()
  const pageSize = 20

  const { data, isLoading, isError } = useQuery({
    queryKey: ['scenario-replay-runs', userId, page],
    queryFn: () => scenarioReplayApi.list(userId, page, pageSize),
    staleTime: 10_000,
  })

  const { mutate: deleteRun, isPending: isDeleting } = useMutation({
    mutationFn: (runId: number) => scenarioReplayApi.delete(runId, userId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['scenario-replay-runs', userId] })
    },
  })

  const handleDelete = (e: React.MouseEvent, runId: number) => {
    e.stopPropagation()
    if (confirm('이 시나리오 리플레이 기록을 삭제하시겠습니까?')) {
      deleteRun(runId)
    }
  }

  if (isLoading) {
    return (
      <div className="flex h-32 items-center justify-center">
        <div className="flex flex-col items-center gap-2">
          <div className="h-5 w-5 animate-spin rounded-full border-2 border-gray-700 border-t-blue-500" />
          <p className="text-xs text-gray-600">리플레이 이력 불러오는 중...</p>
        </div>
      </div>
    )
  }

  if (isError) {
    return (
      <div className="rounded-lg border border-red-900/50 bg-red-950/30 p-4 text-center">
        <p className="text-sm text-red-300">시나리오 리플레이 이력을 불러올 수 없습니다</p>
      </div>
    )
  }

  const items = data?.content ?? []

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-gray-300">
          실행 이력
          {data && (
            <span className="ml-2 text-xs font-normal text-gray-600">
              총 {data.totalElements.toLocaleString('ko-KR')}건
            </span>
          )}
        </h2>
        {data && data.totalPages > 1 && (
          <div className="flex items-center gap-2 text-xs text-gray-500">
            <button
              type="button"
              className="rounded px-2 py-1 hover:bg-gray-800 disabled:opacity-40"
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
            >
              이전
            </button>
            <span>{page + 1} / {data.totalPages}</span>
            <button
              type="button"
              className="rounded px-2 py-1 hover:bg-gray-800 disabled:opacity-40"
              disabled={page >= data.totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
            >
              다음
            </button>
          </div>
        )}
      </div>

      {items.length === 0 ? (
        <div className="flex h-28 items-center justify-center rounded-lg border border-gray-800 bg-gray-900">
          <p className="text-sm text-gray-500">저장된 시나리오 리플레이가 없습니다</p>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-gray-800">
          {items.map((run, idx) => (
            <div
              key={run.id}
              className={[
                'flex cursor-pointer items-center gap-3 px-4 py-3 transition-colors hover:bg-gray-800/60',
                idx !== items.length - 1 ? 'border-b border-gray-800' : '',
                isDeleting ? 'pointer-events-none opacity-50' : '',
              ].join(' ')}
              onClick={() => onSelect(run.id)}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => e.key === 'Enter' && onSelect(run.id)}
            >
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs text-gray-600">#{run.id}</span>
                  <span className="truncate text-sm font-medium text-gray-200">
                    {run.strategyName ?? `전략 ${run.strategyId}`}
                  </span>
                  <span className="text-xs text-gray-500">{run.code}</span>
                  <span className="text-xs text-gray-500">{run.interval}</span>
                  <StatusBadge status={run.status} />
                </div>
                <div className="mt-1 text-xs text-gray-600">
                  {formatKst(run.rangeFrom)} ~ {formatKst(run.rangeTo)}
                </div>
              </div>

              <div className="hidden shrink-0 text-right sm:block">
                <div className="text-xs text-gray-600">이벤트</div>
                <div className="text-sm tabular-nums text-gray-300">
                  {run.eventCount.toLocaleString('ko-KR')}건
                </div>
              </div>
              <div className="hidden shrink-0 text-right md:block">
                <div className="text-xs text-gray-600">최종 현금</div>
                <div className="text-sm tabular-nums text-gray-300">{formatMoney(run.finalCash)}</div>
              </div>
              <button
                type="button"
                className="rounded px-2 py-1 text-xs text-gray-600 hover:bg-red-950/40 hover:text-red-300"
                onClick={(e) => handleDelete(e, run.id)}
                aria-label="시나리오 리플레이 삭제"
              >
                삭제
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
