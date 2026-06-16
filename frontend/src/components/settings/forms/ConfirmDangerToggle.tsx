/**
 * ConfirmDangerToggle — 위험 동작 이중 confirm + 사유 입력 컴포넌트 (실구현)
 *
 * ADR 0074 §8 정합 — KillSwitchSection + 향후 RealTradingConsentSection reuse 가능
 *
 * UX 흐름 (ADR 0074 §3-2-2 이중 confirm + 사유 입력 LOCK):
 *   idle     → [액션 버튼 클릭] → confirming
 *   confirming → [정말 변경하시겠습니까? "예" 클릭] → reasoning
 *   reasoning  → [사유 입력 (minLength=5) + 20초 cooldown 완료] → [확인] → onConfirm(reason) 호출
 *   confirming → [취소] → idle
 *   reasoning  → [취소] → idle
 *
 * 20초 cooldown (ADR 0074 §3-2-2 + frontend 채택 — §12 트레이드오프 (j)):
 *   - 2026-05-20 dogfooding 결과 60초 → 20초 단축 (사용자 UX 답답함 해소).
 *     이중 confirm + 사유 입력 5자 강제 의도치 않은 토글 방지 layer는 그대로.
 *   - reasoning 단계 진입 시 타이머 시작
 *   - 20초 남은 시간 카운트다운 표시
 *   - 20초 경과 + 사유 5자 이상 시 "확인" 버튼 활성화
 *
 * Props:
 *   actionLabel — 초기 버튼 텍스트 (예: "KillSwitch OFF 해제")
 *   warningMessage — 경고 메시지 (1차 confirm 패널 표시)
 *   reasonPlaceholder — 사유 입력 placeholder
 *   onConfirm(reason) — 이중 confirm + 사유 + cooldown 완료 시 호출
 *   isPending — 외부 mutation 진행 중 여부
 *   buttonVariant — 'danger' | 'warning' (색상 변형)
 *   cooldownSeconds — cooldown 초 (default 20, 테스트 환경에서 0 주입 가능)
 */
import { useEffect, useRef, useState } from 'react'

export interface ConfirmDangerToggleProps {
  actionLabel: string
  warningMessage: string
  reasonPlaceholder?: string
  onConfirm: (reason: string) => void
  isPending?: boolean
  buttonVariant?: 'danger' | 'warning'
  /** cooldown 초. default=20 (2026-05-20 60→20 단축, 사용자 UX dogfooding). 테스트 시 0 주입 가능 */
  cooldownSeconds?: number
}

type Stage = 'idle' | 'confirming' | 'reasoning'

