/**
 * SignalListPanel — 사용자 시그널 시계열 패널 (T-SIG Phase 3 — ADR 0066)
 *
 * - useSignals(params) 무한 쿼리
 * - 실시간 prepend: useStompSignal signalQueue 수신 시 목록 상단 표시
 * - 필터: code·interval·side
 * - 빈 목록: "자동매매중 전략 시그널이 아직 발생하지 않았습니다"
 * - feedback_no_mock_data 정합
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { useSignals } from '../../api/hooks/useSignal'
import { useSigStrategies } from '../../api/hooks/useSigStrategy'
import { type InstrumentSearchResultDto } from '../../api/instrument'
import { type SignalDto, type SignalSide } from '../../api/signal'
import { InstrumentSearchInput } from '../InstrumentSearchInput'
import { SignalCard } from './SignalCard'

// ──────────────────────────────────────────────
// 필터 UI
// ──────────────────────────────────────────────

interface FilterState {
  code: string
  interval: string
  side: SignalSide | 'ALL'
  strategyId: string
}

const SIDE_OPTIONS: { value: FilterState['side']; label: string }[] = [
  { value: 'ALL',  label: '전체' },
  { value: 'BUY',  label: '매수' },
  { value: 'SELL', label: '매도' },
  { value: 'HOLD', label: '홀드' },
]

const INTERVAL_OPTIONS = ['', '1m', '3m', '5m', '15m', '30m', '90m', '1h', '4h', '12h', '1d']

type RangePreset = 'today' | '7d' | '30d' | 'custom'

const RANGE_OPTIONS: { value: RangePreset; label: string }[] = [
  { value: 'today',  label: '오늘' },
  { value: '7d',     label: '7일' },
  { value: '30d',    label: '30일' },
  { value: 'custom', label: '직접' },
]

/** 프리셋 → 조회 범위(from/to ISO). 상대 프리셋의 to는 '오늘 끝'으로 고정해 렌더마다 재요청되지 않게 한다. */
function computeRange(
  preset: RangePreset,
  customFrom: string,
  customTo: string,
): { from?: string; to?: string } {
  if (preset === 'custom') {
    const from = customFrom ? new Date(`${customFrom}T00:00:00`) : undefined
    const to = customTo ? new Date(`${customTo}T23:59:59.999`) : undefined
    return { from: from?.toISOString(), to: to?.toISOString() }
  }
  const to = new Date()
  to.setHours(23, 59, 59, 999)
  const from = new Date()
  from.setHours(0, 0, 0, 0)
  if (preset === '7d') from.setDate(from.getDate() - 6)
  else if (preset === '30d') from.setDate(from.getDate() - 29)
  return { from: from.toISOString(), to: to.toISOString() }
}

// ──────────────────────────────────────────────
// 컴포넌트
// ──────────────────────────────────────────────

interface SignalListPanelProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  /** 실시간 prepend — useStompSignal signalQueue */
  prependSignals?: SignalDto[]
  /**
   * 전략 필터 초기값 (선택) — 페이퍼 대시보드 등에서 특정 전략 시그널로 진입할 때 사용.
   * 초기 마운트에만 적용되므로 변경 시에는 key 재마운트로 갱신한다.
   */
  defaultStrategyId?: string
  /** 섹션 제목 (default: '시그널 내역') */
  title?: string
}

