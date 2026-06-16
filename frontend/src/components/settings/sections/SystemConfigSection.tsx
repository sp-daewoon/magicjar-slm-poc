/**
 * SystemConfigSection — 시스템 설정 섹션 (DART + Slack)
 *
 * ADR 0069 §3 Section 3: SettingsModal 내 mount 위치.
 * 기존 useUpdateSystemConfig endpoint(POST /api/onboarding/system-config) reuse.
 * 1차 PR fully functional — RealTradingConsentToggle과 함께 기존 endpoint 운영 중.
 */
import { useState } from 'react'
import type { SectionStatus } from '../SettingsSection'
import { SettingsSection } from '../SettingsSection'
import type { SystemConfigFormValues } from '../forms/SystemConfigForm'
import { SystemConfigForm } from '../forms/SystemConfigForm'
import { extractErrorMessage, useOnboardingStatus, useSystemConfig, useUpdateSystemConfig } from '../../../api/onboarding'

export function SystemConfigSection() {
  const { data: status } = useOnboardingStatus()
  const { data: systemConfig, isLoading: isSystemConfigLoading } = useSystemConfig()
  const [sectionStatus, setSectionStatus] = useState<SectionStatus>('idle')
  const [sectionError, setSectionError] = useState<string | null>(null)
  const [serverError, setServerError] = useState<string | null>(null)

  const { mutate, isPending } = useUpdateSystemConfig()

  function handleSubmit(values: SystemConfigFormValues) {
    setSectionStatus('saving')
    setSectionError(null)
    setServerError(null)

    mutate(
      {
        dartApiKey: values.dartApiKey,
        slackWebhookUrl: values.slackWebhookUrl,
      },
      {
        onSuccess: () => {
          setSectionStatus('saved')
          // 3초 후 idle 복귀
          setTimeout(() => setSectionStatus('idle'), 3000)
        },
        onError: (err) => {
          const message = extractErrorMessage(err, '설정 저장에 실패했습니다. 다시 시도해 주세요.')
          setSectionStatus('error')
          setSectionError(message)
          setServerError(message)
        },
      },
    )
  }

  const hasDart = systemConfig?.hasDartKey ?? status?.hasDartKey ?? false
  const hasSlack = systemConfig?.hasSlackWebhook ?? status?.hasSlackWebhook ?? false

  return (
    <SettingsSection
      title="시스템 설정"
      description="DART API 키와 Slack 알림 webhook을 관리합니다."
      status={sectionStatus}
      errorMessage={sectionError ?? undefined}
    >
      {/* 현재 등록 상태 */}
      <div className="mb-4 flex gap-3">
        <div className="flex items-center gap-1.5">
          <span className="text-xs text-gray-500">DART 키:</span>
          {hasDart ? (
            <span className="rounded-full bg-green-900/40 px-2 py-0.5 text-xs text-green-400">등록됨</span>
          ) : (
            <span className="rounded-full bg-gray-800 px-2 py-0.5 text-xs text-gray-500">미등록</span>
          )}
        </div>
        <div className="flex items-center gap-1.5">
          <span className="text-xs text-gray-500">Slack:</span>
          {hasSlack ? (
            <span className="rounded-full bg-green-900/40 px-2 py-0.5 text-xs text-green-400">등록됨</span>
          ) : (
            <span className="rounded-full bg-gray-800 px-2 py-0.5 text-xs text-gray-500">미등록</span>
          )}
        </div>
      </div>

      <SystemConfigForm
        initialValues={{
          dartApiKey: systemConfig?.dartApiKey ?? '',
          slackWebhookUrl: systemConfig?.slackWebhookUrl ?? '',
        }}
        serverError={serverError}
        onSubmit={handleSubmit}
        isSubmitting={isPending || isSystemConfigLoading}
        submitLabel={isPending ? '저장 중...' : '저장'}
      />
    </SettingsSection>
  )
}
