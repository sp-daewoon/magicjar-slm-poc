/**
 * useAiExplain — AI 코멘트 진입점 hook (T-AI R2 frontend §2-2)
 *
 * 5 진입점 공통 wiring:
 * - POST /api/ai/explain → ACCEPTED (requestId) | REFUSED (userMessage 한국어)
 * - STOMP /topic/ai.explain.{requestId} subscribe → LLM 응답 도달 시 envelope 수신
 *
 * 상태 머신:
 *   IDLE → REQUESTING → ACCEPTED (waiting) → RECEIVED | TIMEOUT
 *                     → REFUSED (graceful 표시)
 *                     → ERROR (네트워크/400)
 *
 * 매매 결정권 0 — 본 hook 호출 결과로 자동 매수·매도 흐름 0건.
 * 1회 1요청 — `trigger()` 호출 시 이전 requestId의 STOMP 구독 해제 + 새 요청 발행.
 *
 * 310초 timeout — Claude CLI/bridge 최대 300초 + 네트워크 여유 10초.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  requestAiExplain,
  type AiCommentType,
  type AiExplainResponse,
  type AiResponseEnvelope,
} from '../ai'
import { getStompClient } from '../../lib/stomp'
import { aiExplain } from '../stompDestinations'

export type AiExplainState =
  | { status: 'IDLE' }
  | { status: 'REQUESTING' }
  | { status: 'WAITING'; requestId: string }
  | { status: 'RECEIVED'; envelope: AiResponseEnvelope }
  | { status: 'REFUSED'; refuseCode: string | null; userMessage: string }
  | { status: 'ERROR'; message: string }
  | { status: 'TIMEOUT'; requestId: string }

interface UseAiExplainOptions {
  /** 진입점 타입 — 5 진입점 분기 (prompt_template 매핑 키). */
  type: AiCommentType
  /** 1일 토큰 한도 집계 키. setup 미완 시 null → trigger 비활성. */
  userId: number | null | undefined
  /** ms — STOMP 응답 도달 timeout. default 310s. */
  timeoutMs?: number
}

const DEFAULT_TIMEOUT_MS = 310_000

export function useAiExplain({ type, userId, timeoutMs = DEFAULT_TIMEOUT_MS }: UseAiExplainOptions) {
  const [state, setState] = useState<AiExplainState>({ status: 'IDLE' })

  // 활성 구독 cleanup용 ref. trigger() 호출 시 이전 구독 해제.
  const unsubRef = useRef<(() => void) | null>(null)
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 본 hook unmount 시 정리.
  useEffect(() => {
    return () => {
      unsubRef.current?.()
      unsubRef.current = null
      if (timeoutRef.current) clearTimeout(timeoutRef.current)
    }
  }, [])

  const cleanup = useCallback(() => {
    unsubRef.current?.()
    unsubRef.current = null
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current)
      timeoutRef.current = null
    }
  }, [])

  /**
   * 5 진입점 공통 trigger.
   *
   * @param context prompt 변수 치환 입력 — type 별 backend variables_schema 정합.
   *                예: SYMBOL → {code,name} · MARKET → {kospiTrend,sectorTopList,maPeriods,rsPeriod}
   */
  const trigger = useCallback(
    async (context: Record<string, unknown>) => {
      if (userId == null) {
        setState({
          status: 'ERROR',
          message: 'AI 기능을 사용하려면 KIS 계좌 설정을 완료해 주세요.',
        })
        return
      }

      // 이전 요청 cleanup — 1회 1요청 LOCK.
      cleanup()
      setState({ status: 'REQUESTING' })

      let response: AiExplainResponse
      try {
        response = await requestAiExplain({ type, userId, context })
      } catch (err) {
        const message =
          err instanceof Error
            ? `AI 요청 실패: ${err.message}`
            : 'AI 요청 중 알 수 없는 오류가 발생했습니다.'
        setState({ status: 'ERROR', message })
        return
      }

      // 4단계 가드 — REFUSED 분기 (KillSwitch · AI_DISABLED · 토큰 한도 · prompt 미존재).
      if (response.status === 'REFUSED') {
        setState({
          status: 'REFUSED',
          refuseCode: response.refuseCode ?? null,
          userMessage:
            response.userMessage ?? 'AI 기능을 사용할 수 없습니다. 설정을 확인해 주세요.',
        })
        return
      }

      // ACCEPTED — STOMP 구독 + timeout 가드.
      const { requestId } = response
      setState({ status: 'WAITING', requestId })

      // STOMP subscribe — getStompClient 직접 사용 (requestId 동적 + 1회 cleanup 의무 → useStompTopic hook 패턴 부적합).
      const client = getStompClient()

      // 연결 보장 — 대기 후 subscribe (race 가드).
      const subscribeNow = () => {
        if (!client.connected) return
        const topic = aiExplain(requestId)
        const sub = client.subscribe(topic, (stompMsg) => {
          try {
            const envelope = JSON.parse(stompMsg.body) as AiResponseEnvelope
            // requestId 일치 검증 (다른 메시지 도달 가드 — 일반적으로 발생 0).
            if (envelope.requestId !== requestId) return
            setState({ status: 'RECEIVED', envelope })
            cleanup()
          } catch {
            // JSON 파싱 실패 — 무시 (graceful).
          }
        })
        unsubRef.current = () => {
          try {
            sub.unsubscribe()
          } catch {
            // graceful — 이미 disconnect 된 경우.
          }
        }
      }

      if (client.connected) {
        subscribeNow()
      } else {
        // 미연결 시 active 후 onConnect 1회 hook.
        const prevOnConnect = client.onConnect
        client.onConnect = (frame) => {
          if (prevOnConnect) prevOnConnect.call(client, frame)
          subscribeNow()
        }
        if (!client.active) client.activate()
      }

      // Claude CLI/bridge 장기 응답 대기 가드 — 사용자 명시적 안내.
      timeoutRef.current = setTimeout(() => {
        setState({ status: 'TIMEOUT', requestId })
        cleanup()
      }, timeoutMs)
    },
    [type, userId, timeoutMs, cleanup],
  )

  /** 응답 카드 닫기 (다음 요청 위해 IDLE 복귀). */
  const reset = useCallback(() => {
    cleanup()
    setState({ status: 'IDLE' })
  }, [cleanup])

  return {
    state,
    trigger,
    reset,
    /** convenience flag — 진행 중 (요청 발행~응답 대기). */
    isInProgress: state.status === 'REQUESTING' || state.status === 'WAITING',
  }
}
