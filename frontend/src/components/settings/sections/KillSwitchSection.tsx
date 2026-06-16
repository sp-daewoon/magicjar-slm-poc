/**
 * KillSwitchSection — 자본 보호 KillSwitch 수동 토글 섹션 (ADR 0074 §3 신규)
 *
 * SettingsModal Section 4: 자본 보호
 *
 * 기능:
 * - GET /api/system-config/kill-switch → 현재 상태 표시 (ON/OFF 점 + 사유 + 토글 시각 + 주체)
 * - 토글 버튼 + ConfirmDangerToggle (이중 confirm + 사유 입력 + 20초 cooldown)
 * - 사유 minLength=5 maxLength=64 (backend VARCHAR(64) 정합)
 * - 20초 cooldown (ADR 0074 §3-2-2 frontend 채택 — 트레이드오프 (j); 2026-05-20 60→20 단축, 사용자 UX dogfooding)
 *
 * 상태별 UX (ADR 0074 §3-2-2 매트릭스 정합):
 * - enabled=true (활성): 빨간 점 + "활성 (ON)" + 사유 + 토글 시각 + 주체 + [KillSwitch OFF 해제] 버튼
 * - enabled=false (비활성): 회색 점 + "비활성 (OFF)" + 마지막 OFF 시각 + [KillSwitch ON] 버튼
 *
 * mock 합성 0 (메모리 feedback_no_mock_data.md 정합):
 * - 초기 row 없을 때: "최초 토글 없음" 표시 + ON 버튼만 노출 (가상 상태 X)
 *
 * AI 호출 0 (개발 룰 5 + ADR 0074 §11-2 정합)
 */
import { useToast } from '../../ui/ToastProvider'
import { SettingsSection } from '../SettingsSection'
import { ConfirmDangerToggle } from '../forms/ConfirmDangerToggle'
import { useKillSwitchState } from '../../../api/queries/useKillSwitchState'
import { useToggleKillSwitch } from '../../../api/queries/useToggleKillSwitch'

