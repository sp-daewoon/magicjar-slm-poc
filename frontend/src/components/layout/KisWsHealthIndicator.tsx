/**
 * KisWsHealthIndicator — consumer↔KIS WS 헬스 상태 헤더 표시 (사용자 dogfooding 2026-05-20)
 *
 * 정합:
 *  - WsConnectionIndicator(STOMP) 옆에 나란히 표시 — visual style 1:1 정합
 *  - useKisWsHealthState 단일 hook 의존 (SystemEvent → KIS WS 상태 판정 + toast는 hook 내부)
 *
 * 표시 규약 (사용자 명세):
 *  - CONNECTED:    🟢 emerald dot + "KIS WS" (text-emerald-300)
 *  - DISCONNECTED: 🔴 red dot + "KIS WS 끊김" + spinner (text-red-300)
 *  - UNKNOWN:      🟡 yellow dot + "KIS WS ?" (text-yellow-300)
 *
 * 접근성:
 *  - role="status" + aria-label="KIS WebSocket 상태: {상태}"
 *  - title(hover tooltip) — 상세 안내
 *
 * 룰 5: LLM provider import 0건
 */
import { useKisWsHealthState, type KisWsHealthState } from '../../api/hooks/useKisWsHealthState'

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
      data-testid="kis-ws-indicator-dot"
      data-color={color}
      className={`inline-block h-2 w-2 rounded-full shadow-[0_0_6px] ${colorClass}`}
    />
  )
}

/** 작은 회전 spinner — DISCONNECTED 상태 시각 보조 */
function Spinner() {
  return (
    <svg
      aria-hidden="true"
      data-testid="kis-ws-indicator-spinner"
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

export function decideKisWsDisplayState(state: KisWsHealthState): DisplayState {
  switch (state) {
    case 'CONNECTED':
      return {
        color: 'green',
        label: 'KIS WS',
        ariaLabel: 'KIS WebSocket 상태: 연결됨',
        title: 'KIS WebSocket 연결 정상 — 시세·호가·체결통보 수신 중 (consumer↔KIS)',
        showSpinner: false,
      }
    case 'DISCONNECTED':
      return {
        color: 'red',
        label: 'KIS WS 끊김',
        ariaLabel: 'KIS WebSocket 상태: 끊김',
        title: 'KIS WebSocket 연결이 끊어졌습니다 — 시세·호가·체결통보 영향 (자동 재연결 시도 중)',
        showSpinner: true,
      }
    case 'UNKNOWN':
    default:
      return {
        color: 'yellow',
        label: 'KIS WS ?',
        ariaLabel: 'KIS WebSocket 상태: 알 수 없음',
        title:
          'KIS WebSocket 상태 확인 불가 — STOMP 끊김 또는 초기 데이터 로드 중 (상태 push 도착 대기)',
        showSpinner: false,
      }
  }
}

export function KisWsHealthIndicator() {
  const state = useKisWsHealthState()
  const display = decideKisWsDisplayState(state)

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
      data-testid="kis-ws-health-indicator"
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
