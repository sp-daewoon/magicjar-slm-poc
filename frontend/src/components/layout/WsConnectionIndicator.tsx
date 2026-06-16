/**
 * WsConnectionIndicator — STOMP WS 접속 상태 헤더 영구 표시 (사용자 결재 2026-05-20)
 *
 * 정합:
 *  - RuntimeFlagsIndicator(모의/실전 dot LED) 옆에 나란히 표시 — visual style 통일
 *  - useStompConnectionState 단일 hook 의존 (콜백 chain 보존 패턴은 hook 내부)
 *  - useStompReconnect의 backfill toast와 별개 책임 — 본 indicator는 즉시성 안내
 *
 * 표시 규약:
 *  - CONNECTED:    🟢 emerald dot + "WS 연결됨" (text-emerald-300)
 *  - CONNECTING:   🟡 yellow dot + "연결 중..." (text-yellow-300)
 *  - DISCONNECTED: 🔴 red dot + "끊김" (text-red-300) + 작은 spinner ("재연결 중...")
 *
 * 접근성:
 *  - role="status" + aria-label="WS 접속 상태: {상태}"
 *  - title(hover tooltip) — 상세 안내
 *
 * 룰 5: LLM provider import 0건
 */
import { useStompConnectionState, type StompConnectionState } from '../../api/hooks/useStompConnectionState'

type DotColor = 'green' | 'red' | 'yellow'

interface DisplayState {
  color: DotColor
  label: string
  ariaLabel: string
  title: string
  showSpinner: boolean
}

function DotLED({ color }: { color: DotColor }) {
  const colorClass =
    color === 'green'
      ? 'bg-emerald-400 shadow-emerald-400/40'
      : color === 'red'
        ? 'bg-red-500 shadow-red-500/40'
        : 'bg-yellow-400 shadow-yellow-400/40'
  return (
    <span
      aria-hidden="true"
      data-testid="ws-indicator-dot"
      data-color={color}
      className={`inline-block h-2 w-2 rounded-full shadow-[0_0_6px] ${colorClass}`}
    />
  )
}

/** 작은 회전 spinner — DISCONNECTED 또는 CONNECTING 상태 시각 보조 */
function Spinner() {
  return (
    <svg
      aria-hidden="true"
      data-testid="ws-indicator-spinner"
      className="h-3 w-3 animate-spin text-current opacity-70"
      viewBox="0 0 24 24"
      fill="none"
    >
      <circle
        className="opacity-25"
        cx="12"
        cy="12"
        r="10"
        stroke="currentColor"
        strokeWidth="4"
      />
      <path
        className="opacity-75"
        fill="currentColor"
        d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"
      />
    </svg>
  )
}

export function decideWsDisplayState(state: StompConnectionState): DisplayState {
  switch (state) {
    case 'CONNECTED':
      return {
        color: 'green',
        label: 'WS 연결됨',
        ariaLabel: 'WS 접속 상태: 연결됨',
        title: 'WebSocket 연결 정상 — 실시간 시세·시그널·주문 이벤트 수신 중',
        showSpinner: false,
      }
    case 'DISCONNECTED':
      return {
        color: 'red',
        label: '끊김',
        ariaLabel: 'WS 접속 상태: 끊김',
        title: 'WebSocket 연결이 끊어졌습니다 — 자동 재연결 시도 중 (5초 간격)',
        showSpinner: true,
      }
    case 'CONNECTING':
    default:
      return {
        color: 'yellow',
        label: '연결 중...',
        ariaLabel: 'WS 접속 상태: 연결 중',
        title: 'WebSocket 연결 시도 중 — 잠시 기다려주세요',
        showSpinner: true,
      }
  }
}

export function WsConnectionIndicator() {
  const state = useStompConnectionState()
  const display = decideWsDisplayState(state)

  const textColorClass =
    display.color === 'green'
      ? 'text-emerald-300'
      : display.color === 'red'
        ? 'text-red-300'
        : 'text-yellow-300'

  return (
    <div
      role="status"
      aria-label={display.ariaLabel}
      title={display.title}
      data-testid="ws-connection-indicator"
      data-state={state}
      className="flex items-center gap-1.5 rounded-md border border-gray-800 bg-gray-900/60 px-2 py-1 text-xs"
    >
      <DotLED color={display.color} />
      <span className={`font-medium ${textColorClass}`}>{display.label}</span>
      {display.showSpinner && (
        <span className={textColorClass}>
          <Spinner />
        </span>
      )}
    </div>
  )
}
