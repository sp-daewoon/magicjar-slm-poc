/**
 * SettingsSection — 설정 섹션 공통 wrapper
 *
 * ADR 0069 §2-2: SettingsModal의 각 section을 감싸는 header + 본문 + status badge.
 * section마다 독립 mutation (글로벌 저장 버튼 없음 — ADR 0069 §6 정합).
 */
import type { ReactNode } from 'react'

export type SectionStatus = 'idle' | 'saving' | 'saved' | 'error'

interface Props {
  /** 섹션 제목 */
  title: string
  /** 섹션 부가 설명 */
  description?: string
  /** 현재 저장 상태 — section 내부 mutation 상태 연동 */
  status?: SectionStatus
  /** 에러 메시지 (status='error' 시) */
  errorMessage?: string
  children: ReactNode
}

function StatusBadge({ status, errorMessage }: { status: SectionStatus; errorMessage?: string }) {
  if (status === 'idle') return null

  const styles: Record<Exclude<SectionStatus, 'idle'>, string> = {
    saving: 'bg-yellow-900/40 text-yellow-400',
    saved: 'bg-green-900/40 text-green-400',
    error: 'bg-red-900/40 text-red-400',
  }

  const labels: Record<Exclude<SectionStatus, 'idle'>, string> = {
    saving: '저장 중...',
    saved: '저장됨',
    error: errorMessage ?? '저장 실패',
  }

  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${styles[status as Exclude<SectionStatus, 'idle'>]}`}>
      {labels[status as Exclude<SectionStatus, 'idle'>]}
    </span>
  )
}

export function SettingsSection({
  title,
  description,
  status = 'idle',
  errorMessage,
  children,
}: Props) {
  return (
    <section className="px-6 py-5">
      {/* 섹션 헤더 */}
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold text-white">{title}</h3>
          {description && (
            <p className="mt-0.5 text-xs text-gray-500">{description}</p>
          )}
        </div>
        <StatusBadge status={status} errorMessage={errorMessage} />
      </div>

      {/* 섹션 본문 */}
      {children}
    </section>
  )
}
