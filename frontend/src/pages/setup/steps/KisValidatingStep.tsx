/**
 * Step 2 (내부 index) — KIS 실전 키 백엔드 검증 진행 화면
 *
 * spec §5-4 정합:
 * - "검증 중..." 스피너 표시 (예상 1~3초)
 * - POST /api/onboarding/kis/validate 호출
 * - 성공 시 zustand setRegistered + setStep(3)
 * - 실패 시 setStep(1) + 에러 메시지
 * - 검증 중 취소 불가 (in-flight 요청)
 */
import { useEffect } from 'react'
import type { KisValidateRequest } from '../../../api/onboarding'
import { extractErrorMessage, useValidateKisReal } from '../../../api/onboarding'
import { useOnboardingStore } from '../../../stores/onboarding'

interface Props {
  payload: KisValidateRequest
  onSuccess: () => void
  onFailure: (message: string) => void
}

export function KisValidatingStep({ payload, onSuccess, onFailure }: Props) {
  const { mutate, isPending } = useValidateKisReal()
  const setRegistered = useOnboardingStore((s) => s.setRegistered)

  useEffect(() => {
    mutate(payload, {
      onSuccess: (data) => {
        setRegistered(data.userId)
        onSuccess()
      },
      onError: (err) => {
        const message = extractErrorMessage(
          err,
          'KIS 키 검증에 실패했습니다. 키를 확인한 후 다시 시도해 주세요.',
        )
        onFailure(message)
      },
    })
  // payload는 부모가 넘긴 고정값 — 재호출 방지를 위해 deps 생략
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="flex flex-col items-center justify-center py-8">
      {isPending ? (
        <>
          {/* 스피너 */}
          <div className="mb-6 h-12 w-12 animate-spin rounded-full border-4 border-gray-700 border-t-blue-500" />
          <p className="mb-2 text-base font-medium text-white">KIS 키 검증 중...</p>
          <p className="text-sm text-gray-400">KIS 서버에 OAuth 토큰 발급을 요청하고 있습니다.</p>
          <p className="mt-2 text-xs text-gray-600">예상 소요 시간: 1~3초</p>
        </>
      ) : (
        // 요청이 완료되면 부모가 step을 전환하므로 잠깐 표시됨
        <p className="text-sm text-gray-400">처리 중...</p>
      )}
      <p className="mt-6 text-xs text-gray-700">검증 진행 중에는 취소할 수 없습니다.</p>
    </div>
  )
}
