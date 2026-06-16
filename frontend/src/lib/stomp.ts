/**
 * STOMP 클라이언트 싱글톤 + useStompTopic 커스텀 훅
 *
 * spec §6-4 LOCK:
 * - @stomp/stompjs Client 싱글톤 (re-render 시 재연결 방지)
 * - brokerURL: ws://localhost:8080/ws (dev 환경 — Vite proxy /ws)
 * - reconnectDelay: 5000ms
 * - onConnect 시 subscribe, cleanup 시 unsubscribe
 * - 연결 상태 인디케이터 (onConnect / onStompError / onDisconnect)
 *
 * STOMP topic 매트릭스 (Phase 2 새 명명규칙 — 권위: api/stompDestinations.ts 빌더):
 *   /topic/stomp.api.market.tick.{code}
 *   /topic/stomp.api.market.candle.1m.{code}
 *   /topic/stomp.api.market.orderbook.{code}
 */
import { Client } from '@stomp/stompjs'
import { useEffect, useRef } from 'react'

// ──────────────────────────────────────────────
// STOMP 클라이언트 싱글톤
// ──────────────────────────────────────────────

/** 브라우저 dev 환경: Vite proxy /ws → ws://localhost:8080/ws */
const WS_BROKER_URL =
  typeof window !== 'undefined'
    ? `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`
    : 'ws://localhost:8080/ws'

let sharedClient: Client | null = null
let connectPromise: Promise<void> | null = null

/** STOMP 싱글톤 client getter — useStompReconnect.ts에서 onConnect chain 후킹용 export */
export function getStompClient(): Client {
  if (!sharedClient) {
    sharedClient = new Client({
      brokerURL: WS_BROKER_URL,
      reconnectDelay: 5000,
      // 연결 로그 (개발 단계)
      debug: (msg: string) => {
        // DEV 환경에서만 STOMP 로그 출력 (Vite import.meta.env 타입 주석 우회)
        // eslint-disable-next-line no-console
        console.debug('[STOMP]', msg)
      },
    })
  }
  return sharedClient
}

function ensureConnected(): Promise<void> {
  const client = getStompClient()
  if (client.connected) {
    return Promise.resolve()
  }
  if (connectPromise) {
    return connectPromise
  }

  connectPromise = new Promise<void>((resolve, reject) => {
    const originalOnConnect = client.onConnect
    const originalOnStompError = client.onStompError

    client.onConnect = (frame) => {
      if (originalOnConnect) originalOnConnect.call(client, frame)
      resolve()
      connectPromise = null
    }
    client.onStompError = (frame) => {
      if (originalOnStompError) originalOnStompError.call(client, frame)
      reject(new Error(`STOMP 연결 오류: ${frame.headers['message']}`))
      connectPromise = null
    }

    if (!client.active) {
      client.activate()
    }
  })

  return connectPromise
}

// ──────────────────────────────────────────────
// useStompTopic 커스텀 훅
// ──────────────────────────────────────────────

/**
 * STOMP topic 구독 커스텀 훅
 *
 * @param topic - 구독할 topic (예: `/topic/stomp.api.market.tick.005930`)
 * @param onMessage - 메시지 수신 콜백
 * @param enabled - false이면 구독하지 않음 (조건부 구독용)
 *
 * 사용 예 (raw 문자열 대신 api/stompDestinations.ts 빌더 사용 권장):
 *   useStompTopic<TickPayload>(
 *     marketTick(code),
 *     (payload) => setCurrentPrice(payload.price),
 *     isStompEnabled
 *   )
 */
export function useStompTopic<T>(
  topic: string,
  onMessage: (payload: T) => void,
  enabled = true,
): void {
  const onMessageRef = useRef(onMessage)
  onMessageRef.current = onMessage

  useEffect(() => {
    if (!enabled || !topic) return

    let unsubscribeFn: (() => void) | null = null
    let cancelled = false

    ensureConnected()
      .then(() => {
        if (cancelled) return
        const client = getStompClient()
        if (!client.connected) return

        const sub = client.subscribe(topic, (stompMsg) => {
          try {
            const payload = JSON.parse(stompMsg.body) as T
            onMessageRef.current(payload)
          } catch {
            // JSON 파싱 실패 — 무시 (graceful)
          }
        })
        unsubscribeFn = () => sub.unsubscribe()
      })
      .catch(() => {
        // STOMP 연결 실패 — 화면별 fallback 정책(useSymbol pollingEnabled)이 처리한다.
      })

    return () => {
      cancelled = true
      unsubscribeFn?.()
    }
  }, [topic, enabled])
}

/**
 * STOMP 연결 상태 (진단용)
 * UI에서 연결 상태 배지 표시 시 사용
 */
export function isStompConnected(): boolean {
  return sharedClient?.connected ?? false
}
