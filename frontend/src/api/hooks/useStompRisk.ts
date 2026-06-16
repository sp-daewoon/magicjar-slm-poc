/**
 * useStompRisk — 리스크 이벤트 실시간 STOMP 구독 (T-RISK Phase 3 — ADR 0067)
 *
 * STOMP topic: /topic/risk.user.{userId}
 * StompRiskNotifier fanout 정합 (ADR 0067 §6-D)
 *
 * 기능:
 * - BLOCKED event: 토스트 (빨강)
 *   "[차단] 000660 — 포지션 한도 초과"
 * - AUTO_PAUSED event: 토스트 (회색=warning)
 *   "[자동 매수차단] strategy XXX — 연속 BLOCKED 10회"
 * - 연결 실패 시 toast 에러 표시
 *
 * 사용 예:
 *   const { latestRiskEvent } = useStompRisk({ userId: '42', enabled: true })
 */
import { useCallback, useRef, useState } from 'react'
import { useStompTopic } from '../../lib/stomp'
import { riskUser } from '../stompDestinations'
import type { RiskEventDto, RiskVerdict } from '../risk'
import { buildRiskToastMessage } from '../riskAlertMessage'
import { useToast } from '../../components/ui/ToastProvider'

export interface UseStompRiskOptions {
  /** ADR 0089 Phase 2 — userId number (backend Long) */
  userId: number
  enabled?: boolean
  onRiskEventReceived?: (event: RiskEventDto) => void
}

export interface UseStompRiskResult {
  /** 가장 최근 수신 리스크 이벤트 */
  latestRiskEvent: RiskEventDto | null
  /** 수신 이벤트 스택 (최근 20건) */
  riskEventQueue: RiskEventDto[]
  /** 큐 초기화 */
  clearQueue: () => void
}

// ToastType 매핑
// BLOCKED → 'error' (빨강), AUTO_PAUSED → 'warning' (회색/주황)
function verdictToToastType(verdict: RiskVerdict) {
  if (verdict === 'BLOCKED') return 'error' as const
  if (verdict === 'AUTO_PAUSED') return 'warning' as const
  return 'info' as const
}

const MAX_QUEUE = 20

export function useStompRisk({
  userId,
  enabled = true,
  onRiskEventReceived,
}: UseStompRiskOptions): UseStompRiskResult {
  const [latestRiskEvent, setLatestRiskEvent] = useState<RiskEventDto | null>(null)
  const [riskEventQueue, setRiskEventQueue] = useState<RiskEventDto[]>([])
  const onEventRef = useRef(onRiskEventReceived)
  onEventRef.current = onRiskEventReceived

  const { showToast } = useToast()

  const topic = userId !== null && userId !== undefined ? riskUser(userId) : ''

  const handleRiskEvent = useCallback(
    (event: RiskEventDto) => {
      setLatestRiskEvent(event)
      setRiskEventQueue((prev) => [event, ...prev].slice(0, MAX_QUEUE))

      // BLOCKED / AUTO_PAUSED 시에만 토스트 (ALLOWED는 알림 불필요)
      if (event.verdict === 'BLOCKED' || event.verdict === 'AUTO_PAUSED') {
        const msg = buildRiskToastMessage(event)
        showToast(msg, verdictToToastType(event.verdict))
      }

      // 외부 콜백
      onEventRef.current?.(event)
    },
    [showToast],
  )

  useStompTopic<RiskEventDto>(
    topic,
    handleRiskEvent,
    enabled && userId !== null && userId !== undefined,
  )

  const clearQueue = useCallback(() => {
    setRiskEventQueue([])
  }, [])

  return { latestRiskEvent, riskEventQueue, clearQueue }
}
