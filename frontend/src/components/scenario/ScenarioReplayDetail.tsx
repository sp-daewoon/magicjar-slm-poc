import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { scenarioReplayApi } from '../../api/scenarioReplay'
import type { ScenarioEventDto } from '../../api/scenarioReplay'
import { ScenarioReplayChart } from './ScenarioReplayChart'
import {
  compactPayload,
  eventClass,
  eventLabel,
  formatKst,
  formatMoney,
  formatQuantity,
  statusClass,
  statusLabel,
} from './scenarioReplayFormat'

interface ScenarioReplayDetailProps {
  runId: number
  userId: string
  onBack: () => void
}

const DIAGNOSTIC_EVENT_TYPES = new Set(['CANDLE', 'SIGNAL_HOLD'])

function isDiagnosticEvent(event: ScenarioEventDto): boolean {
  return DIAGNOSTIC_EVENT_TYPES.has(event.type)
}

function EventNode({ event }: { event: ScenarioEventDto }) {
  const payload = compactPayload(event.payload)
  return (
    <li className="relative pl-8">
      <span className="absolute left-1 top-2 h-3 w-3 rounded-full border border-gray-700 bg-gray-950" />
      <div className={`rounded-lg border px-3 py-2 ${eventClass(event)}`}>
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded bg-black/20 px-2 py-0.5 text-xs font-semibold">
            {eventLabel(event.type)}
          </span>
          <span className="text-xs text-gray-400">#{event.sequenceNo}</span>
          <span className="text-xs text-gray-400">{formatKst(event.virtualTime)}</span>
          {event.strategyName && <span className="text-xs text-gray-400">전략 {event.strategyName}</span>}
        </div>
        <p className="mt-1 text-sm font-medium">{event.title}</p>
        <p className="mt-1 text-sm text-gray-300">{event.message}</p>
        {payload && (
          <details className="mt-2">
            <summary className="cursor-pointer text-xs text-gray-500 hover:text-gray-300">payload</summary>
            <pre className="mt-1 max-h-40 overflow-auto rounded bg-black/30 p-2 text-xs text-gray-400">{payload}</pre>
          </details>
        )}
      </div>
    </li>
  )
}

