import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { sigStrategyApi, type SigStrategyDto } from '../../api/strategy'
import {
  scenarioReplayApi,
  scenarioReplayErrorMessage,
  type ScenarioFaultType,
  type ScenarioRunDetailDto,
} from '../../api/scenarioReplay'
import { defaultKstInput, kstInputToIso } from './scenarioReplayTime'

interface ScenarioReplayFormProps {
  userId: number
  onSuccess: (result: ScenarioRunDetailDto) => void
}

const INTERVAL_OPTIONS = [
  { value: '1m', label: '1분' },
  { value: '3m', label: '3분' },
  { value: '5m', label: '5분' },
  { value: '15m', label: '15분' },
  { value: '30m', label: '30분' },
  { value: '90m', label: '90분' },
  { value: '1h', label: '1시간' },
  { value: '4h', label: '4시간' },
  { value: '12h', label: '12시간' },
  { value: '1d', label: '일봉' },
]

const FAULT_OPTIONS: Array<{ value: ScenarioFaultType | 'NONE'; label: string }> = [
  { value: 'NONE', label: '장애 없음' },
  { value: 'BALANCE_LOOKUP_RATE_LIMITED', label: '잔고 조회 rate limit' },
  { value: 'BALANCE_LOOKUP_FAILED', label: '잔고 조회 실패' },
  { value: 'MARKET_CLOSED', label: '장종료 규칙' },
  { value: 'DATA_NOT_READY', label: '데이터 미준비' },
  { value: 'STRATEGY_TIMEOUT', label: '전략 평가 timeout' },
]

const DEFAULT_FAULT_MESSAGE: Record<ScenarioFaultType, string> = {
  BALANCE_LOOKUP_RATE_LIMITED: 'KIS 잔고 조회 rate limit으로 가상 주문 보류',
  BALANCE_LOOKUP_FAILED: '잔고 조회 실패로 가상 주문 미발행',
  MARKET_CLOSED: '리플레이 시장 세션 규칙상 가상 주문 거절',
  DATA_NOT_READY: '리플레이 candle 데이터 미준비',
  STRATEGY_TIMEOUT: '전략 평가 timeout으로 리플레이 중단',
}

function parseMoney(value: string): string {
  return value.replace(/,/g, '').trim()
}

function formatMoneyInput(value: string): string {
  const numeric = value.replace(/[^0-9]/g, '')
  if (!numeric) return ''
  return Number(numeric).toLocaleString('ko-KR')
}

function firstCode(strategy: SigStrategyDto | undefined): string | null {
  return strategy?.codes.find((code) => /^\d{6}$/.test(code)) ?? null
}

function firstInterval(strategy: SigStrategyDto | undefined): string | null {
  return strategy?.intervals[0] ?? strategy?.tf ?? null
}

