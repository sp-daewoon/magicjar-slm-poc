/**
 * AiDiscoveryCard — AI 종목발굴 후보 1건 카드.
 *
 * 캔들 0건(matched && candleCount === 0) 후보에는 "캔들 가져오기" 버튼을 노출한다.
 * 클릭하면 기존 비동기 재수집 잡(useCandleRecollectJob)을 발굴 interval 기준으로 실행한다.
 * (캔들 0건이라 삭제는 no-op이므로 사실상 "가져오기"로 동작 — 합산 interval은 1m 수집+재생성 내장)
 *
 * 다중 후보 카드 각각이 자체 STOMP 구독을 가지도록 카드를 컴포넌트로 분리했다.
 * 전체 가져오기(순차 실행)는 page가 activeFetchCode 하나만 지정 → 해당 카드가 자동 시작 →
 * terminal 시 onFetchSettled로 page에 알려 다음 후보로 진행한다.
 */
import { useCallback, useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { useCandleRecollectJob } from '../../api/hooks/useCandleRefresh'
import type { CandleRecollectOptions } from '../../api/symbol'
import type {
  StrategyResearchAiDiscoveryItemDto,
  StrategyResearchAiDiscoveryItemStatus,
} from '../../api/strategyResearch'

const MIN_RESEARCH_CANDLES = 40

export interface AiDiscoveryCardProps {
  item: StrategyResearchAiDiscoveryItemDto
  /** 발굴 요청에 사용한 interval (candleCount 계산 기준과 동일) */
  interval: string
  /** 발굴 기간 기준 재수집 범위 옵션. 없으면 full 재수집 방지를 위해 가져오기를 막는다. */
  recollectOptions: CandleRecollectOptions | null
  /** 전체 가져오기에서 page가 현재 처리 대상으로 지정한 code (자동 시작 트리거) */
  activeFetchCode: string | null
  statusPending: boolean
  onStatusChange: (
    item: StrategyResearchAiDiscoveryItemDto,
    status: StrategyResearchAiDiscoveryItemStatus,
  ) => void
  onSelect: (item: StrategyResearchAiDiscoveryItemDto) => void
  /** 캔들 가져오기 성공 시 page가 DB 기준 가용 여부를 재조회하도록 알린다. */
  onCandlesFetched: (code: string, inserted: number) => void
  /** 잡 terminal(SUCCEEDED/FAILED) 시 page 순차 큐 진행용 */
  onFetchSettled: (code: string, result: { ok: boolean; inserted: number; error?: string }) => void
}

function aiDiscoveryStatusLabel(status: StrategyResearchAiDiscoveryItemStatus): string {
  if (status === 'KEPT') return '유지'
  if (status === 'DISMISSED') return '제외'
  return '신규'
}

export function AiDiscoveryCard({
  item,
  interval,
  recollectOptions,
  activeFetchCode,
  statusPending,
  onStatusChange,
  onSelect,
  onCandlesFetched,
  onFetchSettled,
}: AiDiscoveryCardProps) {
  const code = item.code
  const hasResearchCandles =
    typeof item.hasResearchCandles === 'boolean'
      ? item.hasResearchCandles
      : item.candleCount == null
        ? null
        : item.candleCount >= MIN_RESEARCH_CANDLES
  // backend가 가용 여부를 내려줄 때만 리서치 불가 판정 가능 (add-only graceful)
  const lacksResearchCandles = hasResearchCandles === false
  const hasSomeCandles = (item.candleCount ?? 0) > 0
  const canFetch = Boolean(code) && item.matched && lacksResearchCandles && Boolean(recollectOptions)
  const settledRef = useRef(false)

  const {
    recollect,
    isPending: isFetching,
    progress,
  } = useCandleRecollectJob({
    code: code ?? '',
    intervals: [interval],
    recollectOptions: recollectOptions ?? undefined,
    onSuccess: (_deleted, inserted) => {
      if (code) onCandlesFetched(code, inserted)
      if (code && !settledRef.current) {
        settledRef.current = true
        onFetchSettled(code, { ok: true, inserted })
      }
    },
    onError: (msg) => {
      if (code && !settledRef.current) {
        settledRef.current = true
        onFetchSettled(code, { ok: false, inserted: 0, error: msg })
      }
    },
  })

  const startFetch = useCallback(() => {
    if (!canFetch || isFetching) return
    settledRef.current = false
    recollect()
  }, [canFetch, isFetching, recollect])

  // 전체 가져오기: page가 이 카드를 활성으로 지정하면 자동 시작
  useEffect(() => {
    if (!activeFetchCode || !code || activeFetchCode !== code) return
    if (isFetching) return
    if (canFetch) {
      settledRef.current = false
      recollect()
    } else {
      // 활성 대상이지만 더 이상 가져올 게 없음(이미 캔들 있음/비매칭) → 큐 진행만 시킨다
      onFetchSettled(code, { ok: true, inserted: 0 })
    }
    // recollect는 hook 안에서 안정적, code/active/canFetch 변화 시에만 평가
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeFetchCode, code, canFetch])

  const activeProgress =
    progress && (progress.status === 'QUEUED' || progress.status === 'RUNNING') ? progress : null
  const progressPercent =
    activeProgress?.processedDays != null &&
    activeProgress.totalDays != null &&
    activeProgress.totalDays > 0
      ? Math.min(100, Math.round((activeProgress.processedDays / activeProgress.totalDays) * 100))
      : null
  const failed = progress?.status === 'FAILED'
  const succeededEmpty = progress?.status === 'SUCCEEDED' && progress.insertedCount + progress.rebuiltCount === 0

  const candleBadge = (() => {
    if (hasResearchCandles !== false) return null
    if (hasSomeCandles) {
      return (
        <span className="rounded border border-amber-800 px-2 py-0.5 text-xs text-amber-300">캔들 부족</span>
      )
    }
    return (
      <span className="rounded border border-gray-700 px-2 py-0.5 text-xs text-gray-400">캔들 없음</span>
    )
  })()

  return (
    <div className="rounded border border-gray-800 bg-gray-950 p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-sm font-semibold text-gray-100">{item.name}</div>
          <div className="mt-1 flex flex-wrap gap-1 text-xs text-gray-500">
            {item.code && <span className="font-mono text-blue-300">{item.code}</span>}
            {item.market && <span>{item.market}</span>}
            {item.sector && <span>{item.sector}</span>}
          </div>
        </div>
        <div className="flex flex-col items-end gap-1">
          {!item.matched && (
            <span className="rounded border border-amber-800 px-2 py-0.5 text-xs text-amber-300">확인필요</span>
          )}
          {candleBadge}
          <span
            className={[
              'rounded border px-2 py-0.5 text-xs',
              item.status === 'KEPT' ? 'border-blue-800 text-blue-300' : 'border-gray-800 text-gray-500',
            ].join(' ')}
          >
            {aiDiscoveryStatusLabel(item.status)}
          </span>
        </div>
      </div>
      <p className="mt-3 text-sm leading-6 text-gray-300">{item.aiReason}</p>
      {item.themes.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1">
          {item.themes.map((theme) => (
            <span key={theme} className="rounded border border-gray-800 px-2 py-0.5 text-xs text-gray-400">
              {theme}
            </span>
          ))}
        </div>
      )}

      {canFetch && (
        <div className="mt-3">
          {isFetching ? (
            <div
              className="rounded border border-amber-900 bg-amber-950/20 px-3 py-2"
              role="status"
              aria-live="polite"
            >
              <div className="flex items-center justify-between gap-2 text-xs">
                <span className="font-medium text-amber-100">
                  캔들 수집 중{activeProgress?.stage ? ` · ${activeProgress.stage}` : ''}
                </span>
                {progressPercent !== null && (
                  <span className="shrink-0 font-mono text-amber-300">{progressPercent}%</span>
                )}
              </div>
              <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-gray-800">
                <div
                  className="h-full rounded-full bg-amber-400 transition-all"
                  style={{ width: `${progressPercent ?? 18}%` }}
                />
              </div>
            </div>
          ) : (
            <button
              type="button"
              onClick={startFetch}
              className="w-full rounded border border-amber-700 px-2.5 py-1.5 text-xs font-semibold text-amber-200 hover:bg-amber-950/30"
            >
              {failed ? '캔들 가져오기 재시도' : '캔들 가져오기'}
            </button>
          )}
          {failed && (
            <p className="mt-1 text-xs text-red-300">
              수집 실패: {progress?.error ?? progress?.message ?? '알 수 없는 오류'}
            </p>
          )}
          {succeededEmpty && (
            <p className="mt-1 text-xs text-gray-400">수집했지만 해당 구간 캔들이 없습니다.</p>
          )}
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-gray-500">
          신뢰 {Math.round(item.confidence * 100)}%
          {(item.tradingHalt || item.managementStock) && ' · 가드 확인'}
        </span>
        <div className="flex flex-wrap justify-end gap-2">
          <button
            type="button"
            onClick={() => onStatusChange(item, item.status === 'KEPT' ? 'NEW' : 'KEPT')}
            disabled={statusPending}
            className={[
              'rounded border px-2.5 py-1 text-xs disabled:cursor-not-allowed disabled:opacity-50',
              item.status === 'KEPT'
                ? 'border-blue-700 text-blue-300 hover:bg-blue-950/40'
                : 'border-gray-700 text-gray-300 hover:bg-gray-800',
            ].join(' ')}
          >
            {item.status === 'KEPT' ? '유지 해제' : '유지'}
          </button>
          <button
            type="button"
            onClick={() => onStatusChange(item, 'DISMISSED')}
            disabled={statusPending}
            className="rounded border border-red-900 px-2.5 py-1 text-xs text-red-300 hover:bg-red-950/30 disabled:cursor-not-allowed disabled:opacity-50"
          >
            제외
          </button>
          {item.code && (
            <Link
              to={`/symbol/${item.code}`}
              className="rounded border border-gray-700 px-2.5 py-1 text-xs text-gray-300 hover:bg-gray-800"
            >
              차트
            </Link>
          )}
          <button
            type="button"
            onClick={() => onSelect(item)}
            disabled={!item.code || lacksResearchCandles}
            className="rounded bg-blue-600 px-2.5 py-1 text-xs font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            선택
          </button>
        </div>
      </div>
    </div>
  )
}
