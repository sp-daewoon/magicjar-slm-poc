/**
 * SettingsModal — 운영 변경 화면 modal (ADR 0074 §8 풀세트)
 *
 * 진입점: AppHeader ⚙️ 버튼 클릭
 * 닫기: 우상단 X 버튼 / Esc 키 / overlay click
 *
 * 5 section (W4 R2: 추세 분석 설정 합류):
 *   Section 1: RealTradingConsentSection (실거래 동의 — 이중 confirm UX)
 *   Section 2: KisCredentialSection (KIS 키 변경 풀세트 — ADR 0074 §2 submit 활성화)
 *   Section 3: SystemConfigSection (DART + Slack — fully functional)
 *   Section 4: KillSwitchSection (자본 보호 — ADR 0074 §3 신규)
 *   Section 5: RealtimeFallbackPolicySection (자동매매 데이터 정책)
 *   Section 6: TrendPreferenceSection (W4 T-MARKET — ADR 0101)
 *
 * Section 7 (AiCredentialSection): 환경변수 기반 운영 — 본 PR scope 외
 *
 * overlay z-index: z-[60] — AppHeader(z-50) 위
 * panel: max-w-2xl + max-h-[85vh] overflow-y-auto
 *
 * ADR 0069 §6 UX 룰 + ADR 0074 §8 UX 매트릭스 정합.
 */
import { useModalBehavior } from '../../hooks/useModalBehavior'
import { KisCredentialSection } from './sections/KisCredentialSection'
import { KillSwitchSection } from './sections/KillSwitchSection'
import { RealTradingConsentSection } from './sections/RealTradingConsentSection'
import { RealtimeFallbackPolicySection } from './sections/RealtimeFallbackPolicySection'
import { SystemConfigSection } from './sections/SystemConfigSection'
import { TrendPreferenceSection } from '../../features/settings/components/TrendPreferenceSection'

interface Props {
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

export function SettingsModal({ onClose }: Props) {
  // Esc 닫기 + body scroll lock (공용 훅 — useModalBehavior · ADR 0069 §6 UX 룰)
  useModalBehavior({ open: true, onClose })

  function handleOverlayClick(e: React.MouseEvent<HTMLDivElement>) {
    // panel 클릭 이벤트가 올라오지 않도록 panel에서 stopPropagation 처리됨
    // overlay 클릭 시 단순 닫기 (1차 PR — form dirty 추적은 ADR 0069 §7 §J 후속)
    if (e.target === e.currentTarget) onClose()
  }

  return (
    /* overlay */
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black/70 p-4"
      onClick={handleOverlayClick}
      role="dialog"
      aria-modal="true"
      aria-label="설정"
    >
      {/* panel */}
      <div
        className="w-full max-w-2xl max-h-[85vh] overflow-y-auto rounded-2xl border border-gray-800 bg-gray-900 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <header className="sticky top-0 z-10 flex items-center justify-between border-b border-gray-800 bg-gray-900 px-6 py-4">
          <h2 className="text-lg font-semibold text-white">설정</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="설정 닫기"
            className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-gray-600"
          >
            <XIcon />
          </button>
        </header>

        {/* 섹션 컨테이너 — divide-y border */}
        <div className="divide-y divide-gray-800">
          {/* Section 1: 실거래 동의 */}
          <RealTradingConsentSection />

          {/* Section 2: KIS 키 관리 */}
          <KisCredentialSection />

          {/* Section 3: 시스템 설정 */}
          <SystemConfigSection />

          {/* Section 4: 자본 보호 (KillSwitch) — ADR 0074 §3 신규 */}
          <KillSwitchSection />

          {/* Section 5: 자동매매 데이터 정책 */}
          <RealtimeFallbackPolicySection />

          {/* Section 6: 추세 분석 설정 — W4 T-MARKET (ADR 0101) */}
          <TrendPreferenceSection />
        </div>
      </div>
    </div>
  )
}
