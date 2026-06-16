/**
 * RealTradingConsentSection — 실거래 동의 섹션
 *
 * ADR 0069 §3 Section 1: SettingsModal 내 mount 위치.
 * 기존 RealTradingConsentToggle 컴포넌트를 SettingsSection으로 감싸 노출.
 * 컴포넌트 내부 변경 0건 (1차 PR scope — ADR 0069 §7 §B 후속 PR에서 ConfirmDangerToggle 추출).
 *
 * T-O 자본 보호 검증 unblock prereq (ADR 0068 §16-4 dogfooding 케이스 1·2·3).
 */
import { SettingsSection } from '../SettingsSection'
import { RealTradingConsentToggle } from '../RealTradingConsentToggle'

export function RealTradingConsentSection() {
  return (
    <SettingsSection
      title="실거래 모드 동의"
      description="실거래 모드 활성화 시 KIS 실계좌 prod endpoint가 호출됩니다. 자본 손실 위험이 있습니다."
    >
      <RealTradingConsentToggle />
    </SettingsSection>
  )
}
