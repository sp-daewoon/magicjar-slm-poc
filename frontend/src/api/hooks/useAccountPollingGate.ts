/**
 * useAccountPollingGate — 계좌 hook 공용 폴링 게이트 (KIS_TPS_EXCEEDED burst 정리)
 *
 * 배경 (2026-06-08 — 사용자 dogfooding "잔고 조회 폴링 정리"):
 *   useAccountSummary·useAccountPositions·useAccountBalance 3 hook 이 각각 다른 KIS
 *   엔드포인트(/summary·/positions·/balance)를 30s 마다 폴링 → Dashboard 마운트 시
 *   30초마다 KIS 3 호출 버스트. STOMP push(useStompAccountBalance) 가 primary 인데도
 *   폴링이 STOMP 연결 여부와 무관하게 계속 발사 → 전역 KIS 버킷 포화 → TPS burst.
 *
 * 정책 (useSymbol pollingEnabled 패턴 1:1):
 *   - STOMP CONNECTED → 폴링 0 (push 가 주문체결·취소·거절 시 즉시 invalidate 하므로 불필요).
 *   - STOMP DISCONNECTED/CONNECTING → fallback 폴링 ON (주기 60s — 30s 에서 완화).
 *   - 컴포넌트가 화면에서 빠지면 hook unmount → 폴링 자동 정지 (가시성 게이트는
 *     컴포넌트 마운트 = 표시 라는 기존 구조가 이미 보장. 안 보이는 화면에는 카드가
 *     마운트되지 않으므로 폴링도 없음).
 *
 * 반환:
 *   resolveAccountRefetchInterval(stompConnected, fallbackInterval)
 *     → STOMP 연결 시 false (폴링 off), 미연결 시 fallbackInterval(기본 60s).
 *
 * 룰 5: LLM provider import 0건.
 */
import { useStompConnectionState } from './useStompConnectionState'

/** fallback 폴링 주기 — 30s → 60s 완화 (STOMP 끊김 시에만 발동) */
export const ACCOUNT_FALLBACK_POLLING_INTERVAL_MS = 60_000

/** 잔고 staleTime — push 로 즉시 invalidate 되므로 길게 (fallback 주기 직전까지 fresh) */
export const ACCOUNT_STALE_TIME_MS = 55_000

/**
 * STOMP 연결 상태 → 유효 refetchInterval 결정.
 *
 * @param stompConnected STOMP CONNECTED 여부
 * @param fallbackInterval 미연결 시 폴링 주기 (기본 60s)
 * @returns STOMP 연결 시 false(폴링 off), 미연결 시 fallbackInterval
 */
export function resolveAccountRefetchInterval(
  stompConnected: boolean,
  fallbackInterval: number = ACCOUNT_FALLBACK_POLLING_INTERVAL_MS,
): number | false {
  return stompConnected ? false : fallbackInterval
}

/**
 * 계좌 hook 내부에서 STOMP 연결 여부를 silent 하게 추적.
 *
 * enableToast=false — 잔고 hook 은 toast 책임 없음 (WsConnectionIndicator·페이지 레벨이
 *   이미 연결 끊김 toast 표시 중. 중복 spam 방지). chain 보존이라 다중 mount 안전.
 */
export function useAccountStompConnected(enabled = true): boolean {
  const state = useStompConnectionState({ enabled, enableToast: false })
  return state === 'CONNECTED'
}
