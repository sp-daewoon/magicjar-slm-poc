/**
 * RealtimeFallbackPolicySection — 자동매매 데이터 정책.
 *
 * fallback source candle(KIS-POLLING/KIS-NOTICK-FALLBACK)의 신규 BUY 허용 여부를 운영자가 선택한다.
 */
import { useEffect, useState } from 'react'
import { extractErrorMessage, useSystemConfig, useUpdateSystemConfig } from '../../../api/onboarding'
import { useToast } from '../../ui/ToastProvider'
import type { SectionStatus } from '../SettingsSection'
import { SettingsSection } from '../SettingsSection'

export function RealtimeFallbackPolicySection() {
  const { data, isLoading, isError } = useSystemConfig()
  const { mutate, isPending } = useUpdateSystemConfig()
  const { showToast } = useToast()
  const [enabled, setEnabled] = useState(true)
  const [sectionStatus, setSectionStatus] = useState<SectionStatus>('idle')
  const [sectionError, setSectionError] = useState<string | null>(null)

  useEffect(() => {
    if (data) setEnabled(data.realtimeFallbackBuyEnabled)
  }, [data])

  function handleToggle(nextEnabled: boolean) {
    const previous = enabled
    setEnabled(nextEnabled)
    setSectionStatus('saving')
    setSectionError(null)

    mutate(
      { realtimeFallbackBuyEnabled: nextEnabled },
      {
        onSuccess: () => {
          setSectionStatus('saved')
          showToast(
            nextEnabled
              ? 'fallback 봉 신규 매수 허용으로 변경했습니다.'
              : 'fallback 봉 신규 매수를 차단했습니다.',
            nextEnabled ? 'success' : 'warning',
          )
          setTimeout(() => setSectionStatus('idle'), 3000)
        },
        onError: (err) => {
          const message = extractErrorMessage(err, '자동매매 데이터 정책 저장에 실패했습니다.')
          setEnabled(previous)
          setSectionStatus('error')
          setSectionError(message)
          showToast(message, 'error')
        },
      },
    )
  }

  if (isLoading) {
    return (
      <SettingsSection title="자동매매 데이터 정책" description="실시간 fallback 봉의 신규 매수 정책.">
        <div className="h-12 animate-pulse rounded-lg bg-gray-800" />
      </SettingsSection>
    )
  }

  if (isError) {
    return (
      <SettingsSection
        title="자동매매 데이터 정책"
        description="실시간 fallback 봉의 신규 매수 정책."
        status="error"
        errorMessage="정책을 불러오지 못했습니다"
      >
        <p className="text-xs text-red-400">서버와 연결에 실패했습니다.</p>
      </SettingsSection>
    )
  }

  return (
    <SettingsSection
      title="자동매매 데이터 정책"
      description="실시간 fallback 봉의 신규 매수 정책."
      status={sectionStatus}
      errorMessage={sectionError ?? undefined}
    >
      <div className="flex items-center justify-between gap-4 rounded-lg border border-gray-800 bg-gray-900/50 px-4 py-3">
        <div className="min-w-0">
          <div className="text-sm font-medium text-gray-200">fallback 봉 신규 매수</div>
          <div className="mt-1 text-xs text-gray-500">
            OFF면 KIS-POLLING·무tick fallback 봉은 BUY만 차단합니다.
          </div>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={enabled}
          disabled={isPending}
          onClick={() => handleToggle(!enabled)}
          className={[
            'relative h-7 w-12 shrink-0 rounded-full border transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/60 disabled:cursor-not-allowed disabled:opacity-60',
            enabled ? 'border-green-500/60 bg-green-600/80' : 'border-gray-700 bg-gray-800',
          ].join(' ')}
        >
          <span
            className={[
              'absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform',
              enabled ? 'translate-x-5' : 'translate-x-0.5',
            ].join(' ')}
          />
          <span className="sr-only">fallback 봉 신규 매수 {enabled ? '허용' : '차단'}</span>
        </button>
      </div>
    </SettingsSection>
  )
}
