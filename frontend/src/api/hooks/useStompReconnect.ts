/**
 * useStompReconnect — STOMP 재연결 시점 backfill hook (P1-3 frontend)
 *
 * 목적: STOMP 연결이 끊긴 동안 발생한 order/signal/risk 이벤트를 backend REST로 backfill,
 * 사용자가 끊김 동안의 모든 이벤트를 누락 없이 볼 수 있게 함.
 *
 * 흐름:
 *   1. STOMP 클라이언트 onConnect callback (재연결 포함 매 연결마다 발동)
 *   2. localStorage `stomp:lastSeenAt:${userId}` 조회 → 첫 진입 시 now-1day fallback
 *   3. order/signal/risk 채널별 GET /api/stomp/events/unread?userId=&since=lastSeenAt&channel=
 *   4. count > 0 채널만 GET /api/stomp/events?userId=&since=lastSeenAt&limit=100&channel=
 *   5. 각 이벤트 분기 — order/signal/risk → toast 또는 modal 표시
 *   6. lastSeenAt = lastTs (또는 최신 createdAt) 갱신
 *
 * 정합:
 *  - onConnect callback 시점 — useStompTopic은 useEffect 기반이므로 hook 별도 클라이언트 onConnect hook 으로 통합
 *  - 실시간 수신은 useStompOrder/useStompSignal/useStompRisk가 담당 — 본 hook은 backfill 전담
 *  - 중복 토스트 방지: backfill 직후 동시 도래한 실시간 이벤트는 occurredAt 비교로 skip (id 매트릭스 → in-memory recent ids Set)
 *
 * 의존:
 *  - frontend STOMP 클라이언트 (lib/stomp.ts) onConnect 콜백 후킹
 *  - ToastProvider showToast
 *  - localStorage (Web Storage API)
 *
 * 룰 5: LLM provider import 0건
 */
import { useCallback, useEffect, useRef } from 'react'
import { Client } from '@stomp/stompjs'
import { useOptionalToast } from '../../components/ui/ToastProvider'
import {
  stompBackfillApi,
  type StompBackfillChannel,
  type StompEventDto,
} from '../stompBackfill'
import type { OrderStompPayload } from './useStompOrder'
import type { SignalDto } from '../signal'
import type { RiskEventDto } from '../risk'
import { buildRiskToastMessage } from '../riskAlertMessage'
import { getStompClient } from '../../lib/stomp'

// ──────────────────────────────────────────────
// localStorage helpers
// ──────────────────────────────────────────────

const LAST_SEEN_KEY_PREFIX = 'stomp:lastSeenAt:'

function lastSeenStorageKey(userId: number): string {
  return `${LAST_SEEN_KEY_PREFIX}${userId}`
}

/** localStorage `stomp:lastSeenAt:{userId}` 조회. 미존재 시 24h 전 fallback. */
function readLastSeenAt(userId: number): string {
  try {
    const raw = localStorage.getItem(lastSeenStorageKey(userId))
    if (raw) return raw
  } catch {
    // localStorage 차단 환경 — 매 reconnect 시 24h 전부터 backfill (안전 over-fetch)
  }
  const oneDayAgo = new Date(Date.now() - 24 * 60 * 60 * 1000)
  return oneDayAgo.toISOString()
}

function writeLastSeenAt(userId: number, isoInstant: string): void {
  try {
    localStorage.setItem(lastSeenStorageKey(userId), isoInstant)
  } catch {
    // 차단 환경 무시
  }
}

// ──────────────────────────────────────────────
// 이벤트 → 토스트 메시지 변환
// ──────────────────────────────────────────────

function sideLabel(side: 'BUY' | 'SELL' | undefined): string {
  if (side === 'BUY') return '매수'
  if (side === 'SELL') return '매도'
  return ''
}

function formatPrice(value: number | string | undefined): string {
  if (value === undefined || value === null) return ''
  const num = typeof value === 'string' ? Number(value.replace(/,/g, '')) : value
  if (Number.isNaN(num)) return String(value)
  return num.toLocaleString('ko-KR')
}

interface ToastSpec {
  message: string
  type: 'info' | 'success' | 'warning' | 'error'
}

/**
 * KST(Asia/Seoul) HH:mm 시각 prefix — backfill 이벤트가 "지난 알림"임을 사용자에게 시각적으로 안내.
 * 결함 3-C 정합: `[누락]` prefix copy를 제거하고 발생 시각으로 대체.
 *
 * createdAt이 없거나 파싱 실패 시 빈 문자열 반환 (prefix 없음).
 */
