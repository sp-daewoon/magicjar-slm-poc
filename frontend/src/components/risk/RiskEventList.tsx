/**
 * RiskEventList — 리스크 이벤트 페이징 테이블 (T-RISK Phase 3 — ADR 0067)
 *
 * 컬럼: 시각·규칙·판정·사유·시그널 링크
 * 빈 상태: "리스크 위반 이력 없음" 안내
 * 더 보기: 무한 스크롤 "더 보기" 버튼
 */
import { Link } from 'react-router-dom'
import { useRiskEvents } from '../../api/hooks/useRiskEvents'
import { type RiskVerdict } from '../../api/risk'
import { buildRiskAlertMessageParts } from '../../api/riskAlertMessage'
import { RiskEventBadge } from './RiskEventBadge'

/** KST 날짜+시간 포맷 */
function formatKst(iso: string): string {
  try {
    return new Date(iso).toLocaleString('ko-KR', {
      timeZone: 'Asia/Seoul',
      year: '2-digit',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
  } catch {
    return iso
  }
}

interface RiskEventListProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  verdictFilter?: RiskVerdict
  from?: string
  to?: string
}

export function RiskEventList({ userId, verdictFilter, from, to }: RiskEventListProps) {
  const { data, isLoading, isError, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useRiskEvents(userId, { verdict: verdictFilter, from, to }, 20)

  const queriedEvents = data?.pages.flatMap((p) => p.content) ?? []
  const events = verdictFilter
    ? queriedEvents.filter((evt) => evt.verdict === verdictFilter)
    : queriedEvents

  // 로딩
  if (isLoading) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="h-10 animate-pulse rounded bg-gray-800" />
        ))}
      </div>
    )
  }

  // 오류
  if (isError) {
    return (
      <div className="rounded-lg border border-red-800 bg-red-900/20 p-4 text-center text-sm text-red-400">
        이벤트 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
      </div>
    )
  }

  // 빈 상태
  if (events.length === 0) {
    const isAutoPauseFilter = verdictFilter === 'AUTO_PAUSED'
    return (
      <div className="rounded-lg border border-gray-800 bg-gray-900/40 py-12 text-center">
        <p className="text-gray-500">
          {isAutoPauseFilter ? '자동 매수차단 이력이 없습니다.' : '리스크 위반 이력이 없습니다.'}
        </p>
        <p className="mt-1 text-xs text-gray-600">
          {isAutoPauseFilter
            ? '자동 매수차단은 최근 차단 이벤트 누적 결과이므로, 차단 필터에서 직전 원인을 확인하세요.'
            : '선택한 기간의 모든 시그널이 리스크 정책을 통과했습니다.'}
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-3">
      {/* 테이블 */}
      <div className="overflow-x-auto rounded-lg border border-gray-800">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 bg-gray-900/60">
              <th className="px-4 py-2.5 text-left text-xs font-medium text-gray-500">시각 (KST)</th>
              <th className="px-4 py-2.5 text-left text-xs font-medium text-gray-500">규칙</th>
              <th className="px-4 py-2.5 text-left text-xs font-medium text-gray-500">판정</th>
              <th className="px-4 py-2.5 text-left text-xs font-medium text-gray-500">원인</th>
              <th className="px-4 py-2.5 text-left text-xs font-medium text-gray-500">시그널</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800/60">
            {events.map((evt) => {
              const alert = buildRiskAlertMessageParts(evt)
              return (
                <tr key={evt.id} className="hover:bg-gray-800/30 transition-colors">
                  <td className="px-4 py-3 text-gray-400 tabular-nums whitespace-nowrap align-top">
                    {formatKst(evt.ts)}
                  </td>
                  <td className="px-4 py-3 text-gray-300 max-w-[160px] truncate align-top" title={evt.rule}>
                    {evt.rule}
                  </td>
                  <td className="px-4 py-3 align-top">
                    <RiskEventBadge verdict={evt.verdict} />
                  </td>
                  <td className="min-w-[22rem] px-4 py-3 text-gray-300 align-top">
                    <p className="font-medium text-gray-100">{alert.title}</p>
                    <p className="mt-1 text-xs leading-5 text-gray-400">{alert.message}</p>
                    {alert.formula && (
                      <p className="mt-2 whitespace-pre-line rounded-md bg-gray-950/70 px-2.5 py-2 font-mono text-[11px] leading-5 text-amber-100">
                        {alert.formula}
                      </p>
                    )}
                  </td>
                  <td className="px-4 py-3 align-top">
                    {evt.signalId ? (
                      <Link
                        to={`/strategy/list?signalId=${evt.signalId}`}
                        className="text-blue-400 hover:text-blue-300 text-xs underline-offset-2 hover:underline"
                      >
                        #{evt.signalId.slice(0, 8)}
                      </Link>
                    ) : (
                      <span className="text-gray-600">—</span>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {/* 더 보기 버튼 */}
      {hasNextPage && (
        <div className="flex justify-center">
          <button
            onClick={() => void fetchNextPage()}
            disabled={isFetchingNextPage}
            className="rounded-lg border border-gray-700 px-6 py-2 text-sm text-gray-400 hover:bg-gray-800 hover:text-gray-200 disabled:opacity-50 transition-colors"
          >
            {isFetchingNextPage ? '로딩 중...' : '더 보기'}
          </button>
        </div>
      )}
    </div>
  )
}
