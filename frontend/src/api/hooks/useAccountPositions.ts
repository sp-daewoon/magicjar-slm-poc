/**
 * useAccountPositions — KIS 실시간 보유 종목 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * GET /api/account/positions?userId=&accountType=
 * KIS inquire-balance output1 — 보유 종목별 현재가·평가금·손익 (실시간 KIS 시세).
 *
 * ⚠ 주의: 기존 `usePositions` (../api/hooks/usePositions)는 DB `position` 테이블 조회.
 *   본 hook은 KIS 실시간 응답 — 두 데이터 출처가 다름. (spec §2-3 evidence)
 *
 * - STOMP push (useStompAccountBalance) 가 primary — 체결·취소·거절 시 즉시 invalidate.
 * - fallback polling 은 STOMP 끊김 시에만 (60s). STOMP CONNECTED 면 폴링 0.
 * - userId 없으면 쿼리 비활성
 *
 * 폴링 정리 (2026-06-08 — KIS_TPS_EXCEEDED burst 근본 정리, 사용자 dogfooding):
 *   STOMP 연결 시 폴링 off (useSymbol pollingEnabled 패턴), 끊김 시에만 60s fallback.
 *   refetchInterval prop 명시 시 STOMP 게이트 무시 강제 override (기존 시그니처 보존).
 *
 * C-3 polling → push 전환 (2026-05-20): refetchInterval 5s → 30s.
 *   STOMP /topic/account.balance.{userId} 가 주문체결·취소·거절 시 즉시 invalidate 트리거.
 *
 * A-3 (2026-05-20): refetchIntervalInBackground=false 명시화 (background tab 시 polling pause).
 */
import { useQuery } from '@tanstack/react-query'
import {
  accountApi,
  type AccountHoldingDto,
  type AccountType,
} from '../account'
import {
  ACCOUNT_STALE_TIME_MS,
  resolveAccountRefetchInterval,
  useAccountStompConnected,
} from './useAccountPollingGate'

interface UseAccountPositionsOptions {
  /** ADR 0089 Phase 2 — userId number */
  userId: number | null | undefined
  accountType?: AccountType
  /**
   * 폴링 주기 ms 강제 override. 미지정 시 STOMP 게이트 적용:
   *   STOMP CONNECTED → 폴링 off, 끊김 시 60s fallback.
   */
  refetchInterval?: number
  enabled?: boolean
}

export function useAccountPositions({
  userId,
  accountType = 'REAL',
  refetchInterval,
  enabled = true,
}: UseAccountPositionsOptions) {
  const queryEnabled = enabled && userId !== null && userId !== undefined
  const stompConnected = useAccountStompConnected(queryEnabled)
  const effectiveRefetchInterval =
    refetchInterval !== undefined
      ? refetchInterval
      : resolveAccountRefetchInterval(stompConnected)

  return useQuery<AccountHoldingDto[], Error>({
    queryKey: ['account', 'positions', userId, accountType],
    queryFn: () => accountApi.getPositions(userId!, accountType),
    enabled: queryEnabled,
    staleTime: ACCOUNT_STALE_TIME_MS,
    refetchInterval: effectiveRefetchInterval,
    refetchIntervalInBackground: false, // A-3: background tab 시 pause (v5 default 명시화)
    refetchOnWindowFocus: true,
  })
}
