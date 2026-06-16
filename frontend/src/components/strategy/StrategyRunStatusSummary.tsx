import { Link } from 'react-router-dom'
import { useMemo } from 'react'
import { useRuntimeFlags } from '../../api/hooks/useRuntimeFlags'
import { useOrders } from '../../api/hooks/useOrders'
import { useRiskConfig } from '../../api/hooks/useRiskConfig'
import { useRiskEvents } from '../../api/hooks/useRiskEvents'
import { useSignals } from '../../api/hooks/useSignal'
import { useSigStrategies } from '../../api/hooks/useSigStrategy'
import { formatStrategyTargetLabel, isPaperStrategy } from './strategyDisplay'

interface StrategyRunStatusSummaryProps {
  userId: number
}

function formatCurrency(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return '-'
  const n = Number(value)
  if (!Number.isFinite(n)) return String(value)
  return `${Math.floor(n).toLocaleString('ko-KR')}원`
}

function formatPercent(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return '-'
  const n = Number(value)
  if (!Number.isFinite(n)) return String(value)
  return `${n.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}%`
}

function formatKst(value: string | null | undefined): string {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}

export function StrategyRunStatusSummary({ userId }: StrategyRunStatusSummaryProps) {
  const riskWindow = useMemo(() => {
    const to = new Date()
    const from = new Date(to.getTime() - 24 * 60 * 60 * 1000)
    return {
      from: from.toISOString(),
      to: to.toISOString(),
    }
  }, [])
  const { data: runtimeFlags, isLoading: isRuntimeLoading } = useRuntimeFlags(userId)
  const { data: riskConfig } = useRiskConfig(userId)
  const { data: strategyPages, isLoading: isStrategiesLoading } = useSigStrategies(userId, 20)
  const { data: signalPages, isError: isSignalsError } = useSignals({ userId }, 5)
  const { data: riskEventPages, isError: isRiskEventsError } = useRiskEvents(userId, riskWindow, 3)
  const { data: autoOrders, isError: isOrdersError } = useOrders({
    userId,
    source: 'AUTO',
    page: 0,
    size: 5,
  })

  const strategies = strategyPages?.pages.flatMap((page) => page.content) ?? []
  const activeStrategies = strategies.filter((strategy) => strategy.status === 'ACTIVE')
  const buyBlockedStrategies = strategies.filter((strategy) => strategy.status === 'BUY_BLOCKED')
  const activePaperStrategies = activeStrategies.filter(isPaperStrategy)
  const activeLiveStrategies = activeStrategies.filter((strategy) => !isPaperStrategy(strategy))
  const buyBlockedPaperStrategies = buyBlockedStrategies.filter(isPaperStrategy)
  const buyBlockedLiveStrategies = buyBlockedStrategies.filter((strategy) => !isPaperStrategy(strategy))
  const monitoringStrategies = [...activeStrategies, ...buyBlockedStrategies]
  const primaryStrategy = monitoringStrategies[0] ?? strategies[0]
  const latestSignal = signalPages?.pages[0]?.content[0]
  const latestRiskEvent = riskEventPages?.pages[0]?.content[0]
  const latestAutoOrder = autoOrders?.content[0]
  const killSwitchOn = runtimeFlags?.killSwitchEnabled ?? false
  const isMock = runtimeFlags?.mode !== 'REAL'

  const statusLabel = isRuntimeLoading || isStrategiesLoading
    ? '상태 확인 중'
    : killSwitchOn
      ? '주문 정지'
    : activeLiveStrategies.length > 0
      ? '자동매매 감시 중'
    : activePaperStrategies.length > 0
      ? '모의 감시 중'
    : buyBlockedStrategies.length > 0
      ? '매수차단 감시 중'
    : '자동매매중 전략 없음'

  const statusClass = killSwitchOn
    ? 'border-gray-700 bg-gray-800 text-gray-300'
    : activeLiveStrategies.length > 0
      ? 'border-emerald-700 bg-emerald-950/50 text-emerald-300'
      : activePaperStrategies.length > 0
        ? 'border-amber-700 bg-amber-950/50 text-amber-300'
      : buyBlockedStrategies.length > 0
        ? 'border-sky-700 bg-sky-950/50 text-sky-300'
        : 'border-amber-700 bg-amber-950/50 text-amber-300'

  return (
    <section
      aria-labelledby="strategy-run-status-heading"
      className="mb-8"
    >
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="strategy-run-status-heading" className="text-base font-semibold text-white">
            자동매매 현재 상태
          </h2>
          <p className="mt-1 text-sm text-gray-400">
            모드, 자동매매중 전략, 리스크 기준, 최근 자동 주문을 한 번에 봅니다.
          </p>
        </div>
        <span className={`rounded-md border px-3 py-1 text-xs font-semibold ${statusClass}`}>
          {statusLabel}
        </span>
      </div>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
        <div className="rounded-md border border-gray-800 bg-gray-950/60 p-3">
          <p className="text-xs text-gray-500">거래 모드</p>
          <p className="mt-1 text-sm font-semibold text-white">
            {isMock ? '모의계좌' : '실계좌'}
          </p>
          <p className="mt-1 text-xs text-gray-500">
            {killSwitchOn
              ? `KillSwitch ON${runtimeFlags?.killSwitchReason ? ` · ${runtimeFlags.killSwitchReason}` : ''}`
              : isMock
                ? '실주문 차단 상태'
                : runtimeFlags?.allowReal
                  ? '실거래 허용 상태'
                  : '실거래 환경 차단'}
          </p>
        </div>

        <div className="rounded-md border border-gray-800 bg-gray-950/60 p-3">
          <p className="text-xs text-gray-500">감시 중 전략</p>
          <p className="mt-1 text-sm font-semibold text-white">
            {monitoringStrategies.length.toLocaleString('ko-KR')}개
          </p>
          <p className="mt-1 truncate text-xs text-gray-500" title={primaryStrategy ? formatStrategyTargetLabel(primaryStrategy) : undefined}>
            {primaryStrategy
              ? `#${primaryStrategy.id} ${formatStrategyTargetLabel(primaryStrategy)}${
                  activePaperStrategies.length + buyBlockedPaperStrategies.length > 0
                    ? ` · 모의 ${activePaperStrategies.length + buyBlockedPaperStrategies.length}개`
                    : ''
                }${
                  activeLiveStrategies.length + buyBlockedLiveStrategies.length > 0
                    ? ` · 실전 ${activeLiveStrategies.length + buyBlockedLiveStrategies.length}개`
                    : ''
                }${
                  buyBlockedStrategies.length > 0 ? ` · 매수차단 ${buyBlockedStrategies.length}개` : ''
                }`
              : '전략을 자동매매 시작해야 신호를 평가합니다'}
          </p>
        </div>

        <div className="rounded-md border border-gray-800 bg-gray-950/60 p-3">
          <p className="text-xs text-gray-500">리스크 주문 기준</p>
          <p className="mt-1 text-sm font-semibold text-white">
            {formatCurrency(riskConfig?.maxPositionAmount)}
          </p>
          <p className="mt-1 text-xs text-gray-500">
            자산 대비 {formatPercent(riskConfig?.maxPositionPercent)} 중 작은 값
          </p>
        </div>

        <div className="rounded-md border border-gray-800 bg-gray-950/60 p-3">
          <p className="text-xs text-gray-500">최근 자동 주문</p>
          <p className="mt-1 text-sm font-semibold text-white">
            {isOrdersError
              ? '조회 실패'
              : latestAutoOrder
                ? `${latestAutoOrder.code} ${latestAutoOrder.side} ${latestAutoOrder.qty}주`
                : '아직 없음'}
          </p>
          <p className="mt-1 text-xs text-gray-500">
            {latestAutoOrder
              ? `${latestAutoOrder.status} · ${formatKst(latestAutoOrder.requestedAt)}`
              : '신호와 리스크 통과 후 여기에 표시됩니다'}
          </p>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-gray-800 pt-3 text-xs text-gray-500">
        <div className="flex flex-col gap-1">
          <span>
            최근 시그널:{' '}
            {isSignalsError
              ? '조회 실패'
              : latestSignal
                ? `${latestSignal.code} ${latestSignal.side} · ${formatKst(latestSignal.ts)}`
                : '조건 대기 중'}
          </span>
          <span>
            최근 리스크:{' '}
            {isRiskEventsError
              ? '조회 실패'
              : latestRiskEvent
                ? `${latestRiskEvent.rule} · ${latestRiskEvent.reason} · ${formatKst(latestRiskEvent.ts)}`
                : '차단 없음'}
          </span>
        </div>
        <div className="flex items-center gap-3">
          <Link to="/orders" className="font-medium text-blue-300 hover:text-blue-200">
            주문 보기
          </Link>
          <Link to="/risk-config" className="font-medium text-blue-300 hover:text-blue-200">
            리스크 보기
          </Link>
        </div>
      </div>
    </section>
  )
}
