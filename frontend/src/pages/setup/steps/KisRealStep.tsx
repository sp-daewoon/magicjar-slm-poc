/**
 * Step 1 (내부 index) — KIS 실전 키 입력 화면
 *
 * spec §5-3 정합. ADR 0069 §2-2 forms/ 리팩토링:
 * - 입력 폼 로직 → KisCredentialForm 공통 컴포넌트로 이관
 * - Step layout(헤더·안내 문구·검증 mutation 결합)은 그대로 유지
 * - KisCredentialForm: accountNumber·appKey·secretKey 검증 + autocomplete=off + zero-fill
 */
import { useOnboardingStore } from '../../../stores/onboarding'
import type { KisCredentialFormValues } from '../../../components/settings/forms/KisCredentialForm'
import { KisCredentialForm } from '../../../components/settings/forms/KisCredentialForm'

interface Props {
  onNext: (data: {
    accountNumber: string
    displayName: string
    appKey: string
    secretKey: string
  }) => void
}

export function KisRealStep({ onNext }: Props) {
  const validationError = useOnboardingStore((s) => s.validationError)

  function handleSubmit(values: KisCredentialFormValues) {
    // displayName은 Step 1에서 닉네임 별도 입력 없이 빈 값으로 전달
    // (SettingsModal과 달리 SetupWizard는 displayName을 accountNumber와 동시 입력)
    onNext({
      accountNumber: values.accountNumber,
      displayName: '',
      appKey: values.appKey,
      secretKey: values.secretKey,
    })
  }

  return (
    <div>
      <h2 className="mb-2 text-lg font-semibold text-white">KIS 실전 계좌 정보 입력</h2>
      <p className="mb-6 text-sm text-gray-400">
        KIS 개발자 콘솔에서 발급받은 앱 키와 시크릿을 입력해 주세요.
        입력값은 AES-256-GCM으로 암호화되어 저장됩니다.
      </p>

      {/* 이전 검증 실패 에러 메시지 */}
      {validationError && (
        <div className="mb-4 rounded-lg border border-red-800/50 bg-red-950/30 p-3">
          <p className="text-sm text-red-400">{validationError}</p>
        </div>
      )}

      {/* KisCredentialForm 공통 컴포넌트 사용 — ADR 0069 §2-2 중복 제거 */}
      <KisCredentialForm
        accountType="REAL"
        onSubmit={handleSubmit}
        submitLabel="검증 시작 →"
      />
    </div>
  )
}
