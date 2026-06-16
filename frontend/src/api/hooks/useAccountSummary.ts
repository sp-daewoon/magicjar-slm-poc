/**
 * useAccountSummary — 계좌 요약 조회 훅 (T-ACCT Phase 4 — ADR 0081 + spec §5-1)
 *
 * GET /api/account/summary?userId=&accountType=
 * Dashboard 헤더용 — 총 평가금액·예수금 D+2·평가손익·수익률·보유 종목 수.
 *
 * - STOMP push (useStompAccountBalance) 가 primary — 주문체결·취소·거절 시 즉시 invalidate.
 * - fallback polling 은 STOMP 끊김 시에만 (60s). STOMP CONNECTED 면 폴링 0.
 * - userId 없으면 쿼리 비활성 (feedback_no_mock_data 정합)
 * - mock 합성 0 — 실패 시 isError 노출 → UI "데이터 없음" 한국어
 *
 * 폴링 정리 (2026-06-08 — KIS_TPS_EXCEEDED burst 근본 정리, 사용자 dogfooding):
 *   기존: refetchInterval 30s 고정 → STOMP 연결돼 있어도 30초마다 KIS 호출.
 *   변경: STOMP 연결 시 폴링 off (useSymbol pollingEnabled 패턴), 끊김 시에만 60s fallback.
 *   summary·positions·balance 3 hook 동시 마운트 시 steady-state KIS 폴링 3건/30s → 0건.
 *   refetchInterval prop 명시 시 STOMP 게이트 무시하고 강제 override (기존 시그니처 보존).
 *
 * C-3 polling → push 전환 (2026-05-20): refetchInterval 5s → 30s.
 *   "소켓방식으로 가져와야하는거" (사용자 dogfooding). STOMP /topic/account.balance.{userId} 가
 *   주문체결·취소·거절 시 즉시 invalidate 트리거.
 *
 * A-3 (2026-05-20): refetchIntervalInBackground=false 명시화 (background tab 시 polling pause).
 */
import { useQuery } from '@tanstack/react-query'
import {
  accountApi,
  type AccountSummaryDto,
  type AccountType,
} from '../account'
import {
  ACCOUNT_STALE_TIME_MS,
  resolveAccountRefetchInterval,
  useAccountStompConnected,
} from './useAccountPollingGate'

interface UseAccountSummaryOptions {
  /** ADR 0089 Phase 2 — userId number (backend Long) */
  userId: number | null | undefined
  accountType?: AccountType
  /**
   * 폴링 주기 ms 강제 override. 미지정(default) 시 STOMP 게이트 적용:
   *   STOMP CONNECTED → 폴링 off, 끊김 시 60s fallback.
   * 숫자 명시 시 STOMP 상태 무관하게 해당 주기로 폴링 (기존 시그니처·테스트 보존).
   */
  refetchInterval?: number
  enabled?: boolean
}

export function useAccountSummary({
  userId,
  accountType = 'REAL',
  refetchInterval,
  enabled = true,
}: UseAccountSummaryOptions) {
  const queryEnabled = enabled && userId !== null && userId !== undefined
  const stompConnected = useAccountStompConnected(queryEnabled)
  const effectiveRefetchInterval =
    refetchInterval !== undefined
      ? refetchInterval
      : resolveAccountRefetchInterval(stompConnected)

  return useQuery<AccountSummaryDto, Error>({
    queryKey: ['account', 'summary', userId, accountType],
    queryFn: () => accountApi.getSummary(userId!, accountType),
    enabled: queryEnabled,
    staleTime: ACCOUNT_STALE_TIME_MS,
    refetchInterval: effectiveRefetchInterval,
    refetchIntervalInBackground: false, // A-3: background tab 시 pause (v5 default 명시화)
    refetchOnWindowFocus: true, // 포커스 복귀 시 1회 갱신
  })
}
