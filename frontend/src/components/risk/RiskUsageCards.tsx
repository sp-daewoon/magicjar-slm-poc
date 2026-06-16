/**
 * RiskUsageCards — 리스크 한도 "사용량" 게이지 카드 4종
 *
 * 설정 화면(RiskConfigForm) 아래에 보강되는 읽기 전용 사용량 보드.
 * 각 한도를 "지금 얼마나 쓰고 있는지" 게이지로 노출한다.
 *
 *   A. 포지션 한도 (계좌 전체 공유) — "계좌 전체 공유 단일 풀" 배지로 종목별 오해 방지
 *   B. 일일 손실 한도 (DLL)
 *   C. 동시 보유 종목 — 점(●●●○○) + 게이지
 *   D. 거래 빈도 (전략당) — 콤보박스로 ACTIVE 전략 1개를 선택해 표시
 *     (선택된 전략 행만 useRiskUsage(userId, strategyId) 독립 호출 · 키 분리 캐싱.
 *      선택 state 는 카드 로컬 useState — 상위 호출 인자에 넣지 않아 스크롤 점프 회귀 방지)
 *
 * 데이터: GET /api/risk/usage (useRiskUsage, 10초 polling + 저장 시 강제 갱신)
 *
 * 정책:
 *   - mock 합성 금지 — 응답/섹션 null 이면 "준비 중 / 데이터 없음" 표기.
 *   - 게이지 색: 여유 초록 → 70%+ 노랑 → 90%+ 빨강.
 *   - 금액 천단위 콤마, 한국어 라벨.
 *
 * 룰 5: LLM provider import 0건
 */
import { useMemo, useState } from 'react'
import { useRiskUsage } from '../../api/hooks/useRiskUsage'
import { useStrategies } from '../../api/hooks/useStrategy'
import type {
  RiskUsageConcurrent,
  RiskUsageDailyLoss,
  RiskUsagePosition,
  RiskUsageTradeFrequency,
} from '../../api/riskUsage'

// ──────────────────────────────────────────────
// 포맷 유틸
// ──────────────────────────────────────────────

function won(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—'
  return `${Math.round(v).toLocaleString('ko-KR')}원`
}

function pct(used: number | null, limit: number | null): number | null {
  if (used === null || limit === null || limit <= 0) return null
  return (used / limit) * 100
}

/** 사용률 → 색상 등급 (여유/주의/임박) */
function gaugeLevel(ratio: number | null): 'safe' | 'warn' | 'danger' | 'none' {
  if (ratio === null) return 'none'
  if (ratio >= 90) return 'danger'
  if (ratio >= 70) return 'warn'
  return 'safe'
}

const BAR_COLOR: Record<'safe' | 'warn' | 'danger' | 'none', string> = {
  safe: 'bg-emerald-500',
  warn: 'bg-amber-400',
  danger: 'bg-red-500',
  none: 'bg-gray-600',
}

const TEXT_COLOR: Record<'safe' | 'warn' | 'danger' | 'none', string> = {
  safe: 'text-emerald-400',
  warn: 'text-amber-300',
  danger: 'text-red-400',
  none: 'text-gray-500',
}

// ──────────────────────────────────────────────
// 공통 프리미티브
// ──────────────────────────────────────────────

function CardShell({
  label,
  desc,
  badge,
  children,
}: {
  label: string
  desc?: string
  badge?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900/60 p-5">
      <div className="mb-3 flex items-start justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-gray-200">{label}</h3>
          {desc && <p className="mt-0.5 text-xs text-gray-500">{desc}</p>}
        </div>
        {badge}
      </div>
      {children}
    </div>
  )
}

/** 가로 게이지 바 + 우상단 % 라벨 */
function Gauge({
  ratio,
  caption,
}: {
  ratio: number | null
  caption?: string
}) {
  const level = gaugeLevel(ratio)
  const width = ratio === null ? 0 : Math.min(100, Math.max(0, ratio))
  return (
    <div>
      <div className="flex items-baseline justify-between">
        <span className="text-xs text-gray-500">{caption ?? '사용률'}</span>
        <span className={['text-sm font-semibold tabular-nums', TEXT_COLOR[level]].join(' ')}>
          {ratio === null ? '—' : `${ratio.toFixed(1)}%`}
        </span>
      </div>
      <div
        className="mt-1.5 h-2.5 w-full overflow-hidden rounded-full bg-gray-800"
        role="progressbar"
        aria-valuenow={ratio === null ? undefined : Math.round(width)}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={caption ?? '사용률'}
      >
        <div
          className={['h-full rounded-full transition-all duration-500', BAR_COLOR[level]].join(' ')}
          style={{ width: `${width}%` }}
        />
      </div>
    </div>
  )
}

