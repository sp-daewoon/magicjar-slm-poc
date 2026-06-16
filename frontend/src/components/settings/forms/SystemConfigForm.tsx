/**
 * SystemConfigForm — DART API 키 + Slack Webhook URL 입력 공통 컴포넌트
 *
 * ADR 0069 §2-2 공통 컴포넌트 매트릭스 정합.
 * SetupWizard (SystemConfigStep) + SettingsModal (SystemConfigSection) 공유.
 *
 * - DART API 키: 선택 입력
 * - Slack Webhook URL: https://hooks.slack.com/ 시작 URL 검증
 */
import { useEffect, useState } from 'react'

export interface SystemConfigFormValues {
  dartApiKey: string
  slackWebhookUrl: string
}

interface Props {
  /** 초기값 (SettingsModal에서 기존 값을 pre-fill) */
  initialValues?: Partial<SystemConfigFormValues>
  /** 폼 값 변경 콜백 */
  onChange?: (values: SystemConfigFormValues) => void
  /** 외부에서 에러 주입 */
  serverError?: string | null
  /** submit 핸들러 */
  onSubmit?: (values: SystemConfigFormValues) => void
  /** submit 버튼 텍스트 override */
  submitLabel?: string
  /** submit loading 상태 */
  isSubmitting?: boolean
  /** 건너뛰기 버튼 (SetupWizard에서만 사용) */
  onSkip?: () => void
  /** 버튼 숨김 (상위 컨트롤 시) */
  hideSubmitButton?: boolean
}

export function SystemConfigForm({
  initialValues,
  onChange,
  serverError,
  onSubmit,
  submitLabel,
  isSubmitting = false,
  onSkip,
  hideSubmitButton = false,
}: Props) {
  const [dartApiKey, setDartApiKey] = useState(initialValues?.dartApiKey ?? '')
  const [slackWebhookUrl, setSlackWebhookUrl] = useState(initialValues?.slackWebhookUrl ?? '')
  const [errors, setErrors] = useState<Record<string, string>>({})

  useEffect(() => {
    setDartApiKey(initialValues?.dartApiKey ?? '')
    setSlackWebhookUrl(initialValues?.slackWebhookUrl ?? '')
  }, [initialValues?.dartApiKey, initialValues?.slackWebhookUrl])

  function notifyChange(dart: string, slack: string) {
    onChange?.({ dartApiKey: dart, slackWebhookUrl: slack })
  }

  function validate(): boolean {
    const newErrors: Record<string, string> = {}
    if (
      slackWebhookUrl.trim() &&
      !slackWebhookUrl.startsWith('https://hooks.slack.com/')
    ) {
      newErrors.slackWebhookUrl = 'Slack webhook URL은 https://hooks.slack.com/ 으로 시작해야 합니다'
    }
    setErrors(newErrors)
    return Object.keys(newErrors).length === 0
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!validate()) return
    onSubmit?.({ dartApiKey: dartApiKey.trim(), slackWebhookUrl: slackWebhookUrl.trim() })
  }

  const defaultSubmitLabel = isSubmitting ? '저장 중...' : (submitLabel ?? '저장 후 다음 →')

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {serverError && (
        <div className="rounded-lg border border-red-800/50 bg-red-950/30 p-3">
          <p className="text-sm text-red-400">{serverError}</p>
        </div>
      )}

      {/* DART API 키 */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-300">
          DART API 키 <span className="text-gray-500 text-xs">(선택)</span>
        </label>
        <input
          type="text"
          autoComplete="off"
          placeholder="open.dart.fss.or.kr 발급 API 키"
          value={dartApiKey}
          onChange={(e) => {
            setDartApiKey(e.target.value)
            notifyChange(e.target.value, slackWebhookUrl)
          }}
          className="w-full rounded-lg border border-gray-700 px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p className="mt-1 text-xs text-gray-600">공시 정보 조회에 사용됩니다</p>
      </div>

      {/* Slack Webhook URL */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-300">
          Slack Webhook URL <span className="text-gray-500 text-xs">(선택)</span>
        </label>
        <input
          type="url"
          autoComplete="off"
          placeholder="https://hooks.slack.com/services/..."
          value={slackWebhookUrl}
          onChange={(e) => {
            setSlackWebhookUrl(e.target.value)
            notifyChange(dartApiKey, e.target.value)
          }}
          className={[
            'w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500',
            errors.slackWebhookUrl ? 'border-red-600' : 'border-gray-700',
          ].join(' ')}
        />
        {errors.slackWebhookUrl && (
          <p className="mt-1 text-xs text-red-400">{errors.slackWebhookUrl}</p>
        )}
        <p className="mt-1 text-xs text-gray-600">
          등록 시 테스트 메시지가 발송됩니다 — Slack에서 확인하세요
        </p>
      </div>

      {/* 버튼 영역 */}
      {!hideSubmitButton && (
        <div className={onSkip ? 'flex gap-3 pt-2' : 'pt-2'}>
          {onSkip && (
            <button
              type="button"
              onClick={onSkip}
              disabled={isSubmitting}
              className="flex-1 rounded-lg border border-gray-700 px-4 py-2.5 text-sm font-medium text-gray-400 transition-colors hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-gray-600 disabled:opacity-50"
            >
              건너뛰기
            </button>
          )}
          <button
            type="submit"
            disabled={isSubmitting}
            className={[
              'rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50',
              onSkip ? 'flex-1' : 'w-full',
            ].join(' ')}
          >
            {defaultSubmitLabel}
          </button>
        </div>
      )}
    </form>
  )
}
