/**
 * SecretKeyInput — 비밀 키 입력 공통 컴포넌트
 *
 * ADR 0046 §2-5 보안 정책 강제:
 * - type="password" (화면 마스킹)
 * - autoComplete="off" (브라우저 저장 방지)
 * - unmount 시 state + DOM value 모두 zero-fill
 *
 * 사용처: KisCredentialForm (appKey·secretKey), SystemConfigForm (dartApiKey)
 */
import { useEffect, useRef } from 'react'

interface Props {
  id?: string
  label: string
  value: string
  onChange: (value: string) => void
  placeholder?: string
  error?: string
  required?: boolean
  disabled?: boolean
  className?: string
}

export function SecretKeyInput({
  id,
  label,
  value,
  onChange,
  placeholder = '',
  error,
  required = false,
  disabled = false,
  className = '',
}: Props) {
  const inputRef = useRef<HTMLInputElement>(null)

  // unmount 시 민감 데이터 메모리 + DOM 초기화 (ADR 0046 §2-5 LOCK)
  useEffect(() => {
    return () => {
      onChange('')
      if (inputRef.current) {
        inputRef.current.value = ''
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className={className}>
      <label className="mb-1 block text-sm font-medium text-gray-300" htmlFor={id}>
        {label}
        {required && <span className="ml-1 text-red-400">*</span>}
      </label>
      <input
        ref={inputRef}
        id={id}
        type="password"
        autoComplete="off"
        placeholder={placeholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        className={[
          'w-full rounded-lg border px-3 py-2 text-sm text-white placeholder-gray-600 bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50',
          error ? 'border-red-600' : 'border-gray-700',
        ].join(' ')}
      />
      {error && <p className="mt-1 text-xs text-red-400">{error}</p>}
    </div>
  )
}