/** 한 줄 라벨/값 */
function Row({
  label,
  value,
  emphasis,
}: {
  label: string
  value: React.ReactNode
  emphasis?: boolean
}) {
  return (
    <div className="flex items-center justify-between text-xs">
      <span className="text-gray-500">{label}</span>
      <span
        className={[
          'tabular-nums',
          emphasis ? 'text-sm font-semibold text-white' : 'text-gray-300',
        ].join(' ')}
      >
        {value}
      </span>
    </div>
  )
}

function EmptySection({ label, desc }: { label: string; desc?: string }) {
  return (
    <CardShell label={label} desc={desc}>
      <div className="rounded-lg border border-dashed border-gray-700 bg-gray-900/40 px-3 py-6 text-center text-xs text-gray-500">
        데이터 없음 · 준비 중
      </div>
    </CardShell>
  )
}

// ──────────────────────────────────────────────
// A. 포지션 한도 (계좌 전체 공유)
// ──────────────────────────────────────────────

/**
 * "계좌 전체 공유" 안내 배지 — 사용자가 종목별 한도로 오해하지 않도록
 * A 카드에 명시적으로 강조 표시 (피드백 #2).
 */
function SharedPoolBadge() {
  return (
    <span className="inline-flex items-center gap-1 rounded-md border border-blue-700/60 bg-blue-900/30 px-2 py-1 text-[11px] font-medium text-blue-300">
      <span aria-hidden="true">●</span>
      계좌 전체 공유 단일 풀 · 종목별 한도 아님
    </span>
  )
}

function PositionCard({ d }: { d: RiskUsagePosition | null }) {
  // position 객체 자체가 없을 때만 빈 표시.
  // capital(순자산·예수금) 미상으로 totalLimit 이 null 이어도 절대·비율·노출 구조는 렌더한다.
  if (!d) {
    return (
      <EmptySection
        label="A. 포지션 한도 (계좌 전체)"
        desc="계좌 전체가 공유하는 단일 풀 — 종목별 한도가 아닙니다."
      />
    )
  }
  // capital 미상 = 순자산 의존 필드(totalLimit 등)가 null. 절대/비율/노출만 가지고 부분 렌더.
  const capitalReady = d.totalLimit !== null
  const ratio = pct(d.totalExposure, d.totalLimit)
  return (
    <CardShell label="A. 포지션 한도 (계좌 전체)">
      <div className="mb-3">
        <SharedPoolBadge />
      </div>
      {/* 항상 표시: 절대 한도 · 비율% · 현재 전체 노출액 */}
      <div className="mb-3 space-y-1.5">
        <Row label="절대 한도" value={won(d.absoluteLimit)} />
        <Row
          label="비율 한도"
          value={d.maxPositionPercent !== null ? `순자산 ${d.maxPositionPercent}%` : '—'}
        />
        <Row label="현재 전체 노출" value={won(d.totalExposure)} emphasis />
      </div>
      <Gauge
        ratio={ratio}
        caption={
          capitalReady
            ? `전체노출 ${won(d.totalExposure)} / ${won(d.totalLimit)}`
            : `전체노출 ${won(d.totalExposure)} · 총한도 순자산 정보 준비 중`
        }
      />
      <div className="mt-3 space-y-1.5">
        <Row label="남은 한도" value={won(d.remaining)} emphasis />
        <Row label="예수금(주문가능 현금)" value={won(d.orderableCash)} />
        <Row label="실제 매수가능 (남은한도·예수금 중 작은 값)" value={won(d.buyable)} emphasis />
      </div>
      {capitalReady ? (
        <p className="mt-3 text-[11px] leading-relaxed text-gray-600">
          총한도 = min(절대 {won(d.absoluteLimit)}, 순자산
          {d.maxPositionPercent !== null ? ` ${d.maxPositionPercent}%` : ''} =
          {' '}
          {won(d.percentLimit)})
        </p>
      ) : (
        <p className="mt-3 rounded-md border border-dashed border-gray-700 bg-gray-900/40 px-3 py-2 text-[11px] leading-relaxed text-gray-500">
          순자산·예수금 정보 준비 중 — 잔고 동기화 후 총한도·매수가능이 표시됩니다.
        </p>
      )}
    </CardShell>
  )
}

// ──────────────────────────────────────────────
// B. 일일 손실 한도 (DLL)
// ──────────────────────────────────────────────

