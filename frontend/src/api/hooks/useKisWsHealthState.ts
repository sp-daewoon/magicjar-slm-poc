/**
 * useKisWsHealthState — consumer↔KIS WS 헬스 상태 추적 hook (사용자 dogfooding 2026-05-20)
 *
 * 배경:
 *  - magicJar는 2개 WS connection 존재:
 *    #1 frontend↔api STOMP (Spring Messaging) → useStompConnectionState 추적
 *    #2 consumer↔KIS WS (시세 H0UNCNT0 + 호가 H0UNASP0 + 체결통보 H0STCNI0/9) → 본 hook 추적
 *  - 사용자 dogfooding: consumer 임의 종료 시 헤더 indicator는 🟢인 채(STOMP만 추적), KIS WS 끊김 미인지
 *  - 해결: 헤더에 2 indicator 분리 — STOMP + KIS WS 각각 + 각각 toast (어디가 끊겼는지 명확)
 *
 * 상태 enum:
 *  - 'CONNECTED'    — 최근 SystemEvent action ∈ {WS_RECONNECTED, WS_CONNECTED} (actor=KisWebSocketBridge)
 *  - 'DISCONNECTED' — 최근 SystemEvent action = WS_DISCONNECTED (actor=KisWebSocketBridge)
 *  - 'UNKNOWN'      — STOMP 자체 끊김 시 (SystemEvent 도착 불가) 또는 초기 데이터 도착 전
 *
 * 데이터 path:
 *  - REST: useSystemEventList (30s polling fallback) — actor 필터는 backend 미지원이므로
 *    category=WEBSOCKET 조회 후 client 측 actor=KisWebSocketBridge 필터
 *  - STOMP push: useSystemEventStompSubscription (`AppHeader` 전역 mount)이 메시지 수신 시
 *    queryClient.invalidateQueries({queryKey:['system-event']}) 호출 → useSystemEventList 자동 refetch
 *  - latest 이벤트(occurredAt 최대값) 의 action으로 상태 판단
 *
 * Toast 정책 (Task 3 사용자 명세):
 *  - 첫 mount 초기 상태 → toast 0 (사용자 인지 방해 회피)
 *  - {CONNECTED|UNKNOWN} → DISCONNECTED 전환 시 warning toast 1회
 *  - DISCONNECTED → CONNECTED 복귀 시 success toast 1회
 *
 * STOMP UNKNOWN 의미:
 *  - STOMP가 끊긴 상태에서는 backend가 SystemEvent를 발사해도 frontend는 알 수 없음
 *  - "?" 표시 + UNKNOWN 명시 — 사용자에게 "KIS WS 상태 불명" 정직 전달 (mock 합성 0건 정책 정합)
 *
 * 룰 5: LLM provider import 0건 (W4 한정)
 */
import { useEffect, useMemo, useRef } from 'react'
import { useStompConnectionState } from './useStompConnectionState'
import { useSystemEventList } from './useSystemEventList'
import { useOptionalToast } from '../../components/ui/ToastProvider'
import type { SystemEvent } from '../system-event'

export type KisWsHealthState = 'CONNECTED' | 'DISCONNECTED' | 'UNKNOWN'

export interface UseKisWsHealthStateOptions {
  /** toast 표시 여부 — false면 indicator 상태만 반영하고 toast skip (단위 테스트 격리 용) */
  enableToast?: boolean
}

/** SystemEvent actor — backend consumer/.../KisWebSocketBridge.kt SYSTEM_EVENT_ACTOR 정합 */
const KIS_WS_ACTOR = 'KisWebSocketBridge'

/** CONNECTED 판정 액션 — WS_RECONNECTED + WS_CONNECTED (현재 backend는 RECONNECTED만 emit, future-proof) */
const CONNECTED_ACTIONS = new Set(['WS_RECONNECTED', 'WS_CONNECTED'])

/** DISCONNECTED 판정 액션 */
const DISCONNECTED_ACTION = 'WS_DISCONNECTED'

/**
 * KIS WS 이벤트만 필터 후 latest action으로 상태 판단.
 *
 * 순수 함수 — 테스트 격리·재사용 위해 export.
 *
 * @param events SystemEvent 목록 (category=WEBSOCKET filter 이미 적용된 상태 기대)
 * @returns 'CONNECTED' | 'DISCONNECTED' | 'UNKNOWN' (이벤트 0건 또는 KIS WS 외 actor만 있을 때)
 */