export function SignalListPanel({
  userId,
  prependSignals = [],
  defaultStrategyId = '',
  title = '시그널 내역',
}: SignalListPanelProps) {
  const [filter, setFilter] = useState<FilterState>({ code: '', interval: '', side: 'ALL', strategyId: defaultStrategyId })
  const [rangePreset, setRangePreset] = useState<RangePreset>('today')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const [codeSearchValue, setCodeSearchValue] = useState('')
  const [newIds, setNewIds] = useState<Set<string>>(new Set())

  // 전략 필터 드롭다운 — 사용자 전략 목록
  const { data: strategyData } = useSigStrategies(userId)
  const strategyOptions = strategyData?.pages.flatMap((p) => p.content) ?? []

  // 조회 범위 — 프리셋/커스텀 변경 시에만 재계산 (렌더 루프 방지)
  const range = useMemo(
    () => computeRange(rangePreset, customFrom, customTo),
    [rangePreset, customFrom, customTo],
  )

  // 새 시그널 하이라이트 — 3초 후 해제
  useEffect(() => {
    if (prependSignals.length === 0) return
    const ids = new Set(prependSignals.map((s) => s.id))
    setNewIds((prev) => new Set([...prev, ...ids]))
    const t = setTimeout(() => {
      setNewIds((prev) => {
        const next = new Set(prev)
        ids.forEach((id) => next.delete(id))
        return next
      })
    }, 3000)
    return () => clearTimeout(t)
  }, [prependSignals])

  const queryParams = {
    userId,
    code: filter.code || undefined,
    interval: filter.interval || undefined,
    side: filter.side === 'ALL' ? undefined : filter.side,
    strategyId: filter.strategyId || undefined,
    from: range.from,
    to: range.to,
  }

  const {
    data,
    isLoading,
    isError,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useSignals(queryParams, 20)

  // Intersection Observer
  const sentinelRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = sentinelRef.current
    if (!el) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && hasNextPage && !isFetchingNextPage) {
          void fetchNextPage()
        }
      },
      { rootMargin: '200px' },
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [fetchNextPage, hasNextPage, isFetchingNextPage])

  const restSignals = data?.pages.flatMap((p) => p.content) ?? []

  // prepend 중복 제거 (REST와 겹칠 경우)
  const restIds = new Set(restSignals.map((s) => s.id))
  const uniquePrepend = prependSignals.filter((s) => !restIds.has(s.id))
  const allSignals = [...uniquePrepend, ...restSignals]

  function handleInstrumentSelect(result: InstrumentSearchResultDto) {
    setCodeSearchValue(`${result.name} (${result.code})`)
    setFilter((f) => ({ ...f, code: result.code }))
  }

  function clearCodeFilter() {
    setCodeSearchValue('')
    setFilter((f) => ({ ...f, code: '' }))
  }

  return (
    <section aria-labelledby="signal-list-heading" className="flex flex-col gap-4">
      {/* 섹션 헤더 */}
      <div className="flex items-center justify-between">
        <h2 id="signal-list-heading" className="text-base font-semibold text-white">
          {title}
        </h2>
        <span className="text-xs text-gray-500">
          {data?.pages[0]?.totalElements !== undefined
            ? `총 ${data.pages[0].totalElements.toLocaleString()}건`
            : null}
        </span>
      </div>

      {/* 필터 바 */}
      <div className="flex flex-col gap-3">
        {/* 1행: 조회 기간 프리셋 */}
        <div className="flex flex-wrap items-center gap-3">
          <span className="text-xs text-gray-500">기간</span>
          <div
            role="group"
            aria-label="조회 기간"
            className="flex items-center rounded-lg bg-gray-900 p-1"
          >
            {RANGE_OPTIONS.map((opt) => (
              <button
                key={opt.value}
                aria-pressed={rangePreset === opt.value}
                onClick={() => setRangePreset(opt.value)}
                className={[
                  'rounded-md px-3 py-1 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
                  rangePreset === opt.value
                    ? 'bg-blue-600 text-white'
                    : 'text-gray-400 hover:text-gray-200',
                ].join(' ')}
              >
                {opt.label}
              </button>
            ))}
          </div>
          {rangePreset === 'custom' && (
            <div className="flex items-center gap-2 text-xs text-gray-400">
              <input
                type="date"
                value={customFrom}
                onChange={(e) => setCustomFrom(e.target.value)}
                aria-label="조회 시작일"
                className="rounded-lg border border-gray-700 bg-gray-800 px-2 py-1.5 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
              />
              <span>~</span>
              <input
                type="date"
                value={customTo}
                onChange={(e) => setCustomTo(e.target.value)}
                aria-label="조회 종료일"
                className="rounded-lg border border-gray-700 bg-gray-800 px-2 py-1.5 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
              />
            </div>
          )}
        </div>

        {/* 2행: 종목·전략·인터벌·방향 */}
        <div className="flex flex-wrap items-center gap-3">
          {/* 종목 검색 */}
          <div className="relative z-20 flex min-w-[260px] items-start gap-2">
            <div className="min-w-0 flex-1">
              <InstrumentSearchInput
                value={codeSearchValue}
                onSelect={handleInstrumentSelect}
                placeholder="종목명 또는 코드"
              />
            </div>
            {filter.code && (
              <button
                type="button"
                onClick={clearCodeFilter}
                className="rounded-lg border border-gray-700 bg-gray-800 px-3 py-2.5 text-xs font-medium text-gray-300 transition-colors hover:bg-gray-700 hover:text-white focus:outline-none focus:ring-2 focus:ring-blue-400"
              >
                초기화
              </button>
            )}
          </div>

          {/* 전략 필터 */}
          <select
            value={filter.strategyId}
            onChange={(e) => setFilter((f) => ({ ...f, strategyId: e.target.value }))}
            className="max-w-[200px] rounded-lg border border-gray-700 bg-gray-800 px-3 py-1.5 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
            aria-label="전략 필터"
          >
            <option value="">전체 전략</option>
            {strategyOptions.map((s) => (
              <option key={s.id} value={s.id}>
                {s.nameKo || s.name}
              </option>
            ))}
          </select>

          {/* 인터벌 선택 */}
          <select
            value={filter.interval}
            onChange={(e) => setFilter((f) => ({ ...f, interval: e.target.value }))}
            className="rounded-lg border border-gray-700 bg-gray-800 px-3 py-1.5 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
            aria-label="인터벌 필터"
            title="전략이 평가된 캔들 주기. 1m=1분봉, 5m=5분봉, 1h=1시간봉, 1d=일봉. '전체 인터벌'은 모든 주기 표시."
          >
            {INTERVAL_OPTIONS.map((iv) => (
              <option key={iv} value={iv}>
                {iv === '' ? '전체 인터벌' : iv}
              </option>
            ))}
          </select>

          {/* 매수/매도 탭 */}
          <div
            role="tablist"
            aria-label="매매 방향 필터"
            className="flex items-center rounded-lg bg-gray-900 p-1"
          >
            {SIDE_OPTIONS.map((opt) => (
              <button
                key={opt.value}
                role="tab"
                aria-selected={filter.side === opt.value}
                onClick={() => setFilter((f) => ({ ...f, side: opt.value }))}
                className={[
                  'rounded-md px-3 py-1 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
                  filter.side === opt.value
                    ? 'bg-blue-600 text-white'
                    : 'text-gray-400 hover:text-gray-200',
                ].join(' ')}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        {/* 인터벌 설명 */}
        <p className="text-xs text-gray-500">
          <span className="text-gray-400">인터벌</span>은 전략이 평가된{' '}
          <span className="text-gray-300">캔들 주기</span>입니다 (1m=1분봉 · 5m=5분봉 · 1h=1시간봉 · 1d=일봉).
          &lsquo;전체 인터벌&rsquo;은 모든 주기를 표시합니다.
        </p>
      </div>

      {/* 목록 */}
      {isLoading ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="h-16 animate-pulse rounded-lg bg-gray-800/60" />
          ))}
        </div>
      ) : isError ? (
        <div className="rounded-lg border border-red-900 bg-red-950/30 p-6 text-center">
          <p className="text-sm text-red-400">시그널 목록을 불러오지 못했습니다.</p>
        </div>
      ) : allSignals.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-gray-700 bg-gray-900/40 py-12 text-center">
          <p className="text-sm text-gray-400">
            자동매매중 전략 시그널이 아직 발생하지 않았습니다.
          </p>
          <p className="text-xs text-gray-500">전략을 자동매매 시작하면 시그널이 여기에 표시됩니다.</p>
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          {allSignals.map((signal) => (
            <SignalCard
              key={signal.id}
              signal={signal}
              isNew={newIds.has(signal.id)}
            />
          ))}
          <div ref={sentinelRef} className="h-4" />
          {isFetchingNextPage && (
            <p className="text-center text-xs text-gray-500">불러오는 중...</p>
          )}
        </div>
      )}
    </section>
  )
}
