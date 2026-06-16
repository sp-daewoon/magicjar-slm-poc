/**
 * RealtimeStatusBanner — 실시간 상태 상단 배너 (① 섹션)
 *
 * 3개 핵심 신호를 카드 3개로 나란히 표시:
 *  - WS 연결 (ws.connected)
 *  - 상태 신선도 (stale)
 *  - 체결 통보 (executionNotice.subscribed)
 *
 * 셋 중 하나라도 비정상이면:
 *  - 배너 컨테이너 빨간 강조 테두리 + 상단 경고 줄
 *  - 해당 카드 빨간색 + 짧은 한국어 장애 판독 힌트
 *
 * 모두 정상이면 녹색 톤 + "정상" 요약.
 */
import type { RealtimeExecutionNotice, RealtimeWsStatus } from '../../api/adminRealtime'
import { trIdDisplay } from '../../constants/kisTrId'

/**
 * 체결 통보 mode → KIS WS trId 추론.
 * 실전 계좌(REAL)는 H0STCNI0, 모의(MOCK/VPS)는 H0STCNI9 채널을 구독한다.
 * mode 미상이면 null (병기 생략).
 */
function executionNoticeTrId(mode: string | null): string | null {
  if (!mode) return null
  const m = mode.toUpperCase()
  if (m.includes('REAL') || m.includes('PROD')) return 'H0STCNI0'
  if (m.includes('MOCK') || m.includes('VPS') || m.includes('VIRTUAL')) return 'H0STCNI9'
  return null
}

interface Props {
  stale: boolean
  updatedAt: string
  ws: RealtimeWsStatus
  executionNotice: RealtimeExecutionNotice
}

/** 단일 상태 카드 */
function StatusCard({
  label,
  ok,
  value,
  hint,
  detail,
}: {
  label: string
  ok: boolean
  value: string
  /** 비정상일 때만 노출되는 한국어 장애 판독 힌트 */
  hint?: string
  /** 보조 메타 (정상/비정상 무관 표시) */
  detail?: string
}) {
  return (
    <div
      className={[
        'rounded-lg border p-4',
        ok
          ? 'border-emerald-800/70 bg-emerald-950/30'
          : 'border-red-700 bg-red-950/40',
      ].join(' ')}
      data-testid={`status-card-${label}`}
      data-ok={ok}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium uppercase tracking-wide text-gray-400">
          {label}
        </span>
        <span
          className={[
            'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold',
            ok ? 'bg-emerald-900/60 text-emerald-300' : 'bg-red-900/70 text-red-200',
          ].join(' ')}
        >
          <span aria-hidden>{ok ? '●' : '▲'}</span>
          {ok ? '정상' : '비정상'}
        </span>
      </div>
      <p
        className={[
          'mt-2 text-lg font-bold',
          ok ? 'text-emerald-200' : 'text-red-200',
        ].join(' ')}
      >
        {value}
      </p>
      {detail && <p className="mt-0.5 text-xs text-gray-400">{detail}</p>}
      {!ok && hint && (
        <p className="mt-2 rounded bg-red-950/60 px-2 py-1.5 text-xs text-red-300">
          {hint}
        </p>
      )}
    </div>
  )
}

export function RealtimeStatusBanner({ stale, updatedAt, ws, executionNotice }: Props) {
  // 상태 신선도: stale=true 가 비정상
  const staleOk = !stale
  // WS: connected=true 가 정상
  const wsOk = ws.connected
  // 체결 통보: subscribed=true 가 정상
  const noticeOk = executionNotice.subscribed

  const anyAbnormal = !staleOk || !wsOk || !noticeOk

  // 체결 통보 채널 trId (실전 H0STCNI0 / 모의 H0STCNI9) — mode 기반 추론
  const noticeTrId = executionNoticeTrId(executionNotice.mode)
  const noticeChannelLabel = noticeTrId ? trIdDisplay(noticeTrId) : null

  return (
    <section
      aria-label="실시간 상태 요약"
      className={[
        'rounded-xl border p-4',
        anyAbnormal
          ? 'border-red-600/80 bg-red-950/20 ring-1 ring-red-600/40'
          : 'border-gray-800 bg-gray-900/40',
      ].join(' ')}
      data-testid="realtime-banner"
      data-abnormal={anyAbnormal}
    >
      {anyAbnormal && (
        <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-red-300">
          <span aria-hidden>⚠</span>
          실시간 경로에 비정상 신호가 있습니다 — 아래 빨간 항목을 확인하세요.
        </div>
      )}

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <StatusCard
          label="WS 연결"
          ok={wsOk}
          value={wsOk ? '연결됨' : '끊김'}
          detail={
            wsOk
              ? `세션 ${ws.sessionCid ?? '-'} · 24h 재연결 ${ws.reconnects24h}회`
              : ws.lastConnectedAt
                ? `마지막 연결 ${new Date(ws.lastConnectedAt).toLocaleString('ko-KR')}`
                : '연결 이력 없음'
          }
          hint="KIS WS 미연결 — 시세·체결 유입 정지. consumer WS 세션·KIS 키·네트워크 점검."
        />

        <StatusCard
          label="상태 신선도"
          ok={staleOk}
          value={staleOk ? '최신' : '갱신 정지'}
          detail={`갱신 ${new Date(updatedAt).toLocaleString('ko-KR')}`}
          hint="consumer가 상태를 못 쓰고 있음 — 컨테이너 점검 (consumer 다운/멈춤 의심)."
        />

        <StatusCard
          label="체결 통보"
          ok={noticeOk}
          value={noticeOk ? '구독 중' : '미구독'}
          detail={
            noticeOk
              ? `${noticeChannelLabel ?? executionNotice.mode ?? '-'} · ${executionNotice.userIdMasked ?? '-'}`
              : '체결 알림 경로 없음'
          }
          hint="체결 통보 구독 끊김 — 주문 체결 알림 정지. 실주문 체결이 화면에 안 뜰 수 있음."
        />
      </div>
    </section>
  )
}
