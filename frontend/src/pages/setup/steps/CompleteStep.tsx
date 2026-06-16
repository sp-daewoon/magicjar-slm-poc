/**
 * Step 5 (내부 index) — 셋업 완료 화면
 *
 * spec §5-7 정합:
 * - 등록 요약 (maskedAccount·hasMockAccount·hasDartKey·hasSlackWebhook)
 * - "시작하기" 버튼 → POST /api/onboarding/complete → Navigate to="/"
 * - 취소 불가 (셋업 완료 상태)
 */
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { OnboardingCompleteRequest } from '../../../api/onboarding'
import { extractErrorMessage, useCompleteOnboarding } from '../../../api/onboarding'
import { useOnboardingStore } from '../../../stores/onboarding'

export function CompleteStep() {
  const navigate = useNavigate()
  const [serverError, setServerError] = useState<string | null>(null)

  const registeredUserId = useOnboardingStore((s) => s.registeredUserId)
  const hasMockRegistered = useOnboardingStore((s) => s.hasMockRegistered)
  const hasSystemConfig = useOnboardingStore((s) => s.hasSystemConfig)
  const reset = useOnboardingStore((s) => s.reset)

  const { mutate, isPending } = useCompleteOnboarding()

  function handleStart() {
    if (registeredUserId === null) return
    setServerError(null)

    const req: OnboardingCompleteRequest = { userId: registeredUserId }
    mutate(req, {
      onSuccess: (data) => {
        reset()
        void navigate(data.redirectTo, { replace: true })
      },
      onError: (err) => {
        setServerError(
          extractErrorMessage(err, '셋업 완료 처리에 실패했습니다. 다시 시도해 주세요.'),
        )
      },
    })
  }

  // ADR 0089 Phase 2 — registeredUserId now number; convert to padded 8-digit string for masking
  const maskedAccount =
    registeredUserId !== null
      ? `${String(registeredUserId).padStart(8, '0').substring(0, 4)}****`
      : '--------'

  const items = [
    { label: '실전 계좌', value: maskedAccount, done: registeredUserId !== null },
    { label: '모의 계좌', value: hasMockRegistered ? '등록됨' : '미등록', done: hasMockRegistered },
    { label: 'DART API', value: hasSystemConfig ? '설정됨' : '미설정', done: hasSystemConfig },
    { label: 'Slack 알림', value: hasSystemConfig ? '설정됨' : '미설정', done: hasSystemConfig },
  ]

  return (
    <div>
      <div className="mb-6 flex items-center gap-3">
        <div className="flex h-10 w-10 items-center justify-center rounded-full bg-green-900/50">
          <svg
            className="h-5 w-5 text-green-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
          </svg>
        </div>
        <div>
          <h2 className="text-lg font-semibold text-white">셋업 완료!</h2>
          <p className="text-sm text-gray-400">magicJar를 시작할 준비가 되었습니다</p>
        </div>
      </div>

      {/* 등록 요약 */}
      <div className="mb-6 rounded-lg border border-gray-800 bg-gray-800/50 p-4">
        <h3 className="mb-3 text-xs font-medium uppercase tracking-wider text-gray-500">
          등록 요약
        </h3>
        <ul className="space-y-2">
          {items.map((item) => (
            <li key={item.label} className="flex items-center justify-between">
              <span className="text-sm text-gray-400">{item.label}</span>
              <span className="flex items-center gap-1.5 text-sm">
                {item.done ? (
                  <span className="text-green-400">
                    <svg className="inline h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                  </span>
                ) : (
                  <span className="text-gray-600">
                    <svg className="inline h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 12H4" />
                    </svg>
                  </span>
                )}
                <span className={item.done ? 'text-white' : 'text-gray-600'}>{item.value}</span>
              </span>
            </li>
          ))}
        </ul>
      </div>

      {serverError && (
        <div className="mb-4 rounded-lg border border-red-800/50 bg-red-950/30 p-3">
          <p className="text-sm text-red-400">{serverError}</p>
        </div>
      )}

      <button
        type="button"
        onClick={handleStart}
        disabled={isPending || registeredUserId === null}
        className="w-full rounded-lg bg-green-600 px-4 py-3 text-sm font-medium text-white transition-colors hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-green-500 disabled:opacity-50"
      >
        {isPending ? '처리 중...' : 'magicJar 시작하기 →'}
      </button>
    </div>
  )
}