function formatKst(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function formatUpdatedBy(updatedBy: string): string {
  if (updatedBy === 'AUTO') return '자동 (시스템)'
  if (updatedBy.startsWith('USER:')) return `사용자 (${updatedBy.slice(5)})`
  if (updatedBy === '(없음)') return '기록 없음'
  return updatedBy
}

export function KillSwitchSection() {
  const { data, isLoading, isError } = useKillSwitchState()
  const { mutate, isPending } = useToggleKillSwitch()
  const { showToast } = useToast()

  function handleToggle(targetEnabled: boolean, reason: string) {
    mutate(
      { enabled: targetEnabled, reason },
      {
        onSuccess: () => {
          if (targetEnabled) {
            showToast('KillSwitch 활성화 완료 — 자동 매매가 중단됩니다.', 'warning')
          } else {
            showToast('KillSwitch 해제 완료 — 자동 매매가 재개됩니다.', 'success')
          }
        },
        onError: (err) => {
          const msg = err.message || 'KillSwitch 토글에 실패했습니다.'
          showToast(`토글 실패: ${msg}`, 'error')
        },
      },
    )
  }

  // 로딩 중
  if (isLoading) {
    return (
      <SettingsSection
        title="자본 보호"
        description="KillSwitch 수동 토글 — 자동 매매 긴급 중단 및 재개."
      >
        <div className="space-y-2">
          <div className="h-16 animate-pulse rounded-lg bg-gray-800" />
          <div className="h-8 w-32 animate-pulse rounded-lg bg-gray-800" />
        </div>
      </SettingsSection>
    )
  }

  // 에러 (REST 실패 — 페이지 전체 중단 금지)
  if (isError) {
    return (
      <SettingsSection
        title="자본 보호"
        description="KillSwitch 수동 토글 — 자동 매매 긴급 중단 및 재개."
        status="error"
        errorMessage="KillSwitch 상태를 불러오지 못했습니다"
      >
        <p className="text-xs text-red-400">
          서버와 연결에 실패했습니다. 페이지를 새로 고치거나 잠시 후 다시 시도해 주세요.
        </p>
      </SettingsSection>
    )
  }

  const isEnabled = data?.enabled ?? false
  const reason = data?.reason ?? null
  const updatedAt = data?.updatedAt ?? null
  const updatedBy = data?.updatedBy ?? '(없음)'

  return (
    <SettingsSection
      title="자본 보호"
      description="KillSwitch 수동 토글 — 자동 매매 긴급 중단 및 재개. (ADR 0074 §3)"
    >
      {/* 현재 상태 표시 */}
      <div className="mb-4 rounded-lg border border-gray-800 bg-gray-900/50 px-4 py-4 space-y-2">
        {/* 상태 헤더 */}
        <div className="flex items-center gap-2">
          {isEnabled ? (
            <>
              <span className="inline-block h-2.5 w-2.5 rounded-full bg-red-500 ring-2 ring-red-500/30" aria-hidden="true" />
              <span className="text-sm font-semibold text-red-400">활성 (ON) — 자동 매매 중단 중</span>
            </>
          ) : (
            <>
              <span className="inline-block h-2.5 w-2.5 rounded-full bg-gray-500" aria-hidden="true" />
              <span className="text-sm font-medium text-gray-400">비활성 (OFF) — 자동 매매 정상</span>
            </>
          )}
        </div>

        {/* 상태 상세 */}
        <div className="space-y-1 text-xs text-gray-500">
          {reason ? (
            <div className="flex gap-1">
              <span className="text-gray-600">사유:</span>
              <span className="text-gray-400">{reason}</span>
            </div>
          ) : (
            <div className="text-gray-600">최초 토글 없음</div>
          )}
          {updatedAt && (
            <div className="flex gap-1">
              <span className="text-gray-600">토글 시각:</span>
              <span className="text-gray-400">{formatKst(updatedAt)}</span>
            </div>
          )}
          <div className="flex gap-1">
            <span className="text-gray-600">토글 주체:</span>
            <span className="text-gray-400">{formatUpdatedBy(updatedBy)}</span>
          </div>
        </div>
      </div>

      {/* 활성 상태 경고 */}
      {isEnabled && (
        <div className="mb-4 rounded-lg border border-red-900/50 bg-red-950/30 px-3 py-2">
          <p className="text-xs font-medium text-red-400">
            KillSwitch 활성 상태입니다. 자동 매매가 중단되었습니다.
            KIS HTS에서 잔고를 직접 확인 후 OFF 해제하세요.
          </p>
        </div>
      )}

      {/* 토글 버튼 (ConfirmDangerToggle wrap) */}
      {isEnabled ? (
        <ConfirmDangerToggle
          actionLabel="KillSwitch OFF 해제"
          warningMessage={
            'KillSwitch를 해제하면 자동 매매가 재개됩니다.\n' +
            'KIS HTS에서 잔고를 직접 확인한 경우에만 해제하세요.\n' +
            '20초 대기 후 사유 입력 시 확인됩니다.'
          }
          reasonPlaceholder="해제 사유 (예: KIS HTS 잔고 확인 완료 — 일치)"
          onConfirm={(reason) => handleToggle(false, reason)}
          isPending={isPending}
          buttonVariant="danger"
          cooldownSeconds={20}
        />
      ) : (
        <ConfirmDangerToggle
          actionLabel="KillSwitch ON 활성화"
          warningMessage={
            'KillSwitch를 활성화하면 자동 매매가 즉시 중단됩니다.\n' +
            '긴급 상황 또는 시스템 점검 시에만 사용하세요.\n' +
            '20초 대기 후 사유 입력 시 확인됩니다.'
          }
          reasonPlaceholder="활성화 사유 (예: 긴급 매매 중단 — 시스템 점검)"
          onConfirm={(reason) => handleToggle(true, reason)}
          isPending={isPending}
          buttonVariant="warning"
          cooldownSeconds={20}
        />
      )}

      {/* 안내 문구 */}
      <p className="mt-3 text-xs text-gray-600">
        KillSwitch OFF 해제는 ADR 0043 §2-3 정합 — 사용자 수동만 가능. 자동 재개 없음.
      </p>
    </SettingsSection>
  )
}