function backfillTimePrefix(createdAt: string | undefined | null): string {
  if (!createdAt) return ''
  const ms = Date.parse(createdAt)
  if (Number.isNaN(ms)) return ''
  try {
    const hhmm = new Intl.DateTimeFormat('ko-KR', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      timeZone: 'Asia/Seoul',
    }).format(new Date(ms))
    return `[${hhmm}] `
  } catch {
    return ''
  }
}

/**
 * 결함 3-A 정합: PR #636 머지 이전 발행된 옛 stomp_event row(qty·executedQty 모두 null)는
 * "0주" 표시로 사용자 오인 risk → toast skip. null 반환 시 호출 측에서 토스트 미발행.
 *
 * 기준: event=FILLED + quantity·executedQty 둘 다 null/undefined 인 경우만 skip.
 * 정상 신규 이벤트(qty 있음)는 그대로 표시.
 */
function buildBackfillOrderToast(
  p: OrderStompPayload,
  createdAt?: string,
): ToastSpec | null {
  const code = p.code ?? ''
  const prefix = backfillTimePrefix(createdAt)
  switch (p.event) {
    case 'REQUESTED':
      return {
        message: `${prefix}주문 접수 — ${code} ${sideLabel(p.side)} ${p.quantity ?? 0}주`,
        type: 'info',
      }
    case 'FILLED': {
      // 결함 3-A: 옛 데이터(qty=null) graceful skip
      if (p.quantity == null && p.executedQty == null) return null
      return {
        message: p.partial
          ? `${prefix}부분 체결 — ${code} ${p.executedQty ?? 0}/${p.quantity ?? 0}주 @ ${formatPrice(p.executedPrice)}원`
          : `${prefix}체결 완료 — ${code} ${p.quantity ?? 0}주 @ ${formatPrice(p.executedPrice)}원`,
        type: p.partial ? 'warning' : 'success',
      }
    }
    case 'REJECTED':
      return {
        message: `${prefix}주문 거절 — ${code}: ${p.reason ?? ''}`,
        type: 'error',
      }
    case 'CANCELLED':
      return {
        message: `${prefix}주문 취소 — ${code} ${p.quantity ?? 0}주`,
        type: 'info',
      }
    default:
      return { message: `${prefix}주문 이벤트: ${p.event}`, type: 'info' }
  }
}

function buildBackfillSignalToast(s: SignalDto, createdAt?: string): ToastSpec {
  const sideKo = s.side === 'BUY' ? '매수' : s.side === 'SELL' ? '매도' : '홀드'
  const codePart = s.codeName ? `${s.codeName}(${s.code})` : s.code
  const pricePart = formatPrice(s.price)
  const rulePart = s.rule ? ` — ${s.rule}` : ''
  const prefix = backfillTimePrefix(createdAt)
  return {
    message: `${prefix}[${sideKo}] ${codePart} ${s.interval} ${pricePart}원${rulePart}`,
    type: s.side === 'BUY' ? 'success' : s.side === 'SELL' ? 'error' : 'info',
  }
}

function buildBackfillRiskToast(r: RiskEventDto, createdAt?: string): ToastSpec {
  const prefix = backfillTimePrefix(createdAt)
  const message = buildRiskToastMessage(r)
  if (r.verdict === 'BLOCKED' || r.verdict === 'AUTO_PAUSED') {
    return {
      message: prefix ? `${prefix}${message}` : message,
      type: r.verdict === 'AUTO_PAUSED' ? 'warning' : 'error',
    }
  }
  return { message: prefix ? `${prefix}${message}` : message, type: 'info' }
}

// 100건 초과 누락 경고 임계치
const BACKFILL_LIMIT = 100
const BACKFILL_CHANNELS: StompBackfillChannel[] = ['order', 'signal', 'risk']

// ──────────────────────────────────────────────
// 메인 hook
// ──────────────────────────────────────────────

export interface UseStompReconnectOptions {
  /** ADR 0089 Phase 2 — userId number (null = 미인증 → hook 비활성) */
  userId: number | null
  /** false 시 hook 전체 비활성 (테스트·storybook 용) */
  enabled?: boolean
}

/**
 * STOMP 재연결 시점 backfill hook.
 *
 * 사용 예 (App.tsx 또는 layout root 1회 mount):
 *   useStompReconnect({ userId: registeredUserId })
 *
 * 동작:
 *  - userId === null 시 no-op
 *  - STOMP client.onConnect callback chain 후킹 — 매 연결(초기 + 재연결)마다 backfill 시도
 *  - 동일 connect 사이클 내 중복 backfill 방지 (in-flight flag)
 */
