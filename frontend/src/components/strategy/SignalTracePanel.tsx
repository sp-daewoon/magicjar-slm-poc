/**
 * SignalTracePanel — 시그널 키 사슬 추적 드릴다운 (ADR 0115 §2-B 불변식 #4)
 *
 * 최고 관리자 직접 요구(2026-06-10): "전략이 사인 내면 몇 주 주문 내고, 몇 주가 그 전략으로
 * 사지고 팔아졌는지를 키로 추적." SignalCard 행 확장 시 lazy fetch 후 렌더:
 *   사인(요약) → 연결 주문 N건(수량·상태·발사 출처) → 체결 타임라인(부분체결 각각 수량·가격·시각)
 *            → 현재 전략 보유 수량 + 정합 badge(전략 귀속 == KIS 권위면 정합, 다르면 보정 대기)
 *
 * read-only. 데이터 없으면 "없음" 명시 (mock 합성 0건 — feedback_no_mock_data).
 * 룰 5: LLM provider import 0건.
 */
import { Link } from 'react-router-dom'
import { useSignalTrace } from '../../api/hooks/useSignal'
import type {
  SignalTraceSummaryDto,
  TracedExecutionDto,
  TracedOrderDto,
} from '../../api/signal'
import { OrderStatusBadge } from '../order/OrderStatusBadge'
import { OrderTriggerBadge } from '../order/OrderTriggerBadge'

interface SignalTracePanelProps {
  signalId: string
  userId: number
  /** 확장 시에만 fetch (lazy) */
  enabled: boolean
}

/** ISO → KST 시:분:초 (체결 타임라인용) */
function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString('ko-KR', {
      timeZone: 'Asia/Seoul',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    })
  } catch {
    return iso
  }
}

function formatPrice(price: string | null | undefined): string {
  if (price === null || price === undefined) return '-'
  const n = Number(price)
  return Number.isNaN(n) ? String(price) : `${n.toLocaleString('ko-KR')}원`
}

export function SignalTracePanel({ signalId, userId, enabled }: SignalTracePanelProps) {
  const { data, isLoading, isError } = useSignalTrace(signalId, userId, enabled)

  if (isLoading) {
    return (
      <div className="mt-2 flex flex-col gap-2 rounded-lg border border-gray-800 bg-gray-950/60 p-3">
        <div className="h-4 w-1/3 animate-pulse rounded bg-gray-800" />
        <div className="h-12 animate-pulse rounded bg-gray-800/60" />
        <div className="h-8 animate-pulse rounded bg-gray-800/60" />
      </div>
    )
  }

  if (isError || !data) {
    return (
      <div className="mt-2 rounded-lg border border-red-900 bg-red-950/30 p-3 text-center text-xs text-red-400">
        키 사슬 추적 데이터를 불러오지 못했습니다.
      </div>
    )
  }

  const { orders, summary } = data

  return (
    <div className="mt-2 flex flex-col gap-3 rounded-lg border border-gray-800 bg-gray-950/60 p-3">
      {/* 요약 + 정합 badge */}
      <TraceSummary summary={summary} />

      {/* 연결 주문 → 체결 타임라인 */}
      <div className="flex flex-col gap-2">
        <p className="text-xs font-semibold text-gray-400">
          이 사인이 발사한 주문{' '}
          <span className="text-gray-500">({summary.orderCount}건)</span>
        </p>
        {orders.length === 0 ? (
          <p className="rounded-md border border-dashed border-gray-700 bg-gray-900/40 px-3 py-3 text-center text-xs text-gray-500">
            이 사인으로 발사된 주문이 없습니다.
            <br />
            <span className="text-gray-600">
              (HOLD 사인이거나, 리스크 게이트에서 차단됐거나, 아직 주문 발사 전입니다.)
            </span>
          </p>
        ) : (
          orders.map((order) => <TracedOrderRow key={order.id} order={order} />)
        )}
      </div>
    </div>
  )
}

// ──────────────────────────────────────────────
// 요약 + 정합 badge
// ──────────────────────────────────────────────

