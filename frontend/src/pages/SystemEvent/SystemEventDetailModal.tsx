/**
 * SystemEventDetailModal — 시스템 이벤트 단건 상세 모달.
 *
 * 사용자 보고(2026-06-12, 최고 관리자): Detail 컬럼 JSON 이 잘려 안 보임 →
 * 셀 클릭 시 이벤트 전체(시각·심각도·카테고리·actor·action·correlation·detail JSON pretty)
 * + 복사 버튼(장애 보고 붙여넣기용).
 *
 * 설계:
 *   - ScreenerResultModal·SettingsModal 정합: useModalBehavior(ESC·scroll lock) + overlay click 닫기.
 *   - detail JSON pretty-print 는 prettyJson 유틸 재사용.
 *   - 복사는 navigator.clipboard → useToast 피드백. clipboard 미지원 환경 graceful fallback.
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
import { useCallback, useState } from 'react'
import { useModalBehavior } from '../../hooks/useModalBehavior'
import { useOptionalToast } from '../../components/ui/ToastProvider'
import { prettyJson } from '../../lib/prettyJson'
import type { SystemEvent, SystemEventSeverity } from '../../api/system-event'

const SEVERITY_BADGE_CLASS: Record<SystemEventSeverity, string> = {
  INFO: 'bg-blue-950 text-blue-300 border-blue-800',
  WARN: 'bg-amber-950 text-amber-300 border-amber-800',
  ERROR: 'bg-orange-950 text-orange-300 border-orange-800',
  CRITICAL: 'bg-red-950 text-red-300 border-red-700 font-bold',
}

interface Props {
  event: SystemEvent
  onClose: () => void
}

function XIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-5 w-5"
      aria-hidden="true"
    >
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  )
}

/** 라벨 + 값 한 행 */
function MetaRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-3 py-1.5 text-sm">
      <span className="w-28 flex-shrink-0 text-gray-500">{label}</span>
      <span className="min-w-0 flex-1 break-all text-gray-200">{children}</span>
    </div>
  )
}

/**
 * 이벤트 전체를 장애 보고용 평문으로 직렬화 (복사 대상).
 */
function buildClipboardText(event: SystemEvent): string {
  const { text: detailPretty } = prettyJson(event.detailJson)
  return [
    `[시스템 이벤트 #${event.id}]`,
    `발생 시각: ${new Date(event.occurredAt).toLocaleString('ko-KR')} (${event.occurredAt})`,
    `카테고리: ${event.category}`,
    `심각도: ${event.severity}`,
    `Actor: ${event.actor}`,
    `Action: ${event.action}`,
    `사용자: ${event.userId ?? '(전체)'}`,
    `Correlation: ${event.correlationId ?? '-'}`,
    `확인 시각: ${event.viewedAt ?? '(미확인)'}`,
    `해결 시각: ${event.resolvedAt ?? '(미해결)'}`,
    `Detail:`,
    detailPretty || '(없음)',
  ].join('\n')
}

export function SystemEventDetailModal({ event, onClose }: Props) {
  const [copied, setCopied] = useState(false)
  const { showToast } = useOptionalToast()

  useModalBehavior({ open: true, onClose })

  const { text: detailPretty, isJson } = prettyJson(event.detailJson)

  const handleOverlayClick = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (e.target === e.currentTarget) onClose()
    },
    [onClose],
  )

  const handleCopy = useCallback(async () => {
    const payload = buildClipboardText(event)
    try {
      if (navigator?.clipboard?.writeText) {
        await navigator.clipboard.writeText(payload)
      } else {
        // 구형/비보안 컨텍스트 fallback
        const ta = document.createElement('textarea')
        ta.value = payload
        ta.style.position = 'fixed'
        ta.style.opacity = '0'
        document.body.appendChild(ta)
        ta.select()
        document.execCommand('copy')
        document.body.removeChild(ta)
      }
      setCopied(true)
      showToast('이벤트 상세를 클립보드에 복사했습니다.', 'success')
      setTimeout(() => setCopied(false), 2000)
    } catch {
      showToast('복사에 실패했습니다. 직접 선택해 복사해 주세요.', 'error')
    }
  }, [event, showToast])

  return (
    <div
      className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-4"
      onClick={handleOverlayClick}
      role="dialog"
      aria-modal="true"
      aria-label="시스템 이벤트 상세"
    >
      <div
        className="flex w-full max-w-2xl flex-col rounded-2xl border border-gray-800 bg-gray-900 shadow-xl"
        style={{ maxHeight: '90vh' }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <header className="flex flex-shrink-0 items-center justify-between gap-3 border-b border-gray-800 px-6 py-4">
          <div className="flex min-w-0 items-center gap-3">
            <h2 className="text-lg font-semibold text-white">시스템 이벤트 상세</h2>
            <span
              className={[
                'inline-flex rounded border px-2 py-0.5 text-xs',
                SEVERITY_BADGE_CLASS[event.severity],
              ].join(' ')}
            >
              {event.severity}
            </span>
            <span className="text-xs text-gray-500">#{event.id}</span>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-gray-600"
          >
            <XIcon />
          </button>
        </header>

        {/* 본문 */}
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto px-6 py-4">
          {/* 메타 */}
          <div className="mb-4 divide-y divide-gray-800/60 rounded-lg border border-gray-800 bg-gray-950/40 px-4 py-2">
            <MetaRow label="발생 시각">
              {new Date(event.occurredAt).toLocaleString('ko-KR')}
            </MetaRow>
            <MetaRow label="카테고리">{event.category}</MetaRow>
            <MetaRow label="Action">
              <span className="font-mono">{event.action}</span>
            </MetaRow>
            <MetaRow label="Actor">{event.actor}</MetaRow>
            <MetaRow label="사용자">
              {event.userId ?? <span className="italic text-gray-600">(전체)</span>}
            </MetaRow>
            <MetaRow label="Correlation">
              <span className="font-mono">{event.correlationId ?? '-'}</span>
            </MetaRow>
            <MetaRow label="확인 시각">
              {event.viewedAt
                ? new Date(event.viewedAt).toLocaleString('ko-KR')
                : <span className="italic text-gray-600">(미확인)</span>}
            </MetaRow>
            <MetaRow label="해결 시각">
              {event.resolvedAt
                ? new Date(event.resolvedAt).toLocaleString('ko-KR')
                : <span className="italic text-gray-600">(미해결)</span>}
            </MetaRow>
          </div>

          {/* Detail */}
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-medium text-gray-300">
              Detail{isJson && <span className="ml-2 text-xs text-gray-500">JSON</span>}
            </h3>
            <button
              type="button"
              onClick={handleCopy}
              className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-300 transition-colors hover:border-gray-600 hover:bg-gray-800 hover:text-white focus:outline-none focus:ring-2 focus:ring-blue-400"
            >
              {copied ? '✓ 복사됨' : '전체 복사'}
            </button>
          </div>
          <pre
            data-testid="system-event-detail-pre"
            className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-all rounded-lg border border-gray-800 bg-gray-950 p-3 font-mono text-xs leading-relaxed text-gray-200"
          >
            {detailPretty || '(detail 없음)'}
          </pre>
        </div>

        {/* 푸터 */}
        <footer className="flex flex-shrink-0 justify-end border-t border-gray-800 px-6 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-gray-700 bg-gray-900 px-4 py-2 text-sm text-gray-300 transition-colors hover:border-gray-600 hover:text-white focus:outline-none focus:ring-2 focus:ring-blue-400"
          >
            닫기
          </button>
        </footer>
      </div>
    </div>
  )
}