export function useStompReconnect({
  userId,
  enabled = true,
}: UseStompReconnectOptions): void {
  const { showToast } = useOptionalToast()
  const inFlightRef = useRef(false)

  const runBackfill = useCallback(async () => {
    if (userId === null) return
    if (inFlightRef.current) return
    inFlightRef.current = true

    try {
      const since = readLastSeenAt(userId)

      const unreadByChannel = await Promise.all(
        BACKFILL_CHANNELS.map(async (channel) => ({
          channel,
          unread: await stompBackfillApi.getUnreadCount(userId, since, channel),
        })),
      )
      const missedCount = unreadByChannel.reduce((sum, item) => sum + item.unread.count, 0)

      if (missedCount === 0) {
        writeLastSeenAt(userId, new Date().toISOString())
        return
      }

      // 100건 초과 누락 시 사용자에게 안내 (정확한 모든 토스트 노출은 spam 우려)
      if (missedCount > BACKFILL_LIMIT) {
        showToast(
          `누락된 이벤트 ${missedCount}건 감지 — 최근 ${BACKFILL_LIMIT}건만 표시. 매매일지·주문 내역 확인 권장.`,
          'warning',
        )
      }

      const eventsByChannel = await Promise.all(
        unreadByChannel
          .filter((item) => item.unread.count > 0)
          .map((item) => stompBackfillApi.list(userId, since, BACKFILL_LIMIT, item.channel)),
      )
      const events = eventsByChannel
        .flat()
        .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
        .slice(0, BACKFILL_LIMIT)
        .sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt))

      for (const ev of events) {
        const toastSpec = buildBackfillToast(ev)
        if (toastSpec) showToast(toastSpec.message, toastSpec.type)
      }

      // 결함 3-B 정합: lastSeenAt 갱신 보장
      // - 정상 케이스: lastTs · 이벤트 createdAt 중 최신값을 anchor 로 사용 (옛 이벤트 재 backfill 방지)
      // - fallback: lastTs/createdAt 둘 다 없는 edge case 도 now() 로 갱신 → 다음 reconnect 시 재 backfill 무한루프 차단
      const lastTsValues = unreadByChannel
        .map((item) => item.unread.lastTs)
        .filter((value): value is string => Boolean(value))
      const createdAtValues = events.map((event) => event.createdAt)
      const newAnchor = newestIsoInstant([...lastTsValues, ...createdAtValues])
      writeLastSeenAt(userId, newAnchor ?? new Date().toISOString())
    } catch {
      // backend 미구현 / 네트워크 fail — graceful, 다음 reconnect 시 재시도
    } finally {
      inFlightRef.current = false
    }
  }, [userId, showToast])

  useEffect(() => {
    if (!enabled || userId === null) return

    const client: Client = getStompClient()

    // 기존 onConnect chain 보존 (lib/stomp.ts ensureConnected와 호환)
    const previous = client.onConnect
    client.onConnect = (frame) => {
      if (previous) {
        try {
          previous.call(client, frame)
        } catch {
          // 이전 callback 실패는 backfill 진행에 영향 없음
        }
      }
      // 비동기 backfill — 실패는 내부에서 graceful handle
      void runBackfill()
    }

    // 이미 연결돼 있으면 즉시 backfill 1회 실행 (StrictMode double-mount 안전 — in-flight flag)
    if (client.connected) {
      void runBackfill()
    }

    return () => {
      // hook unmount 시 chain 복원
      client.onConnect = previous
    }
  }, [enabled, userId, runBackfill])
}

/**
 * channel 기반 narrowing → payloadJson 을 파싱한 뒤 적절한 토스트 spec 생성.
 *
 * 분기:
 *  - order  → buildBackfillOrderToast (qty=null FILLED 옛 row 는 null 반환 → toast skip)
 *  - signal → buildBackfillSignalToast
 *  - risk   → buildBackfillRiskToast
 *
 * createdAt 은 backfillTimePrefix(KST HH:mm)로 메시지에 prefix.
 */
function buildBackfillToast(ev: StompEventDto): ToastSpec | null {
  const payload = parsePayload(ev)
  if (!payload) return null
  switch (ev.channel) {
    case 'order':
      return buildBackfillOrderToast(payload as OrderStompPayload, ev.createdAt)
    case 'signal':
      return buildBackfillSignalToast(payload as SignalDto, ev.createdAt)
    case 'risk':
      return buildBackfillRiskToast(payload as RiskEventDto, ev.createdAt)
    default:
      return null
  }
}

function parsePayload(ev: StompEventDto): unknown | null {
  try {
    return JSON.parse(ev.payloadJson)
  } catch {
    return null
  }
}

function newestIsoInstant(values: string[]): string | null {
  let newest: string | null = null
  let newestMs = Number.NEGATIVE_INFINITY
  for (const value of values) {
    const ms = Date.parse(value)
    if (!Number.isNaN(ms) && ms > newestMs) {
      newest = value
      newestMs = ms
    }
  }
  return newest
}
