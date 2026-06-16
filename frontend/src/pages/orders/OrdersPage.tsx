/**
 * OrdersPage — /orders 주문 목록 페이지 (T-O Phase 3 — ADR 0068 §16 · T-MAN Phase 2 — ADR 0082)
 *
 * 기능:
 * - 주문 시계열 목록 (GET /api/orders?userId=, 페이징)
 * - 상태 필터 (REQUESTED·ACK·FILLED·PARTIALLY_FILLED·REJECTED·CANCELLED)
 * - 종목코드 검색 + 일자 범위 조회 + 페이지 크기 선택
 * - 빈 목록 graceful 안내 (feedback_no_mock_data 정합)
 * - useStompOrder 구독 (4 event 토스트 + 목록 자동 갱신)
 * - userId 없으면 셋업 안내 (StrategyListPage 패턴 정합)
 * - T-MAN Phase 2: source filter chip (전체·자동·수동) + "신규 주문" 버튼 (/orders/new)
 */
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useOnboardingStore } from '../../stores/onboarding'
import { useOrders } from '../../api/hooks/useOrders'
import { useStompOrder } from '../../api/hooks/useStompOrder'
import { useRealTradingConsent } from '../../api/hooks/useUpdateRealTradingConsent'
import { useRuntimeFlags } from '../../api/hooks/useRuntimeFlags'
import { OrderCard } from '../../components/order/OrderCard'
import { InstrumentSearchInput } from '../../components/InstrumentSearchInput'
import type { OrderStatus, OrderSource } from '../../api/order'
import type { InstrumentSearchResultDto } from '../../api/instrument'

const STATUS_FILTER_OPTIONS: { value: OrderStatus | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'REQUESTED', label: '접수' },
  { value: 'ACK', label: 'KIS 접수' },
  { value: 'PENDING_RECONCILE', label: 'KIS 확인 중' },
  { value: 'PARTIALLY_FILLED', label: '부분 체결' },
  { value: 'FILLED', label: '체결 완료' },
  { value: 'REJECTED', label: '거부' },
  { value: 'CANCELLED', label: '취소' },
]

/** T-MAN B-2-extended — source filter chip 매트릭스 (전체·자동·수동) */
const SOURCE_FILTER_OPTIONS: { value: OrderSource | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'AUTO', label: '자동' },
  { value: 'MANUAL', label: '수동' },
]

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100]
const DEFAULT_FILTER_LOOKBACK_DAYS = 7

interface OrderDateFilter {
  fromDate: string
  toDate: string
}

function createDefaultOrderDateFilter(today = new Date()): OrderDateFilter {
  return {
    fromDate: formatOrderDate(today),
    toDate: formatOrderDate(addDays(today, -DEFAULT_FILTER_LOOKBACK_DAYS)),
  }
}

function toKstDayIso(date: string, boundary: 'start' | 'end'): string | undefined {
  if (!date) return undefined
  const time = boundary === 'start' ? '00:00:00.000' : '23:59:59.999'
  return new Date(`${date}T${time}+09:00`).toISOString()
}

function normalizeOrderDateRange(fromDate: string, toDate: string): { from?: string; to?: string } {
  if (!fromDate && !toDate) return {}
  if (!fromDate) return { to: toKstDayIso(toDate, 'end') }
  if (!toDate) return { from: toKstDayIso(fromDate, 'start') }

  const oldest = fromDate <= toDate ? fromDate : toDate
  const newest = fromDate <= toDate ? toDate : fromDate
  return {
    from: toKstDayIso(oldest, 'start'),
    to: toKstDayIso(newest, 'end'),
  }
}

function formatOrderDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function addDays(date: Date, days: number): Date {
  const next = new Date(date)
  next.setDate(next.getDate() + days)
  return next
}

function formatInstrumentDisplay(result: InstrumentSearchResultDto): string {
  return `${result.name} (${result.code})`
}

function extractInstrumentCode(value: string): string {
  const trimmed = value.trim()
  const labelMatch = trimmed.match(/\((\d{6})\)\s*$/)
  if (labelMatch) return labelMatch[1]
  return /^\d{6}$/.test(trimmed) ? trimmed : ''
}

