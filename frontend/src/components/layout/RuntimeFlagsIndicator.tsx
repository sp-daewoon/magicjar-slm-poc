/**
 * RuntimeFlagsIndicator — 헤더 dot LED + 모드 라벨 (P2-2 frontend)
 *
 * 사용자가 현재 보안 상태를 한 눈에 파악할 수 있도록 AppHeader에 표시.
 *
 * 표시 규약:
 *  - MOCK 모드:          녹색 dot + "모의" 라벨 — 안전한 학습/테스트 상태
 *  - REAL + 모든 게이트 OK: 빨강 dot + "실전" 라벨 — 실주문 가능 상태
 *  - REAL + ALLOW_REAL=false: 노랑 dot + "실전·차단" — 환경변수 미허용
 *  - userConsented=false (REAL): 노랑 + 경고 아이콘 — 동의 토글 미체결
 *  - KillSwitch ON:       회색 dot + "정지" 라벨 — 모든 주문 차단
 *
 * 우선순위: KillSwitch > consent/allowReal 경고 > REAL/MOCK 본 모드
 *
 * hover/click 시 툴팁 또는 상세 모달 가능 (MVP는 title 속성으로 간단 hover 텍스트).
 *
 * 실패 시 (backend 미구현·네트워크 fail): isError 인디케이터 회색 placeholder + title="런타임 상태 조회 실패"
 *
 * mock 합성 0 LOCK — feedback_no_mock_data.
 */
import { useRuntimeFlags } from '../../api/hooks/useRuntimeFlags'

type DotColor = 'green' | 'red' | 'yellow' | 'gray'

interface DisplayState {
  color: DotColor
  label: string
  showWarn: boolean
  title: string
}

function DotLED({ color }: { color: DotColor }) {
  const colorClass =
    color === 'green'
      ? 'bg-green-400 shadow-green-400/40'
      : color === 'red'
        ? 'bg-red-500 shadow-red-500/40'
        : color === 'yellow'
          ? 'bg-yellow-400 shadow-yellow-400/40'
          : 'bg-gray-500 shadow-gray-500/40'
  return (
    <span
      aria-hidden="true"
      className={`inline-block h-2 w-2 rounded-full shadow-[0_0_6px] ${colorClass}`}
    />
  )
}

function WarnIcon() {
  return (
    <svg
      className="h-3.5 w-3.5 text-yellow-400"
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth={2}
        d="M12 9v3.75m0 3.75h.008v.008H12v-.008zM6.343 19h11.314c1.5 0 2.43-1.624 1.69-2.928L13.69 5.072c-.75-1.304-2.63-1.304-3.38 0L4.653 16.072C3.913 17.376 4.843 19 6.343 19z"
      />
    </svg>
  )
}

interface RuntimeFlagsIndicatorProps {
  userId?: string | number | null
}

export function RuntimeFlagsIndicator({ userId }: RuntimeFlagsIndicatorProps) {
  const { data, isLoading, isError } = useRuntimeFlags(userId)

  // 로딩 중 또는 backend 미구현 — 회색 placeholder
  if (isLoading || isError || !data) {
    const placeholderTitle = isError
      ? '런타임 상태 조회 실패 — backend /api/runtime/flags 미구현 가능'
      : '런타임 상태 로딩 중...'
    return (
      <div
        className="flex items-center gap-1.5 rounded-md border border-gray-800 bg-gray-900/60 px-2 py-1 text-xs text-gray-500"
        title={placeholderTitle}
        aria-label={placeholderTitle}
      >
        <DotLED color="gray" />
        <span>--</span>
      </div>
    )
  }

  const state = decideDisplayState(data)

  return (
    <div
      className="flex items-center gap-1.5 rounded-md border border-gray-800 bg-gray-900/60 px-2 py-1 text-xs"
      title={state.title}
      aria-label={state.title}
      role="status"
    >
      <DotLED color={state.color} />
      <span
        className={
          state.color === 'red'
            ? 'font-medium text-red-300'
            : state.color === 'yellow'
              ? 'font-medium text-yellow-300'
              : state.color === 'gray'
                ? 'font-medium text-gray-400'
                : 'font-medium text-green-300'
        }
      >
        {state.label}
      </span>
      {state.showWarn && <WarnIcon />}
    </div>
  )
}

/**
 * 우선순위 매트릭스 (불변 LOCK):
 *  1. killSwitchEnabled=true → 회색 "정지" — 모든 주문 차단
 *  2. mode=REAL && !allowReal → 노랑 "실전·차단" — MAGICJAR_ALLOW_REAL 환경변수 미허용
 *  3. mode=REAL && userConsented=false → 노랑 "실전·동의대기" + 경고 — 사용자 동의 토글 미체결
 *  4. mode=REAL && userConsented=null → 노랑 "실전·계좌확인" — userId 없이 조회되어 동의 판정 불가
 *  5. mode=REAL && allowReal && userConsented=true → 빨강 "실전" — 실주문 가능
 *  6. mode=MOCK → 녹색 "모의"
 */
export function decideDisplayState(flags: {
  mode: 'MOCK' | 'REAL'
  allowReal: boolean
  killSwitchEnabled: boolean
  killSwitchReason: string | null
  userConsented: boolean | null
}): DisplayState {
  if (flags.killSwitchEnabled) {
    return {
      color: 'gray',
      label: '정지',
      showWarn: false,
      title: `킬스위치 ON — 모든 주문 차단 (${flags.killSwitchReason ?? '사유 미기재'})`,
    }
  }

  if (flags.mode === 'REAL') {
    if (!flags.allowReal) {
      return {
        color: 'yellow',
        label: '실전·차단',
        showWarn: true,
        title:
          '실전 모드지만 MAGICJAR_ALLOW_REAL=false — 환경변수 검토 후 재기동 필요',
      }
    }
    if (flags.userConsented === false) {
      return {
        color: 'yellow',
        label: '실전·동의대기',
        showWarn: true,
        title:
          '실전 모드 활성 + ALLOW_REAL=true이지만 사용자 동의 미체결 — 설정 > 실거래 동의 토글 확인',
      }
    }
    if (flags.userConsented === null) {
      return {
        color: 'yellow',
        label: '실전·계좌확인',
        showWarn: true,
        title:
          '실전 모드 활성 + ALLOW_REAL=true이지만 userId 없이 조회되어 동의 상태를 판정하지 못했습니다',
      }
    }
    return {
      color: 'red',
      label: '실전',
      showWarn: false,
      title: '실전 모드 활성 — 3중 게이트 모두 통과 (실주문 가능)',
    }
  }

  // MOCK
  return {
    color: 'green',
    label: '모의',
    showWarn: false,
    title: '모의 모드 — 안전한 학습/테스트 상태 (KIS 모의계좌)',
  }
}
