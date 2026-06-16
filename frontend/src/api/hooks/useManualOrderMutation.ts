/**
 * useManualOrderMutation — 사용자 수동 주문 발행 hook (T-MAN Phase 2 — ADR 0082 B-2-extended)
 *
 * POST /api/orders/manual
 *
 * 기능:
 *  - TanStack Query useMutation
 *  - onSuccess: ['orders'] 캐시 invalidate + 토스트 "수동 주문 접수됨" + navigate('/orders')
 *  - onError 분기 (한국어 토스트):
 *      403 REAL_GATE_DENIED:ALLOW_REAL → "실거래 모드 미허용 — 설정에서 동의 토글 확인"
 *      403 REAL_GATE_DENIED:CONSENT    → "실거래 동의 미체결 — 설정 > 실거래 동의 토글 확인"
 *      403 KILLSWITCH_ON               → "킬스위치 ON — 모든 주문 차단"
 *      409 IDEMPOTENCY                 → "1초 내 중복 주문 — 잠시 후 재시도"
 *      400                             → "주문 검증 실패 — 입력값 확인"
 *      503                             → "Kafka publish 실패 — 잠시 후 재시도"
 *      기타                            → 응답 message fallback
 *
 * AI 호출 0 — 룰 5 (W3 LLM provider import 0건).
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import axios from 'axios'
import { useNavigate } from 'react-router-dom'
import { orderApi, type ManualOrderRequest, type ManualOrderResponse } from '../order'
import { useToast } from '../../components/ui/ToastProvider'

/** axios 에러 본문에서 한국어 메시지를 추출 (3중 게이트·KillSwitch·IDEMPOTENCY 분기) */
function extractErrorMessage(error: unknown): string {
  if (!axios.isAxiosError(error)) {
    return '알 수 없는 오류가 발생했습니다.'
  }

  const status = error.response?.status
  const data = error.response?.data as { message?: string; code?: string; error?: string } | undefined
  const backendMessage = data?.message ?? data?.error ?? ''

  if (status === 403) {
    if (backendMessage.includes('ALLOW_REAL')) {
      return '실거래 모드 미허용 — 설정 화면에서 ALLOW_REAL 환경변수 확인'
    }
    if (backendMessage.includes('CONSENT')) {
      return '실거래 동의 미체결 — 설정 > 실거래 동의 토글 확인'
    }
    if (backendMessage.includes('KILLSWITCH')) {
      return '킬스위치 ON — 모든 주문 차단됨'
    }
    if (backendMessage.includes('REAL_GATE_DENIED')) {
      return '실거래 게이트 차단 — 3중 게이트(MODE·ALLOW_REAL·동의) 확인'
    }
    return backendMessage || '주문 차단됨 (403)'
  }

  if (status === 409) {
    return '1초 내 중복 주문 — 잠시 후 재시도해주세요'
  }

  if (status === 400) {
    return backendMessage || '주문 검증 실패 — 입력값을 확인해주세요'
  }

  if (status === 503) {
    return 'Kafka publish 실패 — 잠시 후 재시도해주세요'
  }

  return backendMessage || `주문 발행 실패 (HTTP ${status ?? '?'})`
}

export interface UseManualOrderMutationOptions {
  /** 성공 시 자동 /orders 이동 여부 (default: true) */
  navigateOnSuccess?: boolean
}

export function useManualOrderMutation(options: UseManualOrderMutationOptions = {}) {
  const { navigateOnSuccess = true } = options
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const { showToast } = useToast()

  return useMutation<ManualOrderResponse, Error, ManualOrderRequest>({
    mutationFn: (request) => orderApi.manualIssue(request),
    onSuccess: (data) => {
      // 주문 목록 캐시 무효화 — useStompOrder가 REQUESTED 이벤트 도래 시 한 번 더 새로고침
      void queryClient.invalidateQueries({ queryKey: ['orders'] })

      showToast(data.message || '수동 주문이 접수되었습니다.', 'success')

      if (navigateOnSuccess) {
        navigate('/orders')
      }
    },
    onError: (error: unknown) => {
      const message = extractErrorMessage(error)
      showToast(message, 'error')
    },
  })
}
