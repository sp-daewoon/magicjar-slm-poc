/**
 * SymbolDetailPage — /symbol/:code 종목 상세 페이지
 *
 * spec §6-1 라우트 LOCK: `/symbol/:code`
 * ADR 0040 §2-4: 현재가·메타·차트·호가 4 영역
 *
 * 구현 사항:
 * 1. useParams → code 추출
 * 2. useSymbol(code) → 메타·현재가 초기 스냅샷
 * 3. STOMP /topic/market.tick.{code} → 현재가 실시간 갱신
 * 4. STOMP /topic/market.candle.minute.{code} → 차트 1m 실시간 append
 * 5. STOMP /topic/market.orderbook.{code} → 호가 실시간 갱신 (수동매매 보조 — 호가 클릭 → 지정가 시드)
 * 6. useEffect mount → POST /api/symbols/{code}/realtime/subscribe (현재가+호가 slot 통보)
 * 7. useEffect cleanup(unmount·종목전환) → POST /api/symbols/{code}/realtime/unsubscribe + sendBeacon fallback
 *    "표시 중에만" 라이프사이클 — 상세 화면 떠 있는 동안만 호가 구독, 이탈 즉시 해제
 *    (최고 관리자 결재 2026-06-12 anchor detail-orderbook-ui-restore — 수동매매 시 호가 없이 주문 risk 차단)
 *
 * graceful 처리 (spec §10·§12):
 * - HTTP 503/404 → SymbolError
 * - cold-start 미완 (candle 0건) → SymbolChart "데이터 수집 중" skeleton
 * - 장 마감 → stale 배지 (SymbolPriceCard)
 * - STOMP 끊김·tick 지연 → 30s HTTP polling fallback
 * - instrument 테이블 부재 → code 그대로 표시 (SymbolHeader graceful)
 * - 호가 slot 가득(queued) → "polling fallback 중" 배지 (SymbolOrderbook)
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useLocation, useParams, useSearchParams } from 'react-router-dom'
import { useAccountPositions } from '../../api/hooks/useAccountPositions'
import { useInstrument } from '../../api/hooks/useInstrument'
import { usePreferredAccountType } from '../../api/hooks/usePreferredAccountType'
import { useRuntimeFlags } from '../../api/hooks/useRuntimeFlags'
import {
  SYMBOL_FALLBACK_POLLING_INTERVAL_MS,
  useSymbol,
} from '../../api/hooks/useSymbol'
import { useStompConnectionState } from '../../api/hooks/useStompConnectionState'
import { useCandleHealth } from '../../api/hooks/useCandleHealth'
import {
  symbolApi,
  type CandleInterval,
  type CandleMinutePayload,
  type OrderbookPayload,
  type TickPayload,
} from '../../api/symbol'
import { CandleHealthBadge } from './CandleHealthBadge'
import { DisclosurePanel } from '../../features/disclosure/components/DisclosurePanel'
import { NewsPanel } from '../../features/news/components/NewsPanel'
import { AiExplainEntry } from '../../features/ai/components/AiExplainEntry'
import {
  buildDaytradeSignalAiContext,
  type DaytradeSignalNavigationState,
} from '../../lib/daytrade/signalInterpretation'
import { currentKrxSession } from '../../lib/marketHours'
import { useStompTopic } from '../../lib/stomp'
import { marketTick, marketCandleMinute, marketOrderbook } from '../../api/stompDestinations'
import { useOnboardingStore } from '../../stores/onboarding'
import { SymbolChart } from './SymbolChart'
import { SymbolError } from './SymbolError'
import { SymbolHeader } from './SymbolHeader'
import { SymbolMetaCard } from './SymbolMetaCard'
import { SymbolOrderbook } from './SymbolOrderbook'
import { SymbolOrderTicket } from './SymbolOrderTicket'
import { SymbolPriceCard } from './SymbolPriceCard'

const SYMBOL_TICK_STALE_MS = 30_000
const SYMBOL_STATUS_CLOCK_MS = 5_000

// ──────────────────────────────────────────────
// 유틸
// ──────────────────────────────────────────────

function isBEError(status: number | undefined): boolean {
  return status === 503 || status === 404 || status === 400
}

// ──────────────────────────────────────────────
// SymbolDetailPage
// ──────────────────────────────────────────────

export function SymbolDetailPage() {
  const { code = '' } = useParams<{ code: string }>()
  const [searchParams] = useSearchParams()
  const location = useLocation()
  const queryClient = useQueryClient()
  const { data: instrument, isLoading: isInstrumentLoading } = useInstrument(code)
  const isDelisted = instrument?.active === false
  // W4 R2 AI 진입점 — userId 1일 토큰 한도 키
  const userId = useOnboardingStore((s) => s.registeredUserId)

  // STOMP 실시간 상태
  const [realtimeTick, setRealtimeTick] = useState<TickPayload | null>(null)
  const [realtimeCandle, setRealtimeCandle] = useState<CandleMinutePayload | null>(null)
  const [orderbook, setOrderbook] = useState<OrderbookPayload | null>(null)
  const [orderbookSlot, setOrderbookSlot] = useState<'live' | 'queued' | null>(null)
  const [selectedOrderPrice, setSelectedOrderPrice] = useState('')
  const [chartInterval, setChartInterval] = useState<CandleInterval>('1d')
  const [lastTickAt, setLastTickAt] = useState<number | null>(null)
  const [watchStartedAt, setWatchStartedAt] = useState(() => Date.now())
  const [nowMs, setNowMs] = useState(() => Date.now())
  const candlesRefetchedAfterFirstTickRef = useRef(false)
  const stompState = useStompConnectionState({
    enabled: Boolean(code) && !isDelisted,
    enableToast: false,
  })
  const stompConnected = stompState === 'CONNECTED'
  const krxSession = currentKrxSession(new Date(nowMs))
  const isTradingSession = krxSession !== 'CLOSED'
  const tickAgeMs = lastTickAt === null ? null : Math.max(0, nowMs - lastTickAt)
  const noTickAgeMs = Math.max(0, nowMs - watchStartedAt)
  const tickStale =
    isTradingSession &&
    stompConnected &&
    (tickAgeMs === null ? noTickAgeMs > SYMBOL_TICK_STALE_MS : tickAgeMs > SYMBOL_TICK_STALE_MS)
  const symbolPollingEnabled =
    Boolean(code) && !isDelisted && isTradingSession && (!stompConnected || tickStale)
  const { accountType, setAccountType, isReady: accountTypeReady } = usePreferredAccountType()
  const { data: runtimeFlags, isLoading: isRuntimeFlagsLoading } = useRuntimeFlags(userId)
  const {
    data: accountHoldings,
    isLoading: isAccountHoldingsLoading,
    isError: isAccountHoldingsError,
  } = useAccountPositions({
    userId,
    accountType,
    enabled: accountTypeReady && userId !== null && userId !== undefined && !isDelisted,
  })
  const accountHolding = useMemo(
    () => accountHoldings?.find((holding) => holding.code === code) ?? null,
    [accountHoldings, code],
  )
  const riskPlanPreview = useMemo(() => {
    const stopLossPct = Number(searchParams.get('stopLossPct'))
    const takeProfitPct = Number(searchParams.get('takeProfitPct'))
    if (!Number.isFinite(stopLossPct) || !Number.isFinite(takeProfitPct)) return null
    if (stopLossPct <= 0 || takeProfitPct <= 0) return null
    return {
      strategyId: searchParams.get('strategyId') ?? undefined,
      stopLossPct,
      takeProfitPct,
    }
  }, [searchParams])
  const daytradeSignal = useMemo(() => {
    const state = location.state as DaytradeSignalNavigationState | null
    const signal = state?.daytradeSignal
    if (!signal || signal.code !== code) return null
    return signal
  }, [code, location.state])

  // 상장폐지 종목은 가격 조회 의미 없음 — useSymbol 비활성으로 polling 차단.
  // 정상 실시간 수신 중에는 HTTP polling 0건, STOMP 장애·tick 지연 시에만 30s fallback.
  const { data: symbol, isLoading: isSymbolLoading, isError, error } = useSymbol(code, {
    enabled: !isDelisted,
    pollingEnabled: symbolPollingEnabled,
  })
  const isLoading = isInstrumentLoading || (!isDelisted && isSymbolLoading)

  // 캔들 수집 건강도 (readiness P1) — 상세 진입 시 1회 + 60s 폴링.
  // 상장폐지 종목은 캔들 수집 의미 없으므로 비활성.
  const {
    data: candleHealth,
    isError: isCandleHealthError,
    isLoading: isCandleHealthLoading,
  } = useCandleHealth(code, { enabled: Boolean(code) && !isDelisted })

  // 네트워크가 아닌 화면 상태용 clock: tick stale·장 상태 badge 계산에만 사용.
  useEffect(() => {
    const id = window.setInterval(() => {
      setNowMs(Date.now())
    }, SYMBOL_STATUS_CLOCK_MS)
    return () => clearInterval(id)
  }, [])

  // 종목 전환 시 이전 종목의 실시간 payload가 UI에 남지 않도록 즉시 초기화.
  useEffect(() => {
    setRealtimeTick(null)
    setRealtimeCandle(null)
    setOrderbook(null)
    setOrderbookSlot(null)
    setSelectedOrderPrice('')
    setLastTickAt(null)
    candlesRefetchedAfterFirstTickRef.current = false
    const resetAt = Date.now()
    setWatchStartedAt(resetAt)
    setNowMs(resetAt)
  }, [code])

  // STOMP topic 구독 (3개 — tick·candle.minute·orderbook).
  // 상장폐지 종목은 실시간 데이터가 없으므로 구독 자체 차단.
  const stompEnabled = Boolean(code) && !isDelisted

  useStompTopic<TickPayload>(
    marketTick(code),
    useCallback((payload) => {
      if (payload.code && payload.code !== code) return
      const receivedAt = Date.now()
      setRealtimeTick(payload)
      setLastTickAt(receivedAt)
      setNowMs(receivedAt)
      if (!candlesRefetchedAfterFirstTickRef.current) {
        candlesRefetchedAfterFirstTickRef.current = true
        void queryClient.invalidateQueries({ queryKey: ['symbolCandles', code], exact: false })
      }
    }, [code, queryClient]),
    stompEnabled,
  )

  useStompTopic<CandleMinutePayload>(
    marketCandleMinute(code),
    useCallback((payload) => {
      if (payload.code && payload.code !== code) return
      setRealtimeCandle(payload)
    }, [code]),
    stompEnabled,
  )

  useStompTopic<OrderbookPayload>(
    marketOrderbook(code),
    useCallback((payload) => {
      if (payload.code && payload.code !== code) return
      setOrderbook(payload)
    }, [code]),
    stompEnabled,
  )

  // ──────────────────────────────────────────────
  // 종목 상세 실시간 라이프사이클 (현재가+호가, watchlist 독립)
  // ──────────────────────────────────────────────
  const subscribeCalledRef = useRef(false)

  useEffect(() => {
    // 상장폐지 종목은 실시간 slot 점유 안 함.
    if (!code || isDelisted || subscribeCalledRef.current) return
    subscribeCalledRef.current = true

    // mount → POST subscribe (200ms 딜레이 — spec §5-2 throttle 정합)
    const timer = setTimeout(() => {
      symbolApi
        .subscribeRealtime(code)
        .then((res) => {
          setOrderbookSlot(res.orderbookSlot)
        })
        .catch(() => {
          // 구독 실패 — graceful (backend 미완 시 무시)
        })
    }, 200)

    return () => {
      clearTimeout(timer)
      // cleanup: POST unsubscribe
      // sendBeacon fallback (브라우저 탭 닫기 시에도 발동)
      // Content-Type: application/json blob 전달 — 일부 서버가 text/plain을 reject하는 경우 대비
      const unsubUrl = `/api/symbols/${encodeURIComponent(code)}/realtime/unsubscribe`
      const beaconData = new Blob([JSON.stringify({ code })], { type: 'application/json' })
      try {
        navigator.sendBeacon(unsubUrl, beaconData)
      } catch {
        // sendBeacon 미지원 환경 — REST fallback
        symbolApi.unsubscribeRealtime(code).catch(() => {
          // cleanup 실패 — backend 5분 idle timeout으로 자동 회수
        })
      }
      subscribeCalledRef.current = false
    }
  }, [code, isDelisted])

  // ──────────────────────────────────────────────
  // HTTP 에러 분류
  // ──────────────────────────────────────────────
  const httpStatus =
    isError && error && 'response' in (error as { response?: { status?: number } })
      ? (error as { response?: { status?: number } }).response?.status
      : undefined

  const fallbackSeconds = SYMBOL_FALLBACK_POLLING_INTERVAL_MS / 1000
  const realtimeStatusMessage = !isTradingSession
    ? null
    : !stompConnected
      ? `실시간 연결 재시도 중... HTTP ${fallbackSeconds}초 fallback 활성`
      : tickStale
        ? `실시간 체결 수신 지연... HTTP ${fallbackSeconds}초 fallback 활성`
        : null

  // ──────────────────────────────────────────────
  // 렌더링
  // ──────────────────────────────────────────────

  // 로딩 중
  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-950">
        <div className="flex flex-col items-center gap-4">
          <div className="h-10 w-10 animate-spin rounded-full border-4 border-gray-800 border-t-blue-500" />
          <p className="text-sm text-gray-500">종목 정보 로딩 중...</p>
        </div>
      </div>
    )
  }

  // 상장폐지 종목 (instrument.active=false) — 가격·차트·호가 진입 차단 + 안내
  if (isDelisted) {
    return (
      <div className="min-h-screen bg-gray-950 px-4 py-8">
        <SymbolError
          code={code}
          variant="delisted"
          delistedAt={instrument?.delistedAt ?? null}
        />
      </div>
    )
  }

  // 데이터 부재 에러 (404·503 등)
  if (isError && isBEError(httpStatus)) {
    return (
      <div className="min-h-screen bg-gray-950 px-4 py-8">
        <SymbolError code={code} message="종목 데이터를 가져올 수 없습니다." />
      </div>
    )
  }

  // symbol 없음 (예상치 못한 경우)
  if (!symbol) {
    return (
      <div className="min-h-screen bg-gray-950 px-4 py-8">
        <SymbolError code={code} />
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gray-950 px-4 py-6">
      <div className="mx-auto max-w-7xl space-y-4">
        {/* ── 헤더: 종목명·코드·시장·산업 + 복귀 버튼 ── */}
        <SymbolHeader symbol={symbol} />

        {/* ── W4 R2 AI 진입점: SYMBOL (AI 종목 분석) ── */}
        <div>
          <AiExplainEntry
            type="SYMBOL"
            userId={userId}
            context={{
              code,
              name: instrument?.name ?? symbol.code,
              currentPrice: realtimeTick?.price ?? symbol.currentPrice ?? null,
              market: instrument?.market ?? 'UNKNOWN',
              sector: instrument?.sector ?? null,
              industryMedium: instrument?.industryMedium ?? null,
              indicators: {
                previousClose: symbol.previousClose ?? null,
                change: realtimeTick?.priceChange ?? symbol.change ?? null,
                changeRate: realtimeTick?.changeRate ?? symbol.changeRate ?? null,
                volume: symbol.volume ?? null,
                marketCap: symbol.marketCap ?? null,
                per: symbol.per ?? null,
                pbr: symbol.pbr ?? null,
              },
              patterns: daytradeSignal
                ? [
                    daytradeSignal.summary,
                    ...daytradeSignal.reasonComments.slice(0, 2),
                  ]
                : [],
              news: [],
            }}
          />
        </div>

        {daytradeSignal && (
          <div className="rounded-lg border border-purple-900/50 bg-gray-900/50 p-3">
            <div className="mb-2">
              <p className="text-xs font-semibold text-purple-200">단타 발굴 신호</p>
              <p className="mt-1 text-xs leading-relaxed text-gray-400">
                {daytradeSignal.summary}
              </p>
            </div>
            <AiExplainEntry
              type="SIGNAL"
              userId={userId}
              context={buildDaytradeSignalAiContext(daytradeSignal)}
              disabledMessage="KIS 계좌 설정 후 단타 신호 AI 해석을 사용할 수 있습니다."
            />
          </div>
        )}

        {/* ── STOMP 연결 상태 배지 ── */}
        {realtimeStatusMessage && (
          <div
            role="status"
            aria-live="polite"
            className="flex items-center gap-2 rounded-lg border border-yellow-800/50 bg-yellow-900/20 px-3 py-2 text-xs text-yellow-400"
          >
            <svg className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z" />
            </svg>
            {realtimeStatusMessage}
          </div>
        )}

        {/* ── 시장 스냅샷: 차트를 가로로 압박하지 않도록 상단 보조 정보로 배치 ── */}
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
          <SymbolPriceCard symbol={symbol} realtimeTick={realtimeTick} />
          <SymbolMetaCard symbol={symbol} />
        </div>

        {/* ── 차트: 종목상세의 주 작업면이므로 전폭 사용 ── */}
        <div className="space-y-3">
          {/* W4 R2 AI 진입점: CHART (차트 해석) */}
          <div>
            <AiExplainEntry
              type="CHART"
              userId={userId}
              context={{
                code,
                name: instrument?.name ?? symbol.code,
                timeframe: chartInterval,
                currentPrice: realtimeTick?.price ?? symbol.currentPrice ?? null,
                changeRate: realtimeTick?.changeRate ?? symbol.changeRate ?? null,
                source: symbol.source,
              }}
            />
          </div>
          {/* 캔들 수집 건강도 1줄 (readiness P1) — stale 시 사유, 정상 시 작은 점 */}
          <CandleHealthBadge
            data={candleHealth}
            isError={isCandleHealthError}
            isLoading={isCandleHealthLoading}
          />
          <SymbolChart
            code={code}
            symbolName={instrument?.name ?? symbol.name ?? code}
            userId={userId}
            realtimeCandle={realtimeCandle}
            riskPlanPreview={riskPlanPreview}
            onIntervalChange={setChartInterval}
            liveTick={
              realtimeTick
                ? {
                    price: realtimeTick.price,
                    priceChange: realtimeTick.priceChange,
                    changeRate: realtimeTick.changeRate,
                    changeSign: realtimeTick.changeSign,
                    ts: realtimeTick.ts,
                  }
                : null
            }
          />
        </div>

        {/* ── 호가창 + 종목 주문 ── */}
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_24rem]">
          <SymbolOrderbook
            code={code}
            orderbook={orderbook}
            orderbookSlot={orderbookSlot}
            isStompConnected={stompConnected}
            selectedPrice={selectedOrderPrice}
            onSelectPrice={(price) => setSelectedOrderPrice(price)}
          />
          <SymbolOrderTicket
            code={code}
            name={instrument?.name ?? symbol.name ?? code}
            currentPrice={realtimeTick?.price ?? symbol.currentPrice ?? null}
            userId={userId}
            accountType={accountType}
            accountTypeReady={accountTypeReady}
            onAccountTypeChange={setAccountType}
            holding={accountHolding}
            isHoldingLoading={isAccountHoldingsLoading}
            isHoldingError={isAccountHoldingsError}
            orderMode={runtimeFlags?.mode ?? null}
            isRuntimeFlagsLoading={isRuntimeFlagsLoading}
            allowReal={runtimeFlags?.allowReal ?? false}
            hasConsent={runtimeFlags?.userConsented ?? false}
            killSwitchOn={runtimeFlags?.killSwitchEnabled ?? false}
            selectedLimitPrice={selectedOrderPrice}
            onLimitPriceChange={setSelectedOrderPrice}
          />
        </div>

        {/* ── 뉴스·공시 sidebar (ADR 0075 Phase 3 — 60s polling STOMP 0 LOCK) ── */}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <NewsPanel code={code} />
          <DisclosurePanel code={code} />
        </div>
      </div>
    </div>
  )
}
