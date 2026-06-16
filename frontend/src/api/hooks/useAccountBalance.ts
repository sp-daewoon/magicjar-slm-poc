/**
 * useAccountBalance — 계좌 잔고 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-3)
 *
 * GET /api/account/balance?userId=&accountType=
 * KIS inquire-balance output2 11 필드 — 예수금·평가금·평가손익 등.
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
  type AccountBalanceDto,
  type AccountType,
} from '../account'
import {
  ACCOUNT_STALE_TIME_MS,
  resolveAccountRefetchInterval,
  useAccountStompConnected,
} from './useAccountPollingGate'

interface UseAccountBalanceOptions {
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

export function useAccountBalance({
  userId,
  accountType = 'REAL',
  refetchInterval,
  enabled = true,
}: UseAccountBalanceOptions) {
  const queryEnabled = enabled && userId !== null && userId !== undefined
  const stompConnected = useAccountStompConnected(queryEnabled)
  const effectiveRefetchInterval =
    refetchInterval !== undefined
      ? refetchInterval
      : resolveAccountRefetchInterval(stompConnected)

  return useQuery<AccountBalanceDto, Error>({
    queryKey: ['account', 'balance', userId, accountType],
    queryFn: () => accountApi.getBalance(userId!, accountType),
    enabled: queryEnabled,
    staleTime: ACCOUNT_STALE_TIME_MS,
    refetchInterval: effectiveRefetchInterval,
    refetchIntervalInBackground: false, // A-3: background tab 시 pause (v5 default 명시화)
    refetchOnWindowFocus: true,
  })
}
