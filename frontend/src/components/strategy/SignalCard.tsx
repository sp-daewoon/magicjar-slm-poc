/**
 * SignalCard — 시그널 단건 표시 컴포넌트 (T-SIG Phase 3 — ADR 0066)
 *
 * 표시 항목: ts·code·interval·side·price·rule·strategyId 링크
 * 실제 데이터만 표시 (feedback_no_mock_data 정합)
 *
 * W4 R2 (2026-05-24): SIGNAL AI 진입점 추가 — AiExplainEntry "이 시그널 해석"
 * (4번째 진입점 / 5 중 SYMBOL·CHART·STRATEGY·SIGNAL·MARKET).
 *
 * ADR 0115 §2-B 불변식 #4 (2026-06-10): 키 사슬 추적 드릴다운 — 행 확장 시 SignalTracePanel
 * (사인→주문→체결→보유+정합)을 lazy fetch로 렌더.
 */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { type SignalDto, type SignalSide } from '../../api/signal'
import { AiExplainEntry } from '../../features/ai/components/AiExplainEntry'
import { useOnboardingStore } from '../../stores/onboarding'
import { SignalTracePanel } from './SignalTracePanel'

// ──────────────────────────────────────────────
// 스타일 헬퍼
// ──────────────────────────────────────────────

const SIDE_BADGE: Record<SignalSide, { label: string; cls: string }> = {
  BUY:  { label: '매수', cls: 'bg-emerald-900/80 text-emerald-300 border border-emerald-700' },
  SELL: { label: '매도', cls: 'bg-red-900/80 text-red-300 border border-red-700' },
  HOLD: { label: '홀드', cls: 'bg-gray-700/80 text-gray-300 border border-gray-600' },
}

/** ISO 8601 → KST 포맷 (YYYY.MM.DD HH:mm) */
function formatTs(iso: string): string {
  try {
    const d = new Date(iso)
    return d.toLocaleString('ko-KR', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    })
  } catch {
    return iso
  }
}

/** 가격 원화 포맷 */
function formatPrice(priceStr: string): string {
  if (!priceStr) return '-'
  const num = Number(priceStr.replace(/,/g, ''))
  if (isNaN(num)) return priceStr
  return `${num.toLocaleString('ko-KR')}원`
}

// ──────────────────────────────────────────────
// 컴포넌트
// ──────────────────────────────────────────────

interface SignalCardProps {
  signal: SignalDto
  /** 새 시그널 하이라이트 (prepend 시 잠깐 표시) */
  isNew?: boolean
}

export function SignalCard({ signal, isNew = false }: SignalCardProps) {
  const badge = SIDE_BADGE[signal.side] ?? SIDE_BADGE.HOLD
  // W4 R2 AI 진입점 — userId 1일 토큰 한도 키
  const userId = useOnboardingStore((s) => s.registeredUserId)
  // ADR 0115 §2-B 키 사슬 추적 드릴다운 (불변식 #4) — 확장 시에만 lazy fetch
  const [traceOpen, setTraceOpen] = useState(false)
  const instrumentLabel = signal.codeName ? (
    <>
      {signal.codeName}
      <span className="font-mono text-xs font-normal text-gray-400">({signal.code})</span>
    </>
  ) : (
    signal.code
  )

  return (
    <article
      className={[
        'flex items-start gap-3 rounded-lg border border-gray-800 bg-gray-900 px-4 py-3 transition-colors',
        isNew ? 'border-blue-700 bg-blue-950/30' : '',
      ].join(' ')}
    >
      {/* Side 배지 */}
      <span
        className={`mt-0.5 shrink-0 rounded px-2 py-0.5 text-xs font-bold ${badge.cls}`}
      >
        {badge.label}
      </span>

      {/* 메인 정보 */}
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
          {/* 종목 */}
          <Link
            to={`/symbol/${signal.code}`}
            className="text-sm font-semibold text-white hover:text-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900"
          >
            {instrumentLabel}
          </Link>

          {/* 인터벌 */}
          <span className="rounded bg-gray-700 px-1.5 py-0.5 text-xs text-gray-300">
            {signal.interval}
          </span>

          {/* 가격 */}
          <span className="text-sm font-medium text-white">
            {formatPrice(signal.price)}
          </span>
        </div>

        {/* 규칙 */}
        {signal.rule && (
          <p className="mt-1 truncate text-xs text-gray-400" title={signal.rule}>
            {signal.rule}
          </p>
        )}

        {/* 하단 메타 */}
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-gray-500">
          <time dateTime={signal.ts}>{formatTs(signal.ts)}</time>
          {(signal.strategyNameKo || signal.strategyName) && (
            <Link
              to={`/strategy/list`}
              className="text-gray-400 hover:text-gray-200 focus:outline-none"
            >
              전략: {signal.strategyNameKo || signal.strategyName}
            </Link>
          )}
        </div>

        {/* 발행 전략 조건 — 이 시그널이 어떤 매수/매도 조건으로 나왔는지 */}
        {(signal.buyCondition || signal.sellCondition) && (
          <div className="mt-1.5 flex flex-col gap-1 rounded-md bg-gray-800/40 px-2.5 py-1.5">
            {signal.buyCondition && (
              <p className="truncate font-mono text-xs text-gray-300" title={signal.buyCondition}>
                <span className="text-emerald-400">매수</span> {signal.buyCondition}
              </p>
            )}
            {signal.sellCondition && (
              <p className="truncate font-mono text-xs text-gray-300" title={signal.sellCondition}>
                <span className="text-orange-300">매도</span> {signal.sellCondition}
              </p>
            )}
          </div>
        )}

        {/* W4 R2 AI 진입점: SIGNAL (이 시그널 해석) + 키 사슬 추적 토글 (ADR 0115 §2-B 불변식 #4) */}
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <AiExplainEntry
            type="SIGNAL"
            userId={userId}
            context={{
              signalId: signal.id,
              code: signal.code,
              codeName: signal.codeName ?? signal.code,
              interval: signal.interval,
              side: signal.side,
              price: signal.price,
              rule: signal.rule ?? '',
              strategyName: signal.strategyName ?? null,
              ts: signal.ts,
            }}
          />
          <button
            type="button"
            onClick={() => setTraceOpen((v) => !v)}
            aria-expanded={traceOpen}
            aria-label="키 사슬 추적 (사인→주문→체결→보유)"
            className="inline-flex items-center gap-1 rounded-md border border-gray-700 bg-gray-800 px-2.5 py-1 text-xs font-medium text-gray-300 transition-colors hover:bg-gray-700 hover:text-white focus:outline-none focus:ring-2 focus:ring-blue-400"
            title="이 사인이 낸 주문·체결·현재 전략 보유와 정합을 추적합니다"
          >
            <span aria-hidden>{traceOpen ? '▾' : '▸'}</span>
            키 사슬 추적
          </button>
        </div>

        {/* 드릴다운 패널 — 확장 시에만 lazy fetch (userId 있을 때만) */}
        {traceOpen && userId != null && (
          <SignalTracePanel signalId={signal.id} userId={userId} enabled={traceOpen} />
        )}
        {traceOpen && userId == null && (
          <p className="mt-2 rounded-md border border-dashed border-gray-700 bg-gray-900/40 px-3 py-2 text-center text-xs text-gray-500">
            키 사슬 추적은 KIS 계좌 등록(userId) 후 사용할 수 있습니다.
          </p>
        )}
      </div>
    </article>
  )
}
