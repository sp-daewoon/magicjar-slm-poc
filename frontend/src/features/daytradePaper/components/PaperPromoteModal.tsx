/**
 * PaperPromoteModal — 발굴 후보 다중선택 → MOCK 페이퍼 일괄 승격 확인 모달 (ADR 0122 §8-1 LOCK)
 *
 * 발굴 화면에서 사용자가 선택한 종목(codes) + setup(presetKey)을 묶어 페이퍼 전략 1개로 승격.
 * 가상 자본 override(선택) + 명시적 "모의로 굴려보기" 확인 → POST /api/daytrade/paper/promote.
 *
 * ### 가드 (방향성 보존 — ADR 0122)
 * - 자동 진입 0 — 이 모달 확인 버튼(사용자 명시 액션)으로만 promote 호출.
 * - 페이퍼 ≠ 실거래 — 모달 전반 모의 배지 + "실거래 아님" 명시(§6).
 * - 가상 자본은 가상 ₩ 표기. 빈 입력이면 ENV default(1천만원).
 */
import { useState } from 'react'
import { useToast } from '../../../components/ui/ToastProvider'
import { PaperBadge } from './PaperBadge'
import { useDaytradePaperPromote } from '../hooks/useDaytradePaper'

interface PaperPromoteModalProps {
  userId: number
  /** 발굴 빌트인 setup key (presetKey 없는 '전체' 모드면 승급 불가 — 호출부 가드) */
  presetKey: string
  /** setup 표시명 (모달 안내용) */
  presetTitle: string
  /** 사용자가 선택한 종목 코드 (다중) */
  codes: string[]
  /** 종목명 lookup (code → name) — 표시용 */
  codeNames: Record<string, string>
  /** 평가 인터벌 (발굴 화면과 동일, default 1d) */
  interval?: string
  onClose: () => void
  /** 승격 성공 후 (선택 초기화 등) */
  onPromoted?: () => void
}

/** 기본 가상 자본 안내값 (ENV default 1천만원 — spec §8-2. 실제 적용은 backend 권위) */
const DEFAULT_CAPITAL_HINT = 10_000_000

export function PaperPromoteModal({
  userId,
  presetKey,
  presetTitle,
  codes,
  codeNames,
  interval = '1d',
  onClose,
  onPromoted,
}: PaperPromoteModalProps) {
  const { showToast } = useToast()
  const promote = useDaytradePaperPromote()

  // 가상 자본 override (빈 문자열 = ENV default)
  const [capitalInput, setCapitalInput] = useState('')

  function handleConfirm() {
    const trimmed = capitalInput.trim()
    let virtualCapitalKrw: number | null = null
    if (trimmed.length > 0) {
      const parsed = Number(trimmed.replace(/,/g, ''))
      if (!Number.isFinite(parsed) || parsed <= 0) {
        showToast('가상 자본 금액이 올바르지 않습니다.', 'warning')
        return
      }
      virtualCapitalKrw = parsed
    }

    promote.mutate(
      { userId, presetKey, codes, interval, virtualCapitalKrw },
      {
        onSuccess: (dto) => {
          showToast(
            `모의 전략 #${dto.strategyId} 생성됨 — ${dto.codes.length}종목 (가상 ₩${dto.virtualCapital.toLocaleString()})`,
            'success',
          )
          onPromoted?.()
          onClose()
        },
        onError: (err) => {
          showToast(`모의 승격 실패: ${err.message}`, 'error')
        },
      },
    )
  }

  const isPending = promote.isPending

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="모의로 굴려보기 확인"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-xl border border-amber-700/50 bg-gray-900 p-5 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-base font-bold text-white">모의로 굴려보기</h2>
          <PaperBadge />
        </div>

        <p className="mb-4 text-xs leading-relaxed text-gray-400">
          선택한 종목을 빌트인 setup{' '}
          <span className="font-medium text-gray-200">{presetTitle}</span> 으로 묶어 가상 자본
          기준의 모의(페이퍼) 전략으로 만듭니다. 실제 주문·실 계좌 영향은 없습니다(실거래 아님).
        </p>

        {/* 선택 종목 요약 */}
        <div className="mb-4">
          <p className="mb-1.5 text-xs font-medium text-gray-400">
            선택 종목 ({codes.length})
          </p>
          <div className="flex max-h-28 flex-wrap gap-1.5 overflow-y-auto rounded-lg border border-gray-800 bg-gray-950/60 p-2">
            {codes.map((code) => (
              <span
                key={code}
                className="rounded bg-gray-800 px-2 py-0.5 text-xs text-gray-300"
              >
                {codeNames[code] ?? code}
                <span className="ml-1 font-mono text-[10px] text-gray-500">{code}</span>
              </span>
            ))}
          </div>
        </div>

        {/* 가상 자본 override */}
        <div className="mb-5">
          <label className="mb-1.5 block text-xs font-medium text-gray-400" htmlFor="paper-capital">
            가상 자본 (원) — 비우면 기본 ₩{DEFAULT_CAPITAL_HINT.toLocaleString()}
          </label>
          <input
            id="paper-capital"
            type="text"
            inputMode="numeric"
            value={capitalInput}
            onChange={(e) => setCapitalInput(e.target.value)}
            placeholder={DEFAULT_CAPITAL_HINT.toLocaleString()}
            className="w-full rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-white placeholder:text-gray-600 focus:border-amber-500 focus:outline-none"
          />
          <p className="mt-1 text-[11px] text-gray-600">
            가상 자본 기준으로 사이징·리스크를 평가합니다. 실 잔고와 격리됩니다.
          </p>
        </div>

        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={isPending}
            className="rounded-lg border border-gray-700 px-4 py-2 text-sm text-gray-300 hover:bg-gray-800 disabled:opacity-50"
          >
            취소
          </button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={isPending || codes.length === 0}
            className="rounded-lg bg-amber-600 px-4 py-2 text-sm font-semibold text-white hover:bg-amber-700 disabled:cursor-not-allowed disabled:bg-gray-700 disabled:text-gray-500"
            aria-busy={isPending}
          >
            {isPending ? '승격 중...' : '모의로 굴려보기'}
          </button>
        </div>
      </div>
    </div>
  )
}
