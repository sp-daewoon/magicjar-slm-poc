/**
 * BacktestForm — 백테스트 실행 폼 (T-BT R3)
 *
 * 입력 항목:
 *   - 전략 선택 (GET /api/strategies → strategyId)
 *   - 종목 검색 (복수 선택)
 *   - 인터벌 (1d · 12h · 4h · 1h · 90m · 30m · 15m · 5m · 3m · 1m)
 *   - 기간 (from · to, yyyy-MM-dd)
 *   - 초기자본 (KRW)
 *
 * 실행 버튼 → POST /api/backtests
 *
 * feedback_no_mock_data.md 정합: 오류 시 에러 메시지 표시
 * 룰 5: LLM provider import 0건
 */
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { sigStrategyApi, type SigStrategyDto } from '../../api/strategy'
import { backtestApi } from '../../api/backtest'
import type { BacktestRunPersistResponse } from '../../api/backtest'
import type { InstrumentSearchResultDto } from '../../api/instrument'
import { InstrumentSearchInput } from '../InstrumentSearchInput'

interface BacktestFormProps {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  onSuccess: (response: BacktestRunPersistResponse) => void
}

const INTERVAL_OPTIONS = [
  { value: '1d', label: '일봉 (1d)' },
  { value: '12h', label: '정규장 전체 (12h)' },
  { value: '4h', label: '4시간 (4h)' },
  { value: '1h', label: '1시간 (1h)' },
  { value: '90m', label: '90분 (90m)' },
  { value: '30m', label: '30분 (30m)' },
  { value: '15m', label: '15분 (15m)' },
  { value: '5m', label: '5분 (5m)' },
  { value: '3m', label: '3분 (3m)' },
  { value: '1m', label: '1분 (1m)' },
]

interface SelectedInstrument {
  code: string
  name?: string
  market?: string
  sector?: string | null
}

/** 기본 from: 오늘 기준 90거래일 전 (약 4.5개월) */
function defaultFrom(): string {
  const d = new Date()
  d.setDate(d.getDate() - 130)
  return d.toISOString().slice(0, 10)
}

function defaultTo(): string {
  return new Date().toISOString().slice(0, 10)
}

/** 천 단위 콤마 입력 → 숫자 문자열 */
function parseCapital(v: string): string {
  return v.replace(/,/g, '').trim()
}

function formatCapital(v: string): string {
  const n = parseInt(v.replace(/,/g, ''), 10)
  if (isNaN(n)) return v
  return n.toLocaleString('ko-KR')
}

