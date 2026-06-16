/**
 * KisCredentialForm — KIS 계좌번호 + App Key + Secret Key 입력 폼
 *
 * ADR 0069 §2-2 공통 입력 컴포넌트 매트릭스 정합.
 * SetupWizard (KisRealStep·KisMockStep) + SettingsModal (KisCredentialSection) 공유.
 *
 * - accountType='REAL': 실전 계좌 (SetupWizard Step 1 / SettingsModal REAL 변경)
 * - accountType='MOCK': 모의 계좌 (SetupWizard Step 3 / SettingsModal MOCK 추가·변경)
 * - 계좌번호 검증: /^\d{8}$/
 * - unmount 시 appKey·secretKey zero-fill (ADR 0046 §2-5)
 */
import { useEffect, useRef, useState } from 'react'
import { SecretKeyInput } from './SecretKeyInput'

export interface KisCredentialFormValues {
  accountNumber: string
  appKey: string
  secretKey: string
}

interface Props {
  accountType: 'REAL' | 'MOCK'
  /** 초기값 (SettingsModal에서 현재 등록된 계좌번호 pre-fill 시 사용) */
  initialAccountNumber?: string
  /** 계좌번호 수정 불가 여부 (SettingsModal REAL 변경 시 read-only) */
  accountNumberReadOnly?: boolean
  /** 폼 값 변경 콜백 */
  onChange?: (values: KisCredentialFormValues) => void
  /** 외부에서 에러 주입 (서버 검증 실패 등) */
  serverError?: string | null
  /** submit 핸들러 (폼 내부 submit 버튼 있을 경우) */
  onSubmit?: (values: KisCredentialFormValues) => void
  /** submit 버튼 텍스트 override */
  submitLabel?: string
  /** submit 버튼 loading 상태 */
  isSubmitting?: boolean
  /** submit 버튼 disabled override (Option A: 후속 backend PR 전까지 disabled) */
  submitDisabled?: boolean
  /** 버튼 없이 ref 제어만 필요할 때 (SetupWizard는 상위에서 onSubmit 직접 처리) */
  hideSubmitButton?: boolean
}

export function KisCredentialForm({
  accountType,
  initialAccountNumber = '',
  accountNumberReadOnly = false,
  onChange,
  serverError,
  onSubmit,
  submitLabel,
  isSubmitting = false,
  submitDisabled = false,
  hideSubmitButton = false,
}: Props) {
  const [accountNumber, setAccountNumber] = useState(initialAccountNumber)
  const [appKey, setAppKey] = useState('')
  const [secretKey, setSecretKey] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})

  const appKeyRef = useRef<HTMLInputElement>(null)
  const secretKeyRef = useRef<HTMLInputElement>(null)

  const isReal = accountType === 'REAL'
  const accountLabel = isReal ? '실전 계좌번호' : '모의 계좌번호'
  const accountPlaceholder = isReal ? '8자리 계좌번호 (예: 74683223)' : '8자리 모의 계좌번호'

  // 값 변경 시 상위 onChange 콜백 호출
  useEffect(() => {
    onChange?.({ accountNumber, appKey, secretKey })
  }, [accountNumber, appKey, secretKey, onChange])

  // unmount 시 민감 데이터 zero-fill (ADR 0046 §2-5 LOCK)
  useEffect(() => {
    return () => {
      setAppKey('')
      setSecretKey('')
      if (appKeyRef.current) appKeyRef.current.value = ''
      if (secretKeyRef.current) secretKeyRef.current.value = ''
    }
  }, [])

  function validate(): boolean {
    const newErrors: Record<string, string> = {}
    if (!accountNumberReadOnly && !/^\d{8}$/.test(accountNumber)) {
      newErrors.accountNumber = '계좌번호는 숫자 8자리여야 합니다'
    }
    if (!appKey.trim()) {
      newErrors.appKey = `${isReal ? '' : '모의 '}App Key를 입력해 주세요`
    }
    if (!secretKey.trim()) {
      newErrors.secretKey = `${isReal ? '' : '모의 '}Secret Key를 입력해 주세요`
    }
    setErrors(newErrors)
    return Object.keys(newErrors).length === 0
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!validate()) return
    const values: KisCredentialFormValues = { accountNumber, appKey, secretKey }
    onSubmit?.(values)
    // 제출 후 민감 데이터 즉시 초기화 (ADR 0046 §2-5)
    setAppKey('')
    setSecretKey('')
    if (appKeyRef.current) appKeyRef.current.value = ''
    if (secretKeyRef.current) secretKeyRef.current.value = ''
  }

  const defaultSubmitLabel = isReal
    ? isSubmitting ? '검증 중...' : '검증 시작 →'
    : isSubmitting ? '등록 중...' : '모의계좌 등록 →'

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {serverError && (
        <div className="rounded-lg border border-red-800/50 bg-red-950/30 p-3">
          <p className="text-sm text-red-400">{serverError}</p>
        </div>
      )}

      {/* 계좌번호 */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-300">
          {accountLabel} <span className="text-red-400">*</span>
        </label>
        {accountNumberReadOnly ? (
          <div className="w-full rounded-lg border border-gray-700 bg-gray-800/50 px-3 py-2 text-sm text-gray-400">
            {accountNumber || '등록된 계좌번호'}
          </div>
        ) : (
          <>
            <input
              type="text"
              autoComplete="off"
              placeholder={accountPlaceholder}
              maxLength={8}
              value={accountNumber}
              onChange={(e) => setAccountNumber(e.target.value.replace(/\D/g, ''))}
              className={[
                'w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500',
                errors.accountNumber ? 'border-red-600' : 'border-gray-700',
              ].join(' ')}
            />
            {errors.accountNumber && (
              <p className="mt-1 text-xs text-red-400">{errors.accountNumber}</p>
            )}
          </>
        )}
      </div>

      {/* App Key — SecretKeyInput 공통 컴포넌트 사용 */}
      <SecretKeyInput
        id={`${accountType}-appKey`}
        label={`KIS ${isReal ? '' : '모의 '}App Key`}
        value={appKey}
        onChange={(v) => {
          setAppKey(v)
          // ref sync (SecretKeyInput 내부 ref가 있으나 state 기반으로도 sync 필요)
        }}
        placeholder={isReal ? 'PS로 시작하는 앱 키' : '모의계좌 앱 키'}
        error={errors.appKey}
        required
      />

      {/* Secret Key — SecretKeyInput 공통 컴포넌트 사용 */}
      <SecretKeyInput
        id={`${accountType}-secretKey`}
        label={`KIS ${isReal ? '' : '모의 '}Secret Key`}
        value={secretKey}
        onChange={(v) => {
          setSecretKey(v)
        }}
        placeholder={isReal ? '시크릿 키' : '모의계좌 시크릿 키'}
        error={errors.secretKey}
        required
      />

      {/* 보안 안내 */}
      <div className="rounded-lg border border-amber-800/30 bg-amber-950/30 p-3">
        <p className="text-xs leading-relaxed text-amber-400/80">
          입력한 키는 전송 즉시 메모리에서 삭제됩니다.
          서버에는 AES-256-GCM 암호화 후 저장되며 로그에 노출되지 않습니다.
        </p>
      </div>

      {/* submit 버튼 */}
      {!hideSubmitButton && (
        <button
          type="submit"
          disabled={isSubmitting || submitDisabled}
          className="w-full rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {submitLabel ?? defaultSubmitLabel}
        </button>
      )}
    </form>
  )
}
