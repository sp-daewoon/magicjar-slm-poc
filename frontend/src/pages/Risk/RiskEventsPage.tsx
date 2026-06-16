/**
 * RiskEventsPage — /risk-events (T-RISK Phase 3 — ADR 0067)
 *
 * 리스크 위반 이력 페이지:
 * - 날짜 범위 필터 + verdict 필터
 * - RiskEventList (무한 스크롤 테이블)
 * - 빈 상태 안내: "리스크 위반 이력 없음"
 * - userId 없으면 셋업 안내 (StrategyListPage 패턴 정합)
 */
import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import type { RiskVerdict } from '../../api/risk'
import { RiskEventList } from '../../components/risk/RiskEventList'

const VERDICT_OPTIONS: { value: RiskVerdict | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'ALLOWED', label: '허용' },
  { value: 'BLOCKED', label: '차단' },
  { value: 'AUTO_PAUSED', label: '자동 매수차단' },
]

const RANGE_OPTIONS = [
  { value: '1d', label: '오늘' },
  { value: '7d', label: '7일' },
  { value: '30d', label: '30일' },
] as const

type RangeOptionValue = (typeof RANGE_OPTIONS)[number]['value']

export function RiskEventsPage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const [searchParams, setSearchParams] = useSearchParams()
  const [verdictFilter, setVerdictFilter] = useState<RiskVerdict | ''>(() =>
    isRiskVerdict(searchParams.get('verdict')) ? searchParams.get('verdict') as RiskVerdict : '',
  )
  const [rangeFilter, setRangeFilter] = useState<RangeOptionValue>(() =>
    isRangeOption(searchParams.get('range')) ? searchParams.get('range') as RangeOptionValue : '30d',
  )

  const dateRange = useMemo(() => {
    const to = new Date()
    const from = new Date(to)
    if (rangeFilter === '1d') {
      from.setHours(0, 0, 0, 0)
    } else {
      const days = rangeFilter === '7d' ? 7 : 30
      from.setDate(from.getDate() - days)
    }
    return {
      from: from.toISOString(),
      to: to.toISOString(),
    }
  }, [rangeFilter])

  function updateVerdictFilter(value: RiskVerdict | '') {
    setVerdictFilter(value)
    const next = new URLSearchParams(searchParams)
    if (value) next.set('verdict', value)
    else next.delete('verdict')
    next.set('range', rangeFilter)
    setSearchParams(next, { replace: true })
  }

  function updateRangeFilter(value: RangeOptionValue) {
    setRangeFilter(value)
    const next = new URLSearchParams(searchParams)
    next.set('range', value)
    if (verdictFilter) next.set('verdict', verdictFilter)
    else next.delete('verdict')
    setSearchParams(next, { replace: true })
  }

  if (!userId) {
    return (
      <div className="mx-auto flex max-w-2xl flex-col items-center gap-6 px-4 py-24 text-center">
        <h1 className="text-2xl font-bold text-white">리스크 위반 이력</h1>
        <p className="text-sm text-gray-400">KIS 계좌 등록을 완료하면 리스크 위반 이력을 확인할 수 있습니다.</p>
        <Link
          to="/setup"
          className="rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950"
        >
          계좌 등록하러 가기
        </Link>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-5xl px-4 py-8">
      {/* 페이지 헤더 */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-white">리스크 위반 이력</h1>
        <p className="mt-2 text-sm text-gray-500">
          시그널이 리스크 정책에 의해 차단되거나 자동 매수차단된 이벤트 목록입니다. 기본은 최근 30일입니다.
        </p>
      </div>

      {/* 필터 영역 */}
      <div className="mb-6 flex flex-wrap items-center gap-5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-gray-500">기간</span>
          {RANGE_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => updateRangeFilter(opt.value)}
              className={[
                'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors',
                rangeFilter === opt.value
                  ? 'bg-blue-600 text-white'
                  : 'border border-gray-700 text-gray-400 hover:bg-gray-800 hover:text-gray-200',
              ].join(' ')}
            >
              {opt.label}
            </button>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-gray-500">판정</span>
          {VERDICT_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => updateVerdictFilter(opt.value)}
              className={[
                'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors',
                verdictFilter === opt.value
                  ? 'bg-blue-600 text-white'
                  : 'border border-gray-700 text-gray-400 hover:bg-gray-800 hover:text-gray-200',
              ].join(' ')}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* 이벤트 목록 */}
      <RiskEventList
        userId={userId}
        verdictFilter={verdictFilter || undefined}
        from={dateRange.from}
        to={dateRange.to}
      />
    </div>
  )
}

function isRiskVerdict(value: string | null): value is RiskVerdict {
  return value === 'ALLOWED' || value === 'BLOCKED' || value === 'AUTO_PAUSED'
}

function isRangeOption(value: string | null): value is RangeOptionValue {
  return value === '1d' || value === '7d' || value === '30d'
}
