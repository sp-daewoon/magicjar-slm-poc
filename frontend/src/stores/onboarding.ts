/**
 * Zustand onboarding store
 *
 * spec §5-9 정합 — 마법사 step 추적용 클라이언트 상태
 * TanStack Query는 server state, Zustand는 wizard step 추적용으로 분리
 *
 * Hotfix 2026-05-14: persist middleware 추가 (localStorage 영속화).
 * 기존에 registeredUserId가 페이지 새로고침 시 즉시 null로 초기화되어 SetupWizard 완료
 * 후에도 모든 페이지(/strategy/list, /risk-config, /orders, /journal 등)가 셋업 안내
 * 모드로 진입하는 결함 발생. PR #386 userId 매트릭스 fix가 16+ 페이지에 userId null
 * 가드를 추가하면서 결함이 표면화됨.
 *
 * 진짜 single source는 server 응답이지만 OnboardingStatus DTO에 userId 없음 →
 * 그건 별도 마이그레이션(backlog §N — server-side single source)이고, 즉시 fix는
 * localStorage 영속화로 대응.
 *
 * ADR 0089 Phase 2 (2026-05-19): `registeredUserId` type `string | null` → `number | null` 통일.
 *   backend `Long` (DB BIGINT) ↔ frontend `number` 자연 정합 (MAX_SAFE_INTEGER 안전권).
 *   8자리 KIS 실전 계좌번호("74683223")는 number 74683223로 표현 — 안전권 내.
 *   localStorage persist payload: 기존 string 값 자동 마이그레이션 (`migrate` 콜백 + version bump).
 */
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'

type WizardStep = 1 | 2 | 3 | 4 | 5 | 6 | 7

interface OnboardingState {
  /** 현재 단계 (1=KisReal입력, 2=검증중, 3=KisMock, 4=SystemConfig, 5=Complete, 6=완료, 7=운영) */
  currentStep: WizardStep
  /** KIS 검증 후 반환된 userId (DB BIGINT PK = KIS 실전 계좌번호 8자리, ADR 0089 number 통일) */
  registeredUserId: number | null
  /** 모의계좌 등록 여부 */
  hasMockRegistered: boolean
  /** 시스템 설정(DART/Slack) 등록 여부 */
  hasSystemConfig: boolean
  /** 검증 실패 시 에러 메시지 (Step 1로 돌아갈 때 표시) */
  validationError: string | null

  // actions
  setStep: (step: WizardStep) => void
  setRegistered: (userId: number) => void
  setMockRegistered: () => void
  setSystemConfig: () => void
  setValidationError: (message: string | null) => void
  reset: () => void
}

const initialState = {
  currentStep: 1 as WizardStep,
  registeredUserId: null,
  hasMockRegistered: false,
  hasSystemConfig: false,
  validationError: null,
}

export const useOnboardingStore = create<OnboardingState>()(
  persist(
    (set) => ({
      ...initialState,

      setStep: (step) => set({ currentStep: step }),

      setRegistered: (userId) =>
        set({
          registeredUserId: userId,
          validationError: null,
        }),

      setMockRegistered: () => set({ hasMockRegistered: true }),

      setSystemConfig: () => set({ hasSystemConfig: true }),

      setValidationError: (message) => set({ validationError: message }),

      reset: () => set(initialState),
    }),
    {
      name: 'magicjar-onboarding',
      storage: createJSONStorage(() => localStorage),
      version: 2,
      partialize: (state) => ({
        registeredUserId: state.registeredUserId,
        hasMockRegistered: state.hasMockRegistered,
        hasSystemConfig: state.hasSystemConfig,
      }),
      /**
       * ADR 0089 Phase 2 — string → number 마이그레이션.
       * v1 이전: registeredUserId가 string("74683223") 영속.
       * v2 이후: number(74683223) 영속. 기존 string 값 발견 시 Number() 변환.
       * 변환 실패(NaN) 시 null로 reset — 다음 부팅 시 useOnboardingStatus 가 server에서 재주입.
       */
      migrate: (persistedState: unknown, version: number) => {
        if (version < 2 && persistedState && typeof persistedState === 'object') {
          const prev = persistedState as Record<string, unknown>
          if (typeof prev.registeredUserId === 'string') {
            const parsed = Number(prev.registeredUserId)
            prev.registeredUserId = Number.isFinite(parsed) ? parsed : null
          }
          return prev as Partial<OnboardingState>
        }
        return persistedState as Partial<OnboardingState>
      },
    },
  ),
)