function pageWindow(current: number, totalPages: number): number[] {
  const start = Math.max(0, current - 2)
  const end = Math.min(totalPages - 1, current + 2)
  return Array.from({ length: end - start + 1 }, (_, idx) => start + idx)
}

export function OrdersPage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const [statusFilter, setStatusFilter] = useState<OrderStatus | ''>('')
  const [sourceFilter, setSourceFilter] = useState<OrderSource | ''>('')
  const [instrumentInput, setInstrumentInput] = useState('')
  const [codeFilter, setCodeFilter] = useState('')
  const [dateFilter, setDateFilter] = useState<OrderDateFilter>(() => createDefaultOrderDateFilter())
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)
  const queryDateRange = normalizeOrderDateRange(dateFilter.fromDate, dateFilter.toDate)

  const { data, isLoading, isError, error } = useOrders({
    userId: userId ?? undefined,
    status: statusFilter || undefined,
    source: sourceFilter || undefined,
    code: codeFilter || undefined,
    from: queryDateRange.from,
    to: queryDateRange.to,
    page,
    size: pageSize,
  })

  // STOMP 구독 — 새 주문 이벤트 발생 시 토스트 + 목록 자동 갱신 (userId 있을 때만)
  // ADR 0089 Phase 2 — userId number; 0은 sentinel (disabled 상태에서만 통과)
  useStompOrder({ userId: userId ?? 0, enabled: userId !== null })

  // 실거래 동의 상태 배지용
  const { data: consent } = useRealTradingConsent(userId)
  const { data: runtimeFlags } = useRuntimeFlags(userId)
  const isRealMode =
    runtimeFlags?.mode === 'REAL' &&
    (runtimeFlags?.allowReal ?? false) &&
    (runtimeFlags?.userConsented ?? !!consent?.realTradingConsentAt)

  function handleSearchSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setCodeFilter(extractInstrumentCode(instrumentInput))
    setPage(0)
  }

  function handleInstrumentRawChange(value: string) {
    setInstrumentInput(value)
    setCodeFilter(extractInstrumentCode(value))
    setPage(0)
  }

  function handleInstrumentSelect(result: InstrumentSearchResultDto) {
    setInstrumentInput(formatInstrumentDisplay(result))
    setCodeFilter(result.code)
    setPage(0)
  }

  function clearFilters() {
    setStatusFilter('')
    setSourceFilter('')
    setInstrumentInput('')
    setCodeFilter('')
    setDateFilter(createDefaultOrderDateFilter())
    setPage(0)
  }

  // userId 미등록 시 셋업 안내
  if (!userId) {
    return (
      <div className="mx-auto flex max-w-2xl flex-col items-center gap-6 px-4 py-24 text-center">
        <div className="rounded-full bg-blue-950 p-5">
          <svg
            className="h-10 w-10 text-blue-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.5}
            aria-hidden
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M9 12h3.75M9 15h3.75M9 18h3.75m3 .75H18a2.25 2.25 0 002.25-2.25V6.108c0-1.135-.845-2.098-1.976-2.192a48.424 48.424 0 00-1.123-.08m-5.801 0c-.065.21-.1.433-.1.664 0 .414.336.75.75.75h4.5a.75.75 0 00.75-.75 2.25 2.25 0 00-.1-.664m-5.8 0A2.251 2.251 0 0113.5 2.25H15c1.012 0 1.867.668 2.15 1.586m-5.8 0c-.376.023-.75.05-1.124.08C9.095 4.01 8.25 4.973 8.25 6.108V8.25m0 0H4.875c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125h9.75c.621 0 1.125-.504 1.125-1.125V9.375c0-.621-.504-1.125-1.125-1.125H8.25zM6.75 12h.008v.008H6.75V12zm0 3h.008v.008H6.75V15zm0 3h.008v.008H6.75V18z" />
          </svg>
        </div>
        <h1 className="text-2xl font-bold text-white">주문 내역</h1>
        <p className="text-sm text-gray-400">
          KIS 계좌 등록을 완료하면 주문 내역을 확인할 수 있습니다.
        </p>
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
    <div className="mx-auto max-w-4xl px-4 py-6">
      {/* 헤더 */}
      <div className="mb-6 flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white">주문 내역</h1>
          <p className="mt-1 text-sm text-gray-400">
            자동매매 주문 의도·체결·감사 이벤트 시계열 (ADR 0068 T-O · ADR 0082 T-MAN)
          </p>
        </div>
        <div className="flex items-center gap-2">
          {/* T-MAN Phase 2: 신규 주문 버튼 — /orders/new */}
          <Link
            to="/orders/new"
            className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950"
          >
            + 신규 주문
          </Link>
          {/* 거래 모드 배지 */}
          {isRealMode ? (
            <span className="rounded-lg border border-red-800 bg-red-950/50 px-3 py-1.5 text-xs font-bold text-red-400">
              REAL 거래 모드
            </span>
          ) : (
            <span className="rounded-lg border border-gray-700 bg-gray-900 px-3 py-1.5 text-xs text-gray-500">
              MOCK 모드
            </span>
          )}
        </div>
      </div>

      <section className="mb-4 rounded-xl border border-gray-800 bg-gray-900/60 p-4">
        <form onSubmit={handleSearchSubmit} className="grid gap-3 lg:grid-cols-[1.3fr_1fr_auto]">
          <label className="flex flex-col gap-1 text-xs text-gray-500">
            종목
            <InstrumentSearchInput
              value={instrumentInput}
              onRawChange={handleInstrumentRawChange}
              onSelect={handleInstrumentSelect}
              placeholder="종목명 또는 코드 검색"
            />
          </label>
          <div className="grid grid-cols-2 gap-2">
            <label className="flex flex-col gap-1 text-xs text-gray-500">
              기간 시작
              <input
                type="date"
                value={dateFilter.fromDate}
                onChange={(e) => {
                  setDateFilter((prev) => ({ ...prev, fromDate: e.target.value }))
                  setPage(0)
                }}
                className="h-9 rounded-lg border border-gray-700 bg-gray-950 px-3 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </label>
            <label className="flex flex-col gap-1 text-xs text-gray-500">
              기간 종료
              <input
                type="date"
                value={dateFilter.toDate}
                onChange={(e) => {
                  setDateFilter((prev) => ({ ...prev, toDate: e.target.value }))
                  setPage(0)
                }}
                className="h-9 rounded-lg border border-gray-700 bg-gray-950 px-3 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </label>
          </div>
          <div className="flex items-end gap-2">
            <button
              type="submit"
              className="h-9 rounded-lg bg-blue-600 px-4 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950"
            >
              검색
            </button>
            <button
              type="button"
              onClick={clearFilters}
              className="h-9 rounded-lg border border-gray-700 px-3 text-sm text-gray-400 transition-colors hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
            >
              초기화
            </button>
          </div>
        </form>

        <div className="mt-4 flex flex-wrap gap-2">
          <span className="self-center text-xs text-gray-500">상태:</span>
          {STATUS_FILTER_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              onClick={() => {
                setStatusFilter(opt.value)
                setPage(0)
              }}
              className={[
                'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
                statusFilter === opt.value
                  ? 'bg-blue-600 text-white'
                  : 'border border-gray-700 bg-gray-950 text-gray-400 hover:bg-gray-800',
              ].join(' ')}
            >
              {opt.label}
            </button>
          ))}
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <span className="self-center text-xs text-gray-500">출처:</span>
          {SOURCE_FILTER_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              onClick={() => {
                setSourceFilter(opt.value)
                setPage(0)
              }}
              className={[
                'rounded-lg px-3 py-1.5 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
                sourceFilter === opt.value
                  ? opt.value === 'MANUAL'
                    ? 'bg-amber-600 text-white'
                    : opt.value === 'AUTO'
                      ? 'bg-cyan-700 text-white'
                      : 'bg-blue-600 text-white'
                  : 'border border-gray-700 bg-gray-950 text-gray-400 hover:bg-gray-800',
              ].join(' ')}
              aria-pressed={sourceFilter === opt.value}
            >
              {opt.label}
            </button>
          ))}

          <label className="ml-auto flex items-center gap-2 text-xs text-gray-500">
            페이지 크기
            <select
              value={pageSize}
              onChange={(e) => {
                setPageSize(Number(e.target.value))
                setPage(0)
              }}
              className="h-8 rounded-lg border border-gray-700 bg-gray-950 px-2 text-xs text-gray-300 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            >
              {PAGE_SIZE_OPTIONS.map((size) => (
                <option key={size} value={size}>
                  {size}
                </option>
              ))}
            </select>
          </label>
        </div>
      </section>

      {/* 목록 */}
      {isLoading && (
        <div className="flex items-center justify-center py-12">
          <div className="flex flex-col items-center gap-3">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-800 border-t-blue-500" />
            <p className="text-sm text-gray-500">주문 내역 로딩 중...</p>
          </div>
        </div>
      )}

      {isError && (
        <div className="rounded-xl border border-red-900/50 bg-red-950/20 px-4 py-6 text-center">
          <p className="text-sm text-red-400">주문 내역 조회 실패</p>
          <p className="mt-1 text-xs text-red-600">{error?.message}</p>
        </div>
      )}

      {!isLoading && !isError && data && (
        <>
          {data.content.length === 0 ? (
            // 빈 목록 graceful (feedback_no_mock_data 정합)
            <div className="rounded-xl border border-gray-800 bg-gray-900/50 px-4 py-12 text-center">
              <p className="text-base text-gray-400">주문 내역이 없습니다.</p>
              <p className="mt-2 text-sm text-gray-600">
                {statusFilter || sourceFilter || codeFilter || dateFilter.fromDate || dateFilter.toDate
                  ? '선택한 조건에 맞는 주문이 없습니다.'
                  : '자동매매 시스템이 주문을 발행하면 이 목록에 표시됩니다.'}
              </p>
            </div>
          ) : (
            <div className="space-y-2">
              {data.content.map((order) => (
                <OrderCard key={order.id} order={order} />
              ))}
            </div>
          )}

          <div className="mt-6 flex flex-col gap-3 border-t border-gray-800 pt-4 sm:flex-row sm:items-center sm:justify-between">
            <p className="text-xs text-gray-500">
              전체 {data.totalElements.toLocaleString('ko-KR')}건 · {data.totalPages === 0 ? 0 : page + 1}/{data.totalPages} 페이지
            </p>
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => setPage(0)}
                disabled={page === 0}
                className="h-8 rounded-lg border border-gray-700 px-3 text-xs text-gray-400 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-40"
              >
                처음
              </button>
              <button
                type="button"
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="h-8 rounded-lg border border-gray-700 px-3 text-xs text-gray-400 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-40"
              >
                이전
              </button>
              {data.totalPages > 1 &&
                pageWindow(page, data.totalPages).map((pageNo) => (
                  <button
                    key={pageNo}
                    type="button"
                    onClick={() => setPage(pageNo)}
                    aria-current={pageNo === page ? 'page' : undefined}
                    className={[
                      'h-8 min-w-8 rounded-lg px-2 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
                      pageNo === page
                        ? 'bg-blue-600 text-white'
                        : 'border border-gray-700 text-gray-400 hover:bg-gray-800',
                    ].join(' ')}
                  >
                    {pageNo + 1}
                  </button>
                ))}
              <button
                type="button"
                onClick={() => setPage((p) => Math.min(data.totalPages - 1, p + 1))}
                disabled={data.last || data.totalPages === 0}
                className="h-8 rounded-lg border border-gray-700 px-3 text-xs text-gray-400 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-40"
              >
                다음
              </button>
              <button
                type="button"
                onClick={() => setPage(Math.max(0, data.totalPages - 1))}
                disabled={data.last || data.totalPages === 0}
                className="h-8 rounded-lg border border-gray-700 px-3 text-xs text-gray-400 hover:bg-gray-800 disabled:cursor-not-allowed disabled:opacity-40"
              >
                마지막
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