function DailyLossCard({ d }: { d: RiskUsageDailyLoss | null }) {
  if (!d) {
    return <EmptySection label="B. 일일 손실 한도 (DLL)" desc="하루 최대 허용 손실" />
  }
  if (!d.enabled) {
    return (
      <CardShell label="B. 일일 손실 한도 (DLL)" desc="하루 최대 허용 손실">
        <div className="rounded-lg border border-dashed border-gray-700 bg-gray-900/40 px-3 py-6 text-center text-xs text-gray-500">
          미설정 — DLL이 비활성화되어 있습니다.
        </div>
      </CardShell>
    )
  }
  // 오늘 손실액 = todayRealizedPnl 이 음수일 때의 절대값
  const lossUsed =
    d.todayRealizedPnl !== null && d.todayRealizedPnl < 0 ? Math.abs(d.todayRealizedPnl) : 0
  const ratio = pct(lossUsed, d.limitAmount)
  const pnlPositive = (d.todayRealizedPnl ?? 0) >= 0
  return (
    <CardShell label="B. 일일 손실 한도 (DLL)" desc="하루 최대 허용 손실 — 초과 시 신규 BUY 차단">
      <Gauge ratio={ratio} caption={`손실 ${won(lossUsed)} / 한도 ${won(d.limitAmount)}`} />
      <div className="mt-3 space-y-1.5">
        <Row
          label="오늘 실현손익"
          value={
            <span className={pnlPositive ? 'text-emerald-400' : 'text-red-400'}>
              {d.todayRealizedPnl === null
                ? '—'
                : `${pnlPositive ? '+' : ''}${Math.round(d.todayRealizedPnl).toLocaleString('ko-KR')}원`}
            </span>
          }
          emphasis
        />
        <Row label="한도" value={won(d.limitAmount)} />
        <Row label="남은 손실 여유" value={won(d.remaining)} emphasis />
      </div>
    </CardShell>
  )
}

// ──────────────────────────────────────────────
// C. 동시 보유 종목
// ──────────────────────────────────────────────

function ConcurrentDots({ current, limit }: { current: number; limit: number }) {
  // 한도 과다 시 점이 너무 많아지지 않게 최대 20개까지만 점, 이후 텍스트
  const showDots = limit <= 20
  if (!showDots) return null
  return (
    <div className="mt-3 flex flex-wrap gap-1.5" aria-hidden="true">
      {Array.from({ length: limit }).map((_, i) => (
        <span
          key={i}
          className={[
            'h-2.5 w-2.5 rounded-full',
            i < current ? 'bg-blue-500' : 'bg-gray-700',
          ].join(' ')}
        />
      ))}
    </div>
  )
}

function ConcurrentCard({ d }: { d: RiskUsageConcurrent | null }) {
  if (!d || d.limit === null || d.current === null) {
    return <EmptySection label="C. 동시 보유 종목" desc="동시에 보유할 수 있는 종목 수" />
  }
  const ratio = pct(d.current, d.limit)
  return (
    <CardShell label="C. 동시 보유 종목" desc="동시에 보유할 수 있는 종목 수">
      <Gauge ratio={ratio} caption={`보유 ${d.current} / ${d.limit}종목`} />
      <ConcurrentDots current={d.current} limit={d.limit} />
      <div className="mt-3 space-y-1.5">
        <Row
          label="현재 보유"
          value={`${d.current} / ${d.limit}종목`}
          emphasis
        />
        <Row label="추가 가능" value={`${d.remaining ?? '—'}종목 더`} emphasis />
      </div>
    </CardShell>
  )
}

// ──────────────────────────────────────────────
// D. 거래 빈도 (전략당) — 콤보박스로 활성 전략 1개 선택해 표시
//    (전략 세로 전체 전개가 너무 길다는 피드백으로 콤보박스 환원)
// ──────────────────────────────────────────────

/**
 * 한 전략의 거래 빈도 행 — 일별·분당 두 게이지.
 * 선택된 전략에 대해서만 useRiskUsage(userId, strategyId) 독립 호출 (react-query 키 분리 캐싱).
 * 카운트는 strategyId 별로 집계되므로 전략당 표기가 실제로 맞다.
 * 전략명은 상위 콤보박스에서 보여주므로 행 내부에 별도 제목을 두지 않는다.
 */
function StrategyFrequencyRow({
  userId,
  strategyId,
}: {
  userId: number
  strategyId: number
}) {
  const { data } = useRiskUsage(userId, strategyId)
  const d: RiskUsageTradeFrequency | null = data?.tradeFrequency ?? null

  return (
    <div className="rounded-lg border border-gray-800 bg-gray-900/40 p-3">
      {!d || d.perDayLimit === null ? (
        <div className="rounded-md border border-dashed border-gray-700 bg-gray-900/40 px-3 py-4 text-center text-[11px] text-gray-500">
          데이터 없음 · 준비 중
        </div>
      ) : (
        <div className="space-y-3">
          <div>
            <Gauge
              ratio={pct(d.perDayUsed, d.perDayLimit)}
              caption={`오늘 ${d.perDayUsed ?? '—'} / ${d.perDayLimit}회`}
            />
            <div className="mt-1.5">
              <Row label="오늘 남은 거래" value={`${d.perDayRemaining ?? '—'}회`} emphasis />
            </div>
          </div>
          <div>
            <Gauge
              ratio={pct(d.perMinuteUsed, d.perMinuteLimit)}
              caption={`이번 분 ${d.perMinuteUsed ?? '—'} / ${d.perMinuteLimit ?? '—'}회`}
            />
          </div>
        </div>
      )}
    </div>
  )
}