export function BacktestForm({ userId, onSuccess }: BacktestFormProps) {
  const queryClient = useQueryClient()
  // 전략 카드 "백테스트 필요" 핸드오프 — URL ?strategyId=N prefill (자동 실행은 하지 않음)
  const [searchParams] = useSearchParams()
  const prefilledStrategyId = searchParams.get('strategyId') ?? ''
  const [strategyId, setStrategyId] = useState<string>(prefilledStrategyId)
  const [instrumentInput, setInstrumentInput] = useState('')
  const [selectedInstruments, setSelectedInstruments] = useState<SelectedInstrument[]>([])
  const [interval, setInterval] = useState('1d')
  const [from, setFrom] = useState(defaultFrom())
  const [to, setTo] = useState(defaultTo())
  const [capitalDisplay, setCapitalDisplay] = useState('10,000,000')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  // 전략 목록 조회
  const { data: strategyPage, isLoading: isStrategiesLoading } = useQuery({
    queryKey: ['sig-strategies', 'backtest-select', userId],
    queryFn: () => sigStrategyApi.list(userId, 0, 100),
    staleTime: 30_000,
  })
  const strategies: SigStrategyDto[] =
    strategyPage?.content.filter((strategy) => strategy.status !== 'DELETED') ?? []

  const { mutate: runBacktest, isPending: isRunning } = useMutation({
    mutationFn: () => {
      const capitalStr = parseCapital(capitalDisplay)
      if (!strategyId) throw new Error('전략을 선택해주세요')
      const universeList = selectedInstruments.map((item) => item.code)
      if (universeList.length === 0) throw new Error('종목을 선택해주세요')
      if (!from || !to) throw new Error('기간을 입력해주세요')
      if (from >= to) throw new Error('시작일은 종료일보다 이전이어야 합니다')

      return backtestApi.create(userId, {
        strategyId: parseInt(strategyId, 10),
        universe: universeList,
        interval,
        from,
        to,
        initialCapital: capitalStr,
        taxModel: 'korean_default',
        commissionModel: 'kis_direct',
        slippageModel: 'fixed_0.05',
        priceAdjustmentMode: 'ADJUSTED',
      })
    },
    onSuccess: (res) => {
      setErrorMsg(null)
      // 백테스트 성공 시 전략 활성화준비(activationReady) 상태가 바뀌므로
      // 전략 목록·단건 캐시를 무효화해 전략 카드가 최신 상태를 반영하도록 한다.
      void queryClient.invalidateQueries({ queryKey: ['sig-strategies'] })
      if (strategyId) {
        void queryClient.invalidateQueries({ queryKey: ['sig-strategies', strategyId] })
      }
      onSuccess(res)
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : '백테스트 실행 중 오류가 발생했습니다'
      setErrorMsg(msg)
    },
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setErrorMsg(null)
    runBacktest()
  }

  const handleInstrumentSelect = (result: InstrumentSearchResultDto) => {
    setSelectedInstruments((prev) => {
      if (prev.some((item) => item.code === result.code)) return prev
      return [
        ...prev,
        {
          code: result.code,
          name: result.name,
          market: result.market,
          sector: result.sector,
        },
      ]
    })
    setInstrumentInput('')
    setErrorMsg(null)
  }

  const handleRemoveInstrument = (code: string) => {
    setSelectedInstruments((prev) => prev.filter((item) => item.code !== code))
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="rounded-xl border border-gray-800 bg-gray-900 p-4"
    >
      <h2 className="mb-4 text-sm font-semibold text-gray-200">백테스트 설정</h2>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {/* 전략 선택 */}
        <div className="flex flex-col gap-1 lg:col-span-2">
          <label className="text-xs text-gray-500">전략 선택</label>
          <select
            value={strategyId}
            onChange={(e) => setStrategyId(e.target.value)}
            aria-label="전략 선택"
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          >
            <option value="">-- 전략을 선택하세요 --</option>
            {isStrategiesLoading && <option disabled>불러오는 중...</option>}
            {strategies.map((s) => (
              <option key={s.id} value={s.id}>
                {s.nameKo ?? s.name} ({s.intervals[0] ?? s.tf ?? '1d'})
              </option>
            ))}
          </select>
          {!isStrategiesLoading && strategies.length === 0 && (
            <p className="text-xs text-amber-500">
              저장된 전략이 없습니다. 전략 빌더에서 먼저 전략을 저장해주세요.
            </p>
          )}
        </div>

        {/* 인터벌 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500">캔들 인터벌</label>
          <select
            value={interval}
            onChange={(e) => setInterval(e.target.value)}
            aria-label="캔들 인터벌"
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            {INTERVAL_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        {/* 종목 검색 */}
        <div className="flex flex-col gap-1 sm:col-span-2 lg:col-span-3">
          <label className="text-xs text-gray-500">종목</label>
          <InstrumentSearchInput
            value={instrumentInput}
            onRawChange={setInstrumentInput}
            onSelect={handleInstrumentSelect}
            placeholder="종목명 또는 코드 검색"
            disabled={isRunning}
          />
          {selectedInstruments.length > 0 && (
            <div
              className="mt-2 flex flex-wrap gap-2"
              aria-label="선택된 백테스트 종목"
            >
              {selectedInstruments.map((item) => (
                <span
                  key={item.code}
                  className="inline-flex min-h-8 max-w-full items-center gap-2 rounded border border-gray-700 bg-gray-950 px-2.5 py-1 text-xs text-gray-200"
                >
                  <span className="truncate">{item.name ?? item.code}</span>
                  {item.name && <span className="font-mono text-gray-500">{item.code}</span>}
                  {item.market && <span className="text-gray-600">{item.market}</span>}
                  <button
                    type="button"
                    onClick={() => handleRemoveInstrument(item.code)}
                    className="ml-0.5 rounded p-0.5 text-gray-500 hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-1 focus:ring-blue-500"
                    aria-label={`${item.name ?? item.code} 제거`}
                    disabled={isRunning}
                  >
                    <XIcon />
                  </button>
                </span>
              ))}
            </div>
          )}
          {selectedInstruments.length > 0 && (
            <p className="text-xs text-gray-600">
              {selectedInstruments.length.toLocaleString('ko-KR')}종목 ·{' '}
              <span className="font-mono">
                {selectedInstruments.map((item) => item.code).join(',')}
              </span>
            </p>
          )}
        </div>

        {/* 시작일 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500">시작일</label>
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            max={to}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          />
        </div>

        {/* 종료일 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500">종료일</label>
          <input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            min={from}
            max={new Date().toISOString().slice(0, 10)}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          />
        </div>

        {/* 초기 자본 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500">초기 자본 (원)</label>
          <input
            type="text"
            inputMode="numeric"
            value={capitalDisplay}
            onChange={(e) => {
              const raw = e.target.value.replace(/[^0-9]/g, '')
              setCapitalDisplay(formatCapital(raw))
            }}
            placeholder="10,000,000"
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          />
        </div>
      </div>

      {/* 에러 메시지 */}
      {errorMsg && (
        <div className="mt-3 rounded-lg border border-red-900/50 bg-red-900/20 px-3 py-2 text-sm text-red-400">
          {errorMsg}
        </div>
      )}

      {/* 실행 버튼 */}
      <div className="mt-4 flex justify-end">
        <button
          type="submit"
          disabled={isRunning || !strategyId}
          className="rounded-lg bg-blue-600 px-5 py-2 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
        >
          {isRunning ? (
            <span className="flex items-center gap-2">
              <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/30 border-t-white" />
              실행 중...
            </span>
          ) : (
            '백테스트 실행'
          )}
        </button>
      </div>
    </form>
  )
}

function XIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      className="h-3.5 w-3.5"
      aria-hidden="true"
    >
      <path d="M4 4l8 8M12 4l-8 8" />
    </svg>
  )
}
