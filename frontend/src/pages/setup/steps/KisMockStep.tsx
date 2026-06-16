/**
 * Step 3 (내부 index) — 모의계좌 추가 (선택)
 *
 * spec §5-5 정합. ADR 0069 §2-2 forms/ 리팩토링:
 * - 입력 폼 로직 → KisCredentialForm 공통 컴포넌트로 이관
 * - Step layout (Yes/No 선택 + 건너뛰기)은 그대로 유지
 * - 검증 성공 시 hasMockRegistered = true
 */
import { useState } from 'react'
import type { KisMockRequest } from '../../../api/onboarding'
import { extractErrorMessage, useAddMockAccount } from '../../../api/onboarding'
import { useOnboardingStore } from '../../../stores/onboarding'
import type { KisCredentialFormValues } from '../../../components/settings/forms/KisCredentialForm'
import { KisCredentialForm } from '../../../components/settings/forms/KisCredentialForm'

interface Props {
  /** ADR 0089 Phase 2 — userId number (backend Long) */
  userId: number
  onNext: () => void
}

export function KisMockStep({ userId, onNext }: Props) {
  const [wantMock, setWantMock] = useState<'yes' | 'no' | null>(null)
  const [serverError, setServerError] = useState<string | null>(null)

  const setMockRegistered = useOnboardingStore((s) => s.setMockRegistered)
  const { mutate, isPending } = useAddMockAccount()

  function handleSubmit(values: KisCredentialFormValues) {
    setServerError(null)

    const req: KisMockRequest = {
      userId,
      mockAccountNumber: values.accountNumber,
      appKey: values.appKey,
      secretKey: values.secretKey,
    }

    mutate(req, {
      onSuccess: () => {
        setMockRegistered()
        onNext()
      },
      onError: (err) => {
        setServerError(
          extractErrorMessage(err, '모의계좌 등록에 실패했습니다. 다시 시도해 주세요.'),
        )
      },
    })
  }

  return (
    <div>
      <h2 className="mb-2 text-lg font-semibold text-white">모의계좌 추가 (선택)</h2>
      <p className="mb-6 text-sm text-gray-400">
        KIS 모의투자 계좌를 등록하면 실제 주문 없이 전략을 테스트할 수 있습니다.
      </p>

      {/* Yes / No 선택 */}
      <div className="mb-6 flex gap-3">
        <button
          type="button"
          onClick={() => setWantMock('yes')}
          className={`flex-1 rounded-lg border py-3 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 ${
            wantMock === 'yes'
              ? 'border-blue-500 bg-blue-950/50 text-blue-300'
              : 'border-gray-700 text-gray-400 hover:bg-gray-800'
          }`}
        >
          네, 등록할게요
        </button>
        <button
          type="button"
          onClick={() => setWantMock('no')}
          className={`flex-1 rounded-lg border py-3 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-gray-600 ${
            wantMock === 'no'
              ? 'border-gray-500 bg-gray-800 text-white'
              : 'border-gray-700 text-gray-400 hover:bg-gray-800'
          }`}
        >
          건너뛰기
        </button>
      </div>

      {/* Yes 선택 시 입력 폼 — KisCredentialForm 공통 컴포넌트 사용 */}
      {wantMock === 'yes' && (
        <KisCredentialForm
          accountType="MOCK"
          serverError={serverError}
          onSubmit={handleSubmit}
          isSubmitting={isPending}
          submitLabel={isPending ? '검증 중...' : '모의계좌 등록 →'}
        />
      )}

      {/* No 선택 시 다음 버튼 */}
      {wantMock === 'no' && (
        <button
          type="button"
          onClick={onNext}
          className="w-full rounded-lg bg-gray-700 px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-gray-600 focus:outline-none focus:ring-2 focus:ring-gray-500"
        >
          다음 단계로 →
        </button>
      )}
    </div>
  )
}