export function ScenarioReplayForm({ userId, onSuccess }: ScenarioReplayFormProps) {
  const userIdStr = String(userId)
  const queryClient = useQueryClient()
  const [strategyId, setStrategyId] = useState('')
  const [code, setCode] = useState('005930')
  const [interval, setInterval] = useState('1m')
  const [from, setFrom] = useState(defaultKstInput(1, 13, 0))
  const [to, setTo] = useState(defaultKstInput(1, 14, 10))
  const [initialCash, setInitialCash] = useState('1,000,000')
  const [positionQuantity, setPositionQuantity] = useState('0')
  const [positionAveragePrice, setPositionAveragePrice] = useState('0')
  const [faultType, setFaultType] = useState<ScenarioFaultType | 'NONE'>('NONE')
  const [faultAt, setFaultAt] = useState(defaultKstInput(1, 14, 10))
  const [faultMessage, setFaultMessage] = useState('')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const { data: strategyPage, isLoading: isStrategiesLoading } = useQuery({
    queryKey: ['sig-strategies', 'scenario-replay-select', userId],
    queryFn: () => sigStrategyApi.list(userId, 0, 100),
    staleTime: 30_000,
  })

  const strategies = strategyPage?.content.filter((strategy) => strategy.status !== 'DELETED') ?? []
  const selectedStrategy = useMemo(
    () => strategies.find((strategy) => strategy.id === strategyId),
    [strategies, strategyId],
  )

  const { mutate: runReplay, isPending } = useMutation({
    mutationFn: () => {
      if (!strategyId) throw new Error('전략을 선택해주세요')
      if (!/^\d{6}$/.test(code.trim())) throw new Error('종목코드는 6자리 숫자여야 합니다')
      const fromIso = kstInputToIso(from)
      const toIso = kstInputToIso(to)
      if (fromIso > toIso) throw new Error('시작 시각은 종료 시각보다 이전이어야 합니다')
      const cash = parseMoney(initialCash)
      if (!cash || Number(cash) < 0) throw new Error('가상 시작 현금을 입력해주세요')

      const quantity = Number(positionQuantity)
      const averagePrice = parseMoney(positionAveragePrice)
      const initialPositions =
        quantity > 0
          ? [{ code: code.trim(), quantity, averagePrice: averagePrice || '0' }]
          : []
      const faults =
        faultType === 'NONE'
          ? []
          : [
              {
                at: kstInputToIso(faultAt),
                type: faultType,
                message: faultMessage.trim() || DEFAULT_FAULT_MESSAGE[faultType],
              },
            ]

      return scenarioReplayApi.create({
        userId: userIdStr,
        strategyId: Number(strategyId),
        code: code.trim(),
        interval,
        from: fromIso,
        to: toIso,
        initialCash: cash,
        initialPositions,
        faults,
      })
    },
    onSuccess: (result) => {
      setErrorMsg(null)
      void queryClient.invalidateQueries({ queryKey: ['scenario-replay-runs', userIdStr] })
      onSuccess(result)
    },
    onError: (error) => {
      setErrorMsg(scenarioReplayErrorMessage(error))
    },
  })

  const handleStrategyChange = (nextId: string) => {
    setStrategyId(nextId)
    const nextStrategy = strategies.find((strategy) => strategy.id === nextId)
    const nextCode = firstCode(nextStrategy)
    const nextInterval = firstInterval(nextStrategy)
    if (nextCode) setCode(nextCode)
    if (nextInterval) setInterval(nextInterval)
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        setErrorMsg(null)
        runReplay()
      }}
      className="rounded-lg border border-gray-800 bg-gray-900 p-4"
    >
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-gray-100">신규 리플레이</h2>
          <p className="mt-0.5 text-xs text-gray-500">가상 주문 타임라인 생성</p>
        </div>
        <span className="rounded border border-sky-800 bg-sky-950/30 px-2 py-1 text-xs font-medium text-sky-300">
          KIS 주문 미발행
        </span>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div className="flex flex-col gap-1 lg:col-span-2">
          <label htmlFor="scenario-strategy" className="text-xs text-gray-500">전략 선택</label>
          <select
            id="scenario-strategy"
            value={strategyId}
            onChange={(e) => handleStrategyChange(e.target.value)}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          >
            <option value="">-- 전략을 선택하세요 --</option>
            {isStrategiesLoading && <option disabled>불러오는 중...</option>}
            {strategies.map((strategy) => (
              <option key={strategy.id} value={strategy.id}>
                {strategy.nameKo ?? strategy.name} · {strategy.status}
              </option>
            ))}
          </select>
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="scenario-code" className="text-xs text-gray-500">종목코드</label>
          <input
            id="scenario-code"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            placeholder="005930"
            inputMode="numeric"
            required
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="scenario-interval" className="text-xs text-gray-500">캔들 interval</label>
          <select
            id="scenario-interval"
            value={interval}
            onChange={(e) => setInterval(e.target.value)}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            {INTERVAL_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="scenario-from" className="text-xs text-gray-500">시작 시각 (KST)</label>
          <input
            id="scenario-from"
            type="datetime-local"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="scenario-to" className="text-xs text-gray-500">종료 시각 (KST)</label>
          <input
            id="scenario-to"
            type="datetime-local"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            required
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="scenario-cash" className="text-xs text-gray-500">가상 시작 현금</label>
          <input
            id="scenario-cash"
            value={initialCash}
            onChange={(e) => setInitialCash(formatMoneyInput(e.target.value))}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            inputMode="numeric"
            required
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="scenario-position-qty" className="text-xs text-gray-500">가상 시작 보유수량</label>
          <input
            id="scenario-position-qty"
            value={positionQuantity}
            onChange={(e) => setPositionQuantity(e.target.value.replace(/[^0-9]/g, ''))}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            inputMode="numeric"
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="scenario-position-price" className="text-xs text-gray-500">가상 시작 평균단가</label>
          <input
            id="scenario-position-price"
            value={positionAveragePrice}
            onChange={(e) => setPositionAveragePrice(formatMoneyInput(e.target.value))}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
            inputMode="numeric"
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="scenario-fault-type" className="text-xs text-gray-500">장애 주입</label>
          <select
            id="scenario-fault-type"
            value={faultType}
            onChange={(e) => {
              const next = e.target.value as ScenarioFaultType | 'NONE'
              setFaultType(next)
              setFaultMessage(next === 'NONE' ? '' : DEFAULT_FAULT_MESSAGE[next])
            }}
            className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
          >
            {FAULT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>

        {faultType !== 'NONE' && (
          <>
            <div className="flex flex-col gap-1">
              <label htmlFor="scenario-fault-at" className="text-xs text-gray-500">장애 시각 (KST)</label>
              <input
                id="scenario-fault-at"
                type="datetime-local"
                value={faultAt}
                onChange={(e) => setFaultAt(e.target.value)}
                className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            <div className="flex flex-col gap-1 lg:col-span-3">
              <label htmlFor="scenario-fault-message" className="text-xs text-gray-500">장애 메시지</label>
              <input
                id="scenario-fault-message"
                value={faultMessage}
                onChange={(e) => setFaultMessage(e.target.value)}
                className="w-full rounded border border-gray-700 bg-gray-950 px-2.5 py-2 text-sm text-white focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
          </>
        )}
      </div>

      {selectedStrategy && (
        <div className="mt-3 rounded border border-gray-800 bg-gray-950 px-3 py-2 text-xs text-gray-500">
          선택 전략: <span className="text-gray-300">{selectedStrategy.nameKo ?? selectedStrategy.name}</span>
          <span className="mx-2 text-gray-700">|</span>
          상태: <span className="text-gray-300">{selectedStrategy.status}</span>
          <span className="mx-2 text-gray-700">|</span>
          대상: <span className="text-gray-300">{selectedStrategy.codes.join(', ') || '-'}</span>
        </div>
      )}

      {errorMsg && (
        <div className="mt-3 rounded border border-red-900/60 bg-red-950/30 px-3 py-2 text-sm text-red-300">
          {errorMsg}
        </div>
      )}

      <div className="mt-4 flex justify-end">
        <button
          type="submit"
          disabled={isPending || !strategyId}
          className="rounded bg-blue-600 px-5 py-2 text-sm font-semibold text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {isPending ? '리플레이 실행 중...' : '리플레이 실행'}
        </button>
      </div>
    </form>
  )
}
