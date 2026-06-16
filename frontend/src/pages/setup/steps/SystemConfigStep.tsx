/**
 * Step 4 (내부 index) — DART + Slack 시스템 설정 (선택)
 *
 * spec §5-6 정합. ADR 0069 §2-2 forms/ 리팩토링:
 * - 입력 폼 로직 → SystemConfigForm 공통 컴포넌트로 이관
 * - Step layout(헤더·안내 문구·mutation 결합)은 그대로 유지
 * - "건너뛰기" 버튼 → system_config UPDATE 호출 없이 다음 step
 */
import { useState } from 'react'
import type { SystemConfigRequest } from '../../../api/onboarding'
import { extractErrorMessage, useUpdateSystemConfig } from '../../../api/onboarding'
import { useOnboardingStore } from '../../../stores/onboarding'
import type { SystemConfigFormValues } from '../../../components/settings/forms/SystemConfigForm'
import { SystemConfigForm } from '../../../components/settings/forms/SystemConfigForm'

interface Props {
  onNext: () => void
  onSkip: () => void
}

export function SystemConfigStep({ onNext, onSkip }: Props) {
  const [serverError, setServerError] = useState<string | null>(null)

  const setSystemConfig = useOnboardingStore((s) => s.setSystemConfig)
  const { mutate, isPending } = useUpdateSystemConfig()

  function handleSubmit(values: SystemConfigFormValues) {
    setServerError(null)

    const req: SystemConfigRequest = {
      dartApiKey: values.dartApiKey || undefined,
      slackWebhookUrl: values.slackWebhookUrl || undefined,
    }

    mutate(req, {
      onSuccess: () => {
        setSystemConfig()
        onNext()
      },
      onError: (err) => {
        setServerError(
          extractErrorMessage(err, '설정 저장에 실패했습니다. 다시 시도해 주세요.'),
        )
      },
    })
  }

  return (
    <div>
      <h2 className="mb-2 text-lg font-semibold text-white">시스템 설정 (선택)</h2>
      <p className="mb-6 text-sm text-gray-400">
        DART API 키와 Slack 알림 webhook을 등록하면 더 많은 기능을 사용할 수 있습니다.
        지금 건너뛰고 나중에 설정할 수도 있습니다.
      </p>

      {/* SystemConfigForm 공통 컴포넌트 사용 — ADR 0069 §2-2 중복 제거 */}
      <SystemConfigForm
        serverError={serverError}
        onSubmit={handleSubmit}
        isSubmitting={isPending}
        onSkip={onSkip}
        submitLabel={isPending ? '저장 중...' : '저장 후 다음 →'}
      />
    </div>
  )
}