export function ScenarioReplayDetail({ runId, userId, onBack }: ScenarioReplayDetailProps) {
  const [showDiagnosticEvents, setShowDiagnosticEvents] = useState(false)

  useEffect(() => {
    setShowDiagnosticEvents(false)
  }, [runId])

  const { data, isLoading, isError } = useQuery({
    queryKey: ['scenario-replay-run', runId, userId],
    queryFn: () => scenarioReplayApi.get(runId, userId),
    staleTime: 10_000,
  })
  const eventPageCount = data ? Math.ceil(data.run.eventCount / 500) : 0
  const shouldFetchAllEvents = data ? data.run.eventCount > data.events.length : false
  const allEventsQuery = useQuery({
    queryKey: ['scenario-replay-run-events-all', runId, userId, data?.run.eventCount],
    enabled: shouldFetchAllEvents,
    queryFn: async () => {
      const pages = await Promise.all(
        Array.from({ length: eventPageCount }, (_, page) => scenarioReplayApi.listEvents(runId, userId, page, 500)),
      )
      return pages.flatMap((page) => page.content)
    },
    staleTime: 10_000,
  })

  if (isLoading) {
    return (
      <div className="flex h-40 items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-gray-700 border-t-blue-500" />
          <p className="text-xs text-gray-500">리플레이 타임라인 불러오는 중...</p>
        </div>
      </div>
    )
  }

  if (isError || !data) {
    return (
      <div className="rounded-lg border border-red-900/50 bg-red-950/30 p-6 text-center">
        <p className="text-sm text-red-300">시나리오 리플레이를 불러올 수 없습니다</p>
        <button type="button" className="mt-3 text-xs text-gray-500 underline hover:text-gray-300" onClick={onBack}>
          목록으로 돌아가기
        </button>
      </div>
    )
  }

  const { run, positions } = data
  const events = allEventsQuery.data ?? data.events
  const diagnosticEventCount = events.filter(isDiagnosticEvent).length
  const visibleEvents = showDiagnosticEvents ? events : events.filter((event) => !isDiagnosticEvent(event))
  const isEventPageLoading = shouldFetchAllEvents && allEventsQuery.isLoading

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <button
          type="button"
          className="rounded border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
          onClick={onBack}
        >
          목록으로
        </button>
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-gray-200">리플레이 #{run.id}</span>
          <span className={`rounded px-2 py-0.5 text-xs font-semibold ${statusClass(run.status)}`}>
            {statusLabel(run.status)}
          </span>
        </div>
      </div>

      <div className="grid gap-3 rounded-lg border border-gray-800 bg-gray-900 p-4 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <div className="text-xs text-gray-600">전략</div>
          <div className="mt-1 text-sm text-gray-200">{run.strategyName ?? `전략 ${run.strategyId}`}</div>
        </div>
        <div>
          <div className="text-xs text-gray-600">종목·interval</div>
          <div className="mt-1 text-sm text-gray-200">{run.code} · {run.interval}</div>
        </div>
        <div>
          <div className="text-xs text-gray-600">가상 시작 현금</div>
          <div className="mt-1 text-sm text-gray-200">{formatMoney(run.initialCash)}</div>
        </div>
        <div>
          <div className="text-xs text-gray-600">가상 최종 현금</div>
          <div className="mt-1 text-sm text-gray-200">{formatMoney(run.finalCash)}</div>
        </div>
        <div className="sm:col-span-2 lg:col-span-4">
          <div className="text-xs text-gray-600">가상 시간 범위</div>
          <div className="mt-1 text-sm text-gray-200">{formatKst(run.rangeFrom)} ~ {formatKst(run.rangeTo)}</div>
        </div>
        {run.abortReason && (
          <div className="sm:col-span-2 lg:col-span-4">
            <div className="text-xs text-gray-600">중단 사유</div>
            <div className="mt-1 text-sm text-amber-300">{run.abortReason}</div>
          </div>
        )}
      </div>

      {diagnosticEventCount > 0 && (
        <div className="flex justify-end">
          <label className="inline-flex cursor-pointer items-center gap-2 rounded border border-gray-800 bg-gray-950 px-3 py-2 text-xs text-gray-400 hover:border-gray-700 hover:text-gray-200">
            <input
              type="checkbox"
              aria-label="보류·캔들 평가 표시"
              className="h-4 w-4 rounded border-gray-700 bg-gray-950 text-blue-500 focus:ring-blue-500"
              checked={showDiagnosticEvents}
              onChange={(event) => setShowDiagnosticEvents(event.target.checked)}
            />
            <span>보류·캔들 평가 표시</span>
            <span aria-hidden="true" className="text-gray-600">
              {showDiagnosticEvents ? '표시 중' : `숨김 ${diagnosticEventCount.toLocaleString('ko-KR')}`}
            </span>
          </label>
        </div>
      )}

      <ScenarioReplayChart run={run} events={events} showDiagnosticEvents={showDiagnosticEvents} />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
        <section aria-labelledby="scenario-timeline-heading" className="rounded-lg border border-gray-800 bg-gray-950 p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 id="scenario-timeline-heading" className="text-sm font-semibold text-gray-200">
              가상 이벤트 타임라인
            </h2>
            <span className="text-xs text-gray-600">
              {visibleEvents.length.toLocaleString('ko-KR')} / {run.eventCount.toLocaleString('ko-KR')}건 표시
            </span>
          </div>
          {isEventPageLoading && (
            <div className="mb-3 rounded border border-blue-900/60 bg-blue-950/30 px-3 py-2 text-xs text-blue-300">
              전체 리플레이 이벤트 불러오는 중...
            </div>
          )}
          {visibleEvents.length === 0 ? (
            <div className="flex h-28 items-center justify-center rounded border border-gray-800 bg-gray-900">
              <p className="text-sm text-gray-500">표시할 핵심 이벤트가 없습니다</p>
            </div>
          ) : (
            <ol className="relative space-y-3 before:absolute before:left-[10px] before:top-2 before:h-[calc(100%-16px)] before:w-px before:bg-gray-800">
              {visibleEvents.map((event) => (
                <EventNode key={`${event.sequenceNo}-${event.type}-${event.virtualTime}`} event={event} />
              ))}
            </ol>
          )}
        </section>

        <aside className="rounded-lg border border-gray-800 bg-gray-900 p-4">
          <h2 className="text-sm font-semibold text-gray-200">가상 포지션</h2>
          <div className="mt-3 space-y-2">
            {positions.length === 0 ? (
              <p className="text-sm text-gray-500">남은 가상 포지션 없음</p>
            ) : (
              positions.map((position) => (
                <div key={position.id ?? position.code} className="rounded border border-gray-800 bg-gray-950 px-3 py-2">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium text-gray-200">{position.code}</span>
                    <span className="text-xs text-gray-500">{formatQuantity(position.quantity)}</span>
                  </div>
                  <div className="mt-1 text-xs text-gray-500">
                    평균 {formatMoney(position.averagePrice)}
                  </div>
                  <div className="mt-1 text-xs text-gray-600">{formatKst(position.virtualTime)}</div>
                </div>
              ))
            )}
          </div>
        </aside>
      </div>
    </div>
  )
}
