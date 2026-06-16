/**
 * RealTradingConsentToggle — 실거래 동의 토글 (T-O Phase 3 — ADR 0068 §16-4)
 *
 * 이중 confirm UX (사용자 자본 보호 LOCK — §16-4):
 *   1차: confirm "실거래 모드를 활성화합니다. 실제 자본이 사용됩니다. 동의하십니까?"
 *   2차: confirm "재확인: 자본 손실 risk를 이해하고 동의합니다."
 *   두 번 모두 확인 시에만 PUT API 호출
 *
 * real_trading_consent_at:
 *   NULL     → 토글 OFF (REAL 주문 BLOCKED)
 *   NOT NULL → 토글 ON (3중 게이트 세 번째 조건 충족 — ADR 0068 의제 A-1)
 *
 * SettingsModal에 통합하여 사용.
 */
import { useOnboardingStore } from '../../stores/onboarding'
import { useRealTradingConsent, useUpdateRealTradingConsent } from '../../api/hooks/useUpdateRealTradingConsent'
import { useToast } from '../ui/ToastProvider'

function formatDate(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function RealTradingConsentToggle() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const { data: consent, isLoading } = useRealTradingConsent(userId)
  const { mutate, isPending } = useUpdateRealTradingConsent(userId)
  const { showToast } = useToast()

  const isOn = !!(consent?.realTradingConsentAt)

  function handleToggle(next: boolean) {
    // userId null 가드 — store sync 이전 엣지 케이스 방어 (dogfooding qa #2 fix)
    // ADR 0089 Phase 2 — userId number, null check 명시적
    if (userId === null) {
      showToast('사용자 인증 정보를 불러오는 중입니다. 잠시 후 다시 시도해 주세요.', 'error')
      return
    }

    if (next) {
      // 1차 confirm
      const first = window.confirm(
        '실거래 모드를 활성화합니다.\n\n' +
        '한국투자증권 실계좌 prod endpoint가 호출되어 실제 자본이 사용됩니다.\n' +
        '환경 변수 MAGICJAR_MODE=REAL + MAGICJAR_ALLOW_REAL=true 설정도 필수입니다.\n\n' +
        '자본 손실 위험을 인지하셨습니까?',
      )
      if (!first) return

      // 2차 confirm
      const second = window.confirm(
        '재확인: 자본 손실 risk를 이해하고 실거래 모드 동의를 진행합니다.\n\n' +
        '이 결정은 KIS 실계좌 주문 발행을 활성화합니다.\n\n' +
        '최종 동의하십니까?',
      )
      if (!second) return
    }

    mutate(
      { consented: next },
      {
        onSuccess: (data) => {
          if (data.realTradingConsentAt) {
            showToast('실거래 동의 완료. REAL 주문 게이트 조건 충족됨.', 'warning')
          } else {
            showToast('실거래 동의 해제. MOCK 모드로 전환됩니다.', 'info')
          }
        },
        onError: (err) => {
          showToast(`동의 토글 실패: ${err.message}`, 'error')
        },
      },
    )
  }

  if (isLoading) {
    return <div className="h-6 w-24 animate-pulse rounded bg-gray-800" />
  }

  return (
    <section className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      <h3 className="text-sm font-semibold text-white">실거래 모드</h3>
      <p className="mt-1 text-xs text-gray-400">
        실거래 모드는 한국투자증권 실계좌 자본을 사용합니다.
        이 토글을 ON 하기 전, 환경 변수{' '}
        <code className="rounded bg-gray-800 px-1 font-mono text-yellow-400">
          MAGICJAR_MODE=REAL
        </code>{' '}
        +{' '}
        <code className="rounded bg-gray-800 px-1 font-mono text-yellow-400">
          MAGICJAR_ALLOW_REAL=true
        </code>{' '}
        둘 다 설정되어야 합니다. 개발 단계는 OFF 유지 권장.
      </p>

      <div className="mt-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          {isOn ? (
            <span className="text-xs font-bold text-red-400">REAL ON</span>
          ) : (
            <span className="text-xs text-gray-500">OFF (MOCK)</span>
          )}
        </div>

        {/* 토글 버튼 */}
        <button
          type="button"
          role="switch"
          aria-checked={isOn}
          disabled={isPending}
          onClick={() => handleToggle(!isOn)}
          className={[
            'relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-red-400 focus:ring-offset-2 focus:ring-offset-gray-900 disabled:cursor-not-allowed disabled:opacity-50',
            isOn ? 'bg-red-600' : 'bg-gray-700',
          ].join(' ')}
        >
          <span
            className={[
              'pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out',
              isOn ? 'translate-x-5' : 'translate-x-0',
            ].join(' ')}
          />
        </button>
      </div>

      {/* 동의 시각 표시 */}
      {isOn && consent?.realTradingConsentAt && (
        <p className="mt-2 text-xs text-red-400">
          동의 시각: {formatDate(consent.realTradingConsentAt)}
        </p>
      )}

      {/* 경고 문구 (ON 상태) */}
      {isOn && (
        <div className="mt-3 rounded-lg border border-red-900/50 bg-red-950/30 px-3 py-2">
          <p className="text-xs font-medium text-red-400">
            주의: 실거래 모드 활성화 상태입니다. KIS 실계좌 주문이 발행될 수 있습니다.
          </p>
        </div>
      )}
    </section>
  )
}