function TraceSummary({ summary }: { summary: SignalTraceSummaryDto }) {
  const reconciled = summary.reconciled
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-md bg-gray-900/60 px-3 py-2 text-xs">
      <Metric label="주문" value={`${summary.orderedQty.toLocaleString('ko-KR')}주`} />
      <span className="text-gray-700">→</span>
      <Metric label="체결" value={`${summary.filledQty.toLocaleString('ko-KR')}주`} />
      <span className="text-gray-700">→</span>
      <Metric
        label="전략 보유"
        value={`${summary.strategyHeldQty.toLocaleString('ko-KR')}주`}
      />
      <span className="text-gray-700">vs</span>
      <Metric
        label="KIS 권위"
        value={`${summary.authorityHeldQty.toLocaleString('ko-KR')}주`}
      />

      {/* 정합 badge */}
      <span
        className={[
          'ml-auto inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 font-medium',
          reconciled
            ? 'bg-emerald-100 text-emerald-800 border border-emerald-300'
            : 'bg-amber-100 text-amber-800 border border-amber-300',
        ].join(' ')}
        title={
          reconciled
            ? '전략 귀속 보유 합 == KIS 권위 position (정합)'
            : '전략 귀속 보유 ≠ KIS 권위 — 미귀속/다전략 혼재 (보정 대기, ADR 0115 §C2)'
        }
      >
        {reconciled ? '정합' : '보정 대기'}
      </span>

      {/* 전략 포지션 메타 (있을 때만) */}
      {summary.strategyAvgPrice != null && summary.strategyHeldQty > 0 && (
        <div className="basis-full pt-1 text-[11px] text-gray-500">
          평단 {formatPrice(summary.strategyAvgPrice)}
          {summary.strategyFilledLots != null && ` · ${summary.strategyFilledLots}회차`}
          {summary.strategyRealizedPnl != null &&
            ` · 실현손익 ${formatPrice(summary.strategyRealizedPnl)}`}
        </div>
      )}
    </div>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <span className="inline-flex items-baseline gap-1">
      <span className="text-gray-500">{label}</span>
      <span className="font-semibold text-white">{value}</span>
    </span>
  )
}

// ──────────────────────────────────────────────
// 주문 + 체결 타임라인 행
// ──────────────────────────────────────────────

function TracedOrderRow({ order }: { order: TracedOrderDto }) {
  const sideLabel = order.side === 'BUY' ? '매수' : '매도'
  const sideColor = order.side === 'BUY' ? 'text-red-400' : 'text-blue-400'

  return (
    <div className="rounded-md border border-gray-800 bg-gray-900/50 p-2.5">
      {/* 주문 헤더 */}
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <Link
          to={`/orders/${order.id}`}
          className="font-mono text-xs text-gray-400 hover:text-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-400"
          title="주문 상세로 이동"
        >
          #{order.id}
        </Link>
        <span className={`text-xs font-medium ${sideColor}`}>{sideLabel}</span>
        <span className="text-xs text-gray-300">{order.qty.toLocaleString('ko-KR')}주</span>
        <OrderStatusBadge status={order.status} />
        <OrderTriggerBadge trigger={order.orderTrigger} triggerRefId={order.triggerRefId} />
        {order.mode === 'REAL' && (
          <span className="rounded bg-red-900/50 px-1.5 py-0.5 text-[10px] font-bold text-red-400">
            REAL
          </span>
        )}
        <span className="ml-auto text-[11px] text-gray-500">
          체결 {order.filledQty.toLocaleString('ko-KR')}/{order.qty.toLocaleString('ko-KR')}주
        </span>
      </div>

      {order.rejectReason && (
        <p className="mt-1 text-[11px] text-red-400">거절 사유: {order.rejectReason}</p>
      )}

      {/* 체결 타임라인 (부분체결 N건) */}
      <ExecutionTimeline executions={order.executions} />
    </div>
  )
}

function ExecutionTimeline({ executions }: { executions: TracedExecutionDto[] }) {
  if (executions.length === 0) {
    return (
      <p className="mt-1.5 text-[11px] text-gray-600">체결 내역 없음 (미체결 또는 거절)</p>
    )
  }
  return (
    <ol className="mt-1.5 flex flex-col gap-1 border-l border-gray-800 pl-3">
      {executions.map((exec, i) => (
        <li key={exec.id} className="flex flex-wrap items-baseline gap-x-2 text-[11px]">
          <span className="font-mono text-gray-500">{i + 1}.</span>
          <span className="font-medium text-gray-200">
            {exec.executedQty.toLocaleString('ko-KR')}주
          </span>
          <span className="text-gray-400">@ {formatPrice(exec.executedPrice)}</span>
          <time className="text-gray-500" dateTime={exec.executedAt}>
            {formatTime(exec.executedAt)}
          </time>
        </li>
      ))}
    </ol>
  )
}
