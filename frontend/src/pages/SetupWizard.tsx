/**
 * SetupWizard — KIS 키 등록 마법사 메인 컴포넌트
 *
 * spec §5 (마법사 7-step 계약 LOCK) 정합:
 * - Step 1 (내부): KisRealStep  — KIS 실전 키 입력
 * - Step 2 (내부): KisValidatingStep — 백엔드 검증 진행
 * - Step 3 (내부): KisMockStep  — 모의계좌 추가 (선택)
 * - Step 4 (내부): SystemConfigStep — DART + Slack (선택)
 * - Step 5 (내부): CompleteStep — 셋업 완료 요약 + 시작
 *
 * 진행률: (currentStep - 1) / 5 * 100% (spec §5-10)
 * Zustand store: 각 step의 전이 상태 관리 (spec §5-9)
 */
import { useOnboardingStore } from '../stores/onboarding'
import type { KisValidateRequest } from '../api/onboarding'
import { KisRealStep } from './setup/steps/KisRealStep'
import { KisValidatingStep } from './setup/steps/KisValidatingStep'
import { KisMockStep } from './setup/steps/KisMockStep'
import { SystemConfigStep } from './setup/steps/SystemConfigStep'
import { CompleteStep } from './setup/steps/CompleteStep'
import { useState } from 'react'

/** 내부 step 인덱스 (1~5) */
type InternalStep = 1 | 2 | 3 | 4 | 5

const TOTAL_STEPS = 5

const STEP_LABELS: Record<InternalStep, string> = {
  1: 'KIS 실전 키 입력',
  2: '검증 중',
  3: '모의계좌 (선택)',
  4: '시스템 설정 (선택)',
  5: '완료',
}

export function SetupWizard() {
  const [internalStep, setInternalStep] = useState<InternalStep>(1)
  const [kisPayload, setKisPayload] = useState<KisValidateRequest | null>(null)

  const registeredUserId = useOnboardingStore((s) => s.registeredUserId)
  const setValidationError = useOnboardingStore((s) => s.setValidationError)

  // 진행률 계산 (spec §5-10)
  const progressPercent = ((internalStep - 1) / (TOTAL_STEPS - 1)) * 100

  function goTo(step: InternalStep) {
    setInternalStep(step)
  }

  /** KisRealStep → KisValidatingStep 전이 */
  function handleKisRealNext(data: KisValidateRequest) {
    setKisPayload(data)
    goTo(2)
  }

  /** KisValidatingStep 검증 성공 → KisMockStep */
  function handleValidationSuccess() {
    setValidationError(null)
    goTo(3)
  }

  /** KisValidatingStep 검증 실패 → KisRealStep (에러 메시지 표시) */
  function handleValidationFailure(message: string) {
    setValidationError(message)
    goTo(1)
  }

  /** KisMockStep → SystemConfigStep */
  function handleMockNext() {
    goTo(4)
  }

  /** SystemConfigStep "저장 후 다음" → CompleteStep */
  function handleSystemConfigNext() {
    goTo(5)
  }

  /** SystemConfigStep "건너뛰기" → CompleteStep */
  function handleSystemConfigSkip() {
    goTo(5)
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-950 px-4 py-8">
      {/* 헤더 */}
      <div className="mb-8 text-center">
        <div className="mb-2 text-4xl font-bold tracking-tight text-white">
          magic<span className="text-blue-400">Jar</span>
        </div>
        <p className="text-sm text-gray-400">셋업 마법사</p>
      </div>

      {/* 메인 카드 */}
      <div className="w-full max-w-lg rounded-2xl border border-gray-800 bg-gray-900 p-8 shadow-xl">
        {/* step 헤더 */}
        <div className="mb-4 flex items-center justify-between">
          <span className="text-xs font-medium uppercase tracking-wider text-gray-500">
            {STEP_LABELS[internalStep]}
          </span>
          <span className="text-xs text-gray-600">
            {internalStep} / {TOTAL_STEPS}
          </span>
        </div>

        {/* 진행률 바 (spec §5-10) */}
        <div className="mb-6 h-1.5 w-full overflow-hidden rounded-full bg-gray-800">
          <div
            className="h-full rounded-full bg-blue-500 transition-all duration-300"
            style={{ width: `${progressPercent}%` }}
          />
        </div>

        {/* Step 컴포넌트 렌더링 */}
        {internalStep === 1 && (
          <KisRealStep onNext={handleKisRealNext} />
        )}

        {internalStep === 2 && kisPayload && (
          <KisValidatingStep
            payload={kisPayload}
            onSuccess={handleValidationSuccess}
            onFailure={handleValidationFailure}
          />
        )}

        {internalStep === 3 && registeredUserId !== null && (
          <KisMockStep userId={registeredUserId} onNext={handleMockNext} />
        )}

        {internalStep === 4 && (
          <SystemConfigStep onNext={handleSystemConfigNext} onSkip={handleSystemConfigSkip} />
        )}

        {internalStep === 5 && (
          <CompleteStep />
        )}
      </div>

      <p className="mt-6 text-xs text-gray-700">v3.0.1 · T-ONBOARD</p>
    </div>
  )
}
