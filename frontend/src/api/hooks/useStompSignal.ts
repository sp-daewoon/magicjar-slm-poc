/**
 * useStompSignal — 사용자별 실시간 시그널 STOMP 구독 (T-SIG Phase 3 — ADR 0066)
 *
 * STOMP topic: /topic/signal.user.{userId}
 * StompSignalDispatcher fanout 정합 (ADR 0066 §4-D)
 *
 * 기능:
 * - 시그널 도착 시 자체 Toast 알림 노출
 *   포맷: "[BUY] 000660 5m 1,898,000 — RSI(14) < 30"
 * - onSignalReceived 콜백 → SignalListPanel prepend 용
 * - 연결 실패 시 toast 에러 표시 (ADR 0064 candle topic 패턴 정합)
 *
 * 사용 예:
 *   const { latestSignal } = useStompSignal({ userId: '42', enabled: true })
 */
import { useCallback, useRef, useState } from 'react'
import { useStompTopic } from '../../lib/stomp'
import { signalUser } from '../stompDestinations'
import { type SignalDto } from '../signal'
import { useToast } from '../../components/ui/ToastProvider'

export interface UseStompSignalOptions {
  /** ADR 0089 Phase 2 — userId number */
  userId: number
  enabled?: boolean
  onSignalReceived?: (signal: SignalDto) => void
}

export interface UseStompSignalResult {
  /** 가장 최근 수신 시그널 */
  latestSignal: SignalDto | null
  /** 수신된 시그널 스택 (최근 20건 — prepend용) */
  signalQueue: SignalDto[]
  /** 큐 초기화 */
  clearQueue: () => void
}

/** 가격 숫자 포맷 (천 단위 콤마) */
function formatPrice(priceStr: string): string {
  const num = Number(priceStr.replace(/,/g, ''))
  if (isNaN(num)) return priceStr
  return num.toLocaleString('ko-KR')
}

/** 시그널 토스트 메시지 조립 */
function buildToastMessage(signal: SignalDto): string {
  const sideLabel = signal.side === 'BUY' ? '매수' : signal.side === 'SELL' ? '매도' : '홀드'
  const codePart = signal.codeName ? `${signal.codeName}(${signal.code})` : signal.code
  const pricePart = formatPrice(signal.price)
  const rulePart = signal.rule ? ` — ${signal.rule}` : ''
  return `[${sideLabel}] ${codePart} ${signal.interval} ${pricePart}원${rulePart}`
}

const MAX_QUEUE = 20

export function useStompSignal({
  userId,
  enabled = true,
  onSignalReceived,
}: UseStompSignalOptions): UseStompSignalResult {
  const [latestSignal, setLatestSignal] = useState<SignalDto | null>(null)
  const [signalQueue, setSignalQueue] = useState<SignalDto[]>([])
  const onSignalReceivedRef = useRef(onSignalReceived)
  onSignalReceivedRef.current = onSignalReceived

  const { showToast } = useToast()

  const topic = userId !== null && userId !== undefined ? signalUser(userId) : ''

  const handleSignal = useCallback(
    (signal: SignalDto) => {
      setLatestSignal(signal)
      setSignalQueue((prev) => [signal, ...prev].slice(0, MAX_QUEUE))

      // 토스트 알림
      const msg = buildToastMessage(signal)
      const toastType = signal.side === 'BUY' ? 'success' : signal.side === 'SELL' ? 'error' : 'info'
      showToast(msg, toastType)

      // 외부 콜백
      onSignalReceivedRef.current?.(signal)
    },
    [showToast],
  )

  useStompTopic<SignalDto>(
    topic,
    handleSignal,
    enabled && userId !== null && userId !== undefined,
  )

  const clearQueue = useCallback(() => {
    setSignalQueue([])
  }, [])

  return { latestSignal, signalQueue, clearQueue }
}
