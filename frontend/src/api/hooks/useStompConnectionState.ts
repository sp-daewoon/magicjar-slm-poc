/**
 * useStompConnectionState — STOMP 클라이언트 연결 상태 추적 hook
 *
 * 목적:
 *  - 헤더 indicator(WsConnectionIndicator)가 매 연결 상태 변화 시점에 자동 갱신
 *  - 연결 끊김 발생 시 toast로 사용자에게 즉시 통지 (CRITICAL — WS는 자동매매 본체 신호 채널)
 *
 * 상태 enum:
 *  - 'CONNECTED'    — STOMP CONNECT frame 수신 후 stable
 *  - 'DISCONNECTED' — onDisconnect 또는 onWebSocketClose 트리거
 *  - 'CONNECTING'   — DISCONNECTED 직후 자동 재연결 진행 중 (reconnectDelay 5s 후 재시도)
 *
 * 콜백 체인 보존:
 *  - useStompReconnect.ts:241-261 패턴과 1:1 정합
 *  - const previous = client.onXxx; client.onXxx = (...args) => { previous?.(...args); ... }
 *  - hook unmount 시 chain 복원 — 다른 hook(useStompReconnect 등)의 후킹 영향 0
 *
 * Toast 정책 (Task 3):
 *  - 첫 mount 초기 CONNECTING → toast 0건 (사용자 인지 방해 회피)
 *  - CONNECTED → DISCONNECTED 전환 시 warning toast 1회
 *  - DISCONNECTED → CONNECTED 복귀 시 success toast 1회 ("WS 재연결 완료")
 *  - useStompReconnect의 "[누락] N건" backfill toast는 별개 책임 — 본 hook은 단순 연결 상태 안내
 *
 * 룰 5: LLM provider import 0건
 */
import { useEffect, useRef, useState } from 'react'
import { getStompClient } from '../../lib/stomp'
import { useOptionalToast } from '../../components/ui/ToastProvider'

export type StompConnectionState = 'CONNECTED' | 'DISCONNECTED' | 'CONNECTING'

export interface UseStompConnectionStateOptions {
  /** false 시 hook 비활성 (테스트·storybook 용) */
  enabled?: boolean
  /** toast 표시 여부 — false면 indicator 상태만 반영하고 toast 호출 skip (단위 테스트 격리 용) */
  enableToast?: boolean
}

/**
 * STOMP 연결 상태 추적.
 *
 * 사용 예 (WsConnectionIndicator 등 헤더 컴포넌트에서 1회 mount):
 *   const state = useStompConnectionState()
 *   // 'CONNECTED' | 'DISCONNECTED' | 'CONNECTING'
 *
 * 동작:
 *  1. mount 시 client.connected 읽어 초기 상태 결정
 *     - true  → CONNECTED
 *     - false → CONNECTING (자동 재연결 진입 가정)
 *  2. client.onConnect chain 후킹 → CONNECTED 전환
 *  3. client.onDisconnect chain 후킹 → DISCONNECTED 전환 + warning toast
 *  4. client.onWebSocketClose chain 후킹 → DISCONNECTED + 후속 CONNECTING (reconnectDelay 후 재시도)
 *  5. unmount 시 모든 chain 복원
 */
export function useStompConnectionState(
  options: UseStompConnectionStateOptions = {},
): StompConnectionState {
  const { enabled = true, enableToast = true } = options
  const { showToast } = useOptionalToast()
  const [state, setState] = useState<StompConnectionState>(() => {
    if (typeof window === 'undefined') return 'CONNECTING'
    try {
      const client = getStompClient()
      return client.connected ? 'CONNECTED' : 'CONNECTING'
    } catch {
      return 'CONNECTING'
    }
  })

  // 이전 상태 추적 (전환 detection — 첫 mount CONNECTING toast skip)
  const previousStateRef = useRef<StompConnectionState>(state)

  useEffect(() => {
    if (!enabled) return

    const client = getStompClient()

    // 콜백 체인 보존 — useStompReconnect 패턴 1:1
    const previousOnConnect = client.onConnect
    const previousOnDisconnect = client.onDisconnect
    const previousOnWebSocketClose = client.onWebSocketClose
    const previousOnStompError = client.onStompError

    client.onConnect = (frame) => {
      if (previousOnConnect) {
        try {
          previousOnConnect.call(client, frame)
        } catch {
          // 이전 callback 실패는 상태 추적 진행에 영향 없음
        }
      }
      setState((prev) => {
        if (enableToast && prev === 'DISCONNECTED') {
          // DISCONNECTED → CONNECTED 복귀: 재연결 안내
          showToast('WS 재연결 완료', 'success')
        }
        previousStateRef.current = 'CONNECTED'
        return 'CONNECTED'
      })
    }

    client.onDisconnect = (frame) => {
      if (previousOnDisconnect) {
        try {
          previousOnDisconnect.call(client, frame)
        } catch {
          // 이전 callback 실패 무시
        }
      }
      setState((prev) => {
        if (enableToast && prev === 'CONNECTED') {
          showToast('WS 연결이 끊어졌습니다 — 재연결 시도 중...', 'warning')
        }
        previousStateRef.current = 'DISCONNECTED'
        return 'DISCONNECTED'
      })
    }

    client.onWebSocketClose = (event) => {
      if (previousOnWebSocketClose) {
        try {
          previousOnWebSocketClose.call(client, event)
        } catch {
          // 이전 callback 실패 무시
        }
      }
      setState((prev) => {
        if (enableToast && prev === 'CONNECTED') {
          showToast('WS 연결이 끊어졌습니다 — 재연결 시도 중...', 'warning')
        }
        previousStateRef.current = 'DISCONNECTED'
        return 'DISCONNECTED'
      })
    }

    client.onStompError = (frame) => {
      if (previousOnStompError) {
        try {
          previousOnStompError.call(client, frame)
        } catch {
          // 이전 callback 실패 무시
        }
      }
      // STOMP 프로토콜 에러 — 연결은 살아 있을 수 있으나 사용자 인지 필요
      // DISCONNECTED 전환은 onWebSocketClose가 담당하므로 여기서는 상태 변경 X
      // (헤더 indicator는 onDisconnect/onWebSocketClose 기반)
    }

    return () => {
      // chain 복원 — 다른 hook 후킹 영향 0
      client.onConnect = previousOnConnect
      client.onDisconnect = previousOnDisconnect
      client.onWebSocketClose = previousOnWebSocketClose
      client.onStompError = previousOnStompError
    }
  }, [enabled, enableToast, showToast])

  return state
}