export function decideKisWsStateFromEvents(events: readonly SystemEvent[]): KisWsHealthState {
  if (!events.length) return 'UNKNOWN'

  // actor=KisWebSocketBridge만 필터 (backend는 actor 필터 미지원 → client 측에서 처리)
  const kisEvents = events.filter((e) => e.actor === KIS_WS_ACTOR)
  if (!kisEvents.length) return 'UNKNOWN'

  // occurredAt 최대값 (ISO-8601 문자열 lexical 비교 = chronological 비교)
  const latest = kisEvents.reduce((max, cur) =>
    cur.occurredAt > max.occurredAt ? cur : max,
  )

  if (CONNECTED_ACTIONS.has(latest.action)) return 'CONNECTED'
  if (latest.action === DISCONNECTED_ACTION) return 'DISCONNECTED'
  return 'UNKNOWN'
}

/**
 * KIS WS 헬스 상태 추적.
 *
 * 사용 예 (KisWsHealthIndicator 헤더 컴포넌트에서 1회 mount):
 *   const state = useKisWsHealthState()
 *   // 'CONNECTED' | 'DISCONNECTED' | 'UNKNOWN'
 *
 * 동작:
 *  1. useStompConnectionState 의존 — STOMP DISCONNECTED 시 UNKNOWN 강제 (backend 도착 불가)
 *  2. useSystemEventList({ category: 'WEBSOCKET' }) — REST 30s polling + STOMP invalidate 자동 반영
 *  3. event list에서 actor=KisWebSocketBridge 필터 → latest action으로 상태 판단
 *  4. CONNECTED→DISCONNECTED 전환 시 warning toast / DISCONNECTED→CONNECTED 시 success toast
 *
 * 의존성: AppHeader에서 useSystemEventStompSubscription도 mount되어 있어야 STOMP push 즉시 반영됨
 *   (그렇지 않으면 30s polling 으로만 갱신)
 */
export function useKisWsHealthState(
  options: UseKisWsHealthStateOptions = {},
): KisWsHealthState {
  const { enableToast = true } = options
  const { showToast } = useOptionalToast()

  // #1 STOMP 자체 상태 — DISCONNECTED 시 KIS WS 상태는 UNKNOWN (push 도착 불가)
  const stompState = useStompConnectionState()

  // #2 SystemEvent 목록 — category=WEBSOCKET (backend 필터 미지원이므로 전체 fetch 후 client filter)
  //    page=0 size=20 (최근 20건이면 KIS WS 최신 상태 판정 충분 — 평소 0~수건/day)
  //    userId 미지정 — KisWebSocketBridge 이벤트는 userId=null broadcast이거나 user-specific
  //    (현재 backend emitWsEvent는 broadcast로 발사 — actor=KisWebSocketBridge 통합 채널)
  const eventListQuery = useSystemEventList({ page: 0, size: 20 })

  // events array 안정화 — reference 동일성 유지 (toast effect dep 안정)
  const items = eventListQuery.data?.items ?? []

  // 1차: SystemEvent 기반 상태 판정
  const eventBasedState = useMemo<KisWsHealthState>(
    () => decideKisWsStateFromEvents(items),
    [items],
  )

  // 2차: STOMP DISCONNECTED 시 UNKNOWN 강제 + 데이터 미도착(loading) 시 UNKNOWN
  const state: KisWsHealthState = useMemo(() => {
    if (stompState === 'DISCONNECTED') return 'UNKNOWN'
    // 아직 첫 fetch 미완 → UNKNOWN (mock 합성 금지 정책 정합: 명확히 "?"로 표시)
    if (eventListQuery.isLoading && !eventListQuery.data) return 'UNKNOWN'
    return eventBasedState
  }, [stompState, eventBasedState, eventListQuery.isLoading, eventListQuery.data])

  // 이전 상태 추적 (전환 detection — 첫 mount toast skip)
  const previousStateRef = useRef<KisWsHealthState | null>(null)

  useEffect(() => {
    const previous = previousStateRef.current

    // 첫 mount (previous=null): toast 0 — 단순 상태 기록
    if (previous === null) {
      previousStateRef.current = state
      return
    }

    // 동일 상태 유지: skip
    if (previous === state) return

    if (enableToast) {
      // {CONNECTED|UNKNOWN} → DISCONNECTED: warning
      if (state === 'DISCONNECTED' && previous !== 'DISCONNECTED') {
        showToast('KIS WS 끊김 — 시세·호가·체결 영향 (재연결 시도 중)', 'warning')
      }
      // DISCONNECTED → CONNECTED: success
      if (state === 'CONNECTED' && previous === 'DISCONNECTED') {
        showToast('KIS WS 재연결 완료', 'success')
      }
    }

    previousStateRef.current = state
  }, [state, enableToast, showToast])

  return state
}