export function ConfirmDangerToggle({
  actionLabel,
  warningMessage,
  reasonPlaceholder = '변경 사유를 입력해 주세요 (최소 5자)',
  onConfirm,
  isPending = false,
  buttonVariant = 'danger',
  cooldownSeconds = 20,
}: ConfirmDangerToggleProps) {
  const [stage, setStage] = useState<Stage>('idle')
  const [reason, setReason] = useState('')
  const [remaining, setRemaining] = useState(cooldownSeconds)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const isDanger = buttonVariant === 'danger'
  const primaryColor = isDanger
    ? 'bg-red-600 hover:bg-red-700 focus:ring-red-500'
    : 'bg-amber-600 hover:bg-amber-700 focus:ring-amber-500'
  const borderColor = isDanger ? 'border-red-800/50' : 'border-amber-800/50'
  const bgColor = isDanger ? 'bg-red-950/20' : 'bg-amber-950/20'
  const textColor = isDanger ? 'text-red-400' : 'text-amber-400'

  // reasoning 단계 진입 시 cooldown 타이머 시작
  useEffect(() => {
    if (stage === 'reasoning') {
      setRemaining(cooldownSeconds)
      if (cooldownSeconds <= 0) return

      timerRef.current = setInterval(() => {
        setRemaining((prev) => {
          if (prev <= 1) {
            if (timerRef.current) clearInterval(timerRef.current)
            return 0
          }
          return prev - 1
        })
      }, 1000)
    } else {
      if (timerRef.current) {
        clearInterval(timerRef.current)
        timerRef.current = null
      }
    }

    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current)
        timerRef.current = null
      }
    }
  }, [stage, cooldownSeconds])

  function handleCancel() {
    setStage('idle')
    setReason('')
    setRemaining(cooldownSeconds)
  }

  function handleFirstConfirm() {
    setStage('reasoning')
  }

  function handleFinalConfirm() {
    if (reason.trim().length < 5) return
    if (remaining > 0) return
    onConfirm(reason.trim())
    // 성공 후 UI 리셋 (onSuccess에서 refetch → 상태 자동 갱신)
    setStage('idle')
    setReason('')
  }

  const canSubmit = reason.trim().length >= 5 && remaining <= 0 && !isPending

  // ── idle 단계 ──
  if (stage === 'idle') {
    return (
      <button
        type="button"
        onClick={() => setStage('confirming')}
        disabled={isPending}
        className={[
          'rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-offset-gray-900 disabled:cursor-not-allowed disabled:opacity-50',
          primaryColor,
        ].join(' ')}
      >
        {actionLabel}
      </button>
    )
  }

  // ── confirming 단계 (1차 경고 + 확인 요청) ──
  if (stage === 'confirming') {
    return (
      <div className={`rounded-lg border ${borderColor} ${bgColor} p-4 space-y-3`}>
        <p className={`text-sm font-medium ${textColor}`}>
          정말 변경하시겠습니까?
        </p>
        <p className="text-xs text-gray-400 leading-relaxed">
          {warningMessage}
        </p>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={handleFirstConfirm}
            className={[
              'rounded-lg px-3 py-1.5 text-sm font-medium text-white transition-colors focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-offset-gray-900',
              primaryColor,
            ].join(' ')}
          >
            예, 계속합니다
          </button>
          <button
            type="button"
            onClick={handleCancel}
            className="rounded-lg border border-gray-700 px-3 py-1.5 text-sm text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-gray-600"
          >
            취소
          </button>
        </div>
      </div>
    )
  }

  // ── reasoning 단계 (사유 입력 + 20초 cooldown) ──
  return (
    <div className={`rounded-lg border ${borderColor} ${bgColor} p-4 space-y-3`}>
      <p className={`text-sm font-medium ${textColor}`}>
        변경 사유를 입력해 주세요
      </p>

      {/* 사유 입력 */}
      <textarea
        value={reason}
        onChange={(e) => {
          // maxLength=64 (backend VARCHAR(64) 정합)
          if (e.target.value.length <= 64) setReason(e.target.value)
        }}
        placeholder={reasonPlaceholder}
        rows={3}
        className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-white placeholder-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
      />
      <div className="flex justify-between text-xs text-gray-500">
        <span>
          {reason.trim().length < 5
            ? `최소 5자 필요 (현재 ${reason.trim().length}자)`
            : `${reason.trim().length}/64자`}
        </span>
        {/* 20초 cooldown 카운트다운 (2026-05-20 60→20 단축) */}
        {remaining > 0 ? (
          <span className={textColor}>
            {remaining}초 후 확인 가능
          </span>
        ) : (
          <span className="text-green-400">
            확인 가능
          </span>
        )}
      </div>

      {/* cooldown 안내 */}
      {remaining > 0 && (
        <div className="rounded-lg border border-gray-700 bg-gray-800/50 px-3 py-2">
          <p className="text-xs text-gray-400">
            의도치 않은 토글 방지를 위해 {cooldownSeconds}초 대기가 필요합니다.
            KIS HTS에서 잔고를 직접 확인 후 진행하세요.
          </p>
        </div>
      )}

      <div className="flex gap-2">
        <button
          type="button"
          onClick={handleFinalConfirm}
          disabled={!canSubmit}
          className={[
            'rounded-lg px-3 py-1.5 text-sm font-medium text-white transition-colors focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-offset-gray-900 disabled:cursor-not-allowed disabled:opacity-50',
            primaryColor,
          ].join(' ')}
        >
          {isPending ? '처리 중...' : '확인'}
        </button>
        <button
          type="button"
          onClick={handleCancel}
          disabled={isPending}
          className="rounded-lg border border-gray-700 px-3 py-1.5 text-sm text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-gray-600 disabled:opacity-50"
        >
          취소
        </button>
      </div>
    </div>
  )
}