function TradeFrequencyCard({ userId }: { userId: number }) {
  const { data: strategies, isLoading } = useStrategies(userId)

  // ACTIVE(enabled) 전략만 후보 — id 는 string 이므로 number 로 환산
  const activeStrategies = useMemo(
    () =>
      (strategies ?? [])
        .filter((s) => s.enabled)
        .map((s) => ({ id: Number(s.id), name: s.nameKo || s.name }))
        .filter((s) => Number.isFinite(s.id)),
    [strategies],
  )

  // ⚠️ 선택 state 는 이 카드 내부 로컬에만 둔다. 상위 RiskUsageCards 의
  //    useRiskUsage(userId) 인자에 strategyId 를 넣지 않으므로 선택 변경이
  //    전체 섹션 refetch/리렌더로 번지지 않는다 → 스크롤 점프 회귀 방지 (피드백 #4).
  const [selectedStrategyId, setSelectedStrategyId] = useState<number | null>(null)

  // 활성 목록이 바뀌면 선택 보정: 미선택이거나 선택이 더 이상 활성이 아니면 첫 번째로.
  const selected = useMemo(() => {
    if (activeStrategies.length === 0) return null
    const found = activeStrategies.find((s) => s.id === selectedStrategyId)
    return found ?? activeStrategies[0]
  }, [activeStrategies, selectedStrategyId])

  return (
    <CardShell label="D. 거래 빈도 (전략당)" desc="활성 전략별 분당·일별 주문 사용량">
      {isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 2 }).map((_, i) => (
            <div key={i} className="h-28 animate-pulse rounded-lg bg-gray-800" />
          ))}
        </div>
      ) : activeStrategies.length === 0 || !selected ? (
        <div className="rounded-lg border border-dashed border-gray-700 bg-gray-900/40 px-3 py-6 text-center text-xs text-gray-500">
          활성 전략 없음 — 전략을 활성화하면 거래 빈도가 표시됩니다.
        </div>
      ) : (
        <div className="space-y-3">
          <select
            aria-label="거래 빈도를 볼 활성 전략 선택"
            value={selected.id}
            onChange={(e) => setSelectedStrategyId(Number(e.target.value))}
            className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-1.5 text-sm text-white focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
          >
            {activeStrategies.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
          <StrategyFrequencyRow
            key={selected.id}
            userId={userId}
            strategyId={selected.id}
          />
        </div>
      )}
    </CardShell>
  )
}

// ──────────────────────────────────────────────
// 본체
// ──────────────────────────────────────────────

interface RiskUsageCardsProps {
  userId: number
}

export function RiskUsageCards({ userId }: RiskUsageCardsProps) {
  // A/B/C 카드는 strategyId 무관 단일 호출. D(거래 빈도)는 전략별로 자체 호출하므로
  // 여기서 strategyId state 를 두지 않는다 → 셀렉트 변경에 의한 스크롤 점프 제거 (피드백 #4).
  const { data, isLoading, isError, isFetching, dataUpdatedAt } = useRiskUsage(userId)

  const updatedLabel = useMemo(() => {
    if (!dataUpdatedAt) return null
    return new Date(dataUpdatedAt).toLocaleTimeString('ko-KR', { hour12: false })
  }, [dataUpdatedAt])

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h2 className="text-lg font-bold text-white">한도 사용 현황</h2>
          <p className="mt-0.5 text-xs text-gray-500">
            10초마다 자동 갱신 · 설정 저장 시 즉시 반영
          </p>
        </div>
        <span className="text-[11px] text-gray-600 tabular-nums">
          {isFetching ? '갱신 중…' : updatedLabel ? `갱신 ${updatedLabel}` : ''}
        </span>
      </div>

      {isLoading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-40 animate-pulse rounded-xl bg-gray-800" />
          ))}
        </div>
      ) : isError ? (
        <div className="rounded-xl border border-red-800 bg-red-900/20 p-6 text-center text-sm text-red-400">
          한도 사용량을 불러오지 못했습니다. 잠시 후 다시 시도됩니다.
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <PositionCard d={data?.position ?? null} />
          <DailyLossCard d={data?.dailyLoss ?? null} />
          <ConcurrentCard d={data?.concurrent ?? null} />
          <TradeFrequencyCard userId={userId} />
        </div>
      )}
    </section>
  )
}
