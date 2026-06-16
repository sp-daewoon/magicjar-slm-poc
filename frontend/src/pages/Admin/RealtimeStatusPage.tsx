/**
 * RealtimeStatusPage — /admin/realtime 실시간 구독 상태 관리자 화면
 *
 * 최고 관리자 결재: "내가 그 상태를 확인 못 하니깐 장애 처리가 어렵다."
 * → KIS WS 실시간 구독 상태를 한 화면에서 가시화해 장애 판독을 돕는다.
 *
 * ### 구성 (5 섹션)
 *  ① 상단 상태 배너   — WS 연결 · stale · 체결통보. 셋 중 하나라도 비정상이면 빨간 강조.
 *  ② 슬롯 게이지       — used/max + trId 별 분해.
 *  ③ 구독 테이블       — trId · 종목 · owner(색 배지) · since.
 *  ④ 캔들 신선도       — ageSeconds > 180 이면 경고색 (시세 유입 정지 의심).
 *  ⑤ 컨트롤            — 5초 auto-refresh + 수동 새로고침 + 마지막 갱신 시각.
 *
 * ### 장애 판독 보조 (짧은 한국어 힌트)
 *  - stale            → "consumer가 상태를 못 쓰고 있음 — 컨테이너 점검"
 *  - WS 끊김          → "KIS WS 미연결 — 시세·체결 유입 정지"
 *  - 체결통보 미구독  → "체결 통보 구독 끊김 — 주문 체결 알림 정지"
 *  - 캔들 정체        → "시세 유입 정지 의심 — 15:20 이후는 마감 동시호가로 정상"
 *
 * ### 데이터 흐름
 *  - useRealtimeStatus (REST polling 5s) — consumer 상태 스냅샷 단일 출처.
 *  - mock 합성 0 — 빈/에러 상태 정직 표기.
 *
 * ### 디자인 정합
 *  - magicJar 다크 테마 (gray-950 base + gray-800 + blue-400 accent)
 *  - SystemEventPage 컨테이너 패턴 정합 (mx-auto + max-w-7xl + px-4 py-6)
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
import { useRealtimeStatus, REALTIME_STATUS_POLL_MS } from '../../api/hooks/useRealtimeStatus'
import { RealtimeStatusBanner } from '../../components/admin/RealtimeStatusBanner'
import { RealtimeSlotGauge } from '../../components/admin/RealtimeSlotGauge'
import { RealtimeSubscriptionTable } from '../../components/admin/RealtimeSubscriptionTable'
import { RealtimeCandleFreshnessPanel } from '../../components/admin/RealtimeCandleFreshnessPanel'

/** 5초 → 사용자 표시용 (auto-refresh 라벨) */
const POLL_SECONDS = Math.round(REALTIME_STATUS_POLL_MS / 1000)

export function RealtimeStatusPage() {
  const { data, isLoading, isError, error, isFetching, refetch, dataUpdatedAt } =
    useRealtimeStatus()

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      {/* 헤더 + 컨트롤 (⑤) */}
      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white">실시간 구독 상태</h1>
          <p className="mt-1 text-sm text-gray-400">
            KIS WS 연결 · 슬롯 · 구독 종목 · 캔들 신선도 — 장애 처리용 관리자 화면
          </p>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1.5">
          <button
            type="button"
            onClick={() => refetch()}
            disabled={isFetching}
            className="inline-flex items-center gap-2 rounded-lg border border-gray-700 bg-gray-900 px-3.5 py-2 text-sm text-gray-200 transition-colors hover:border-gray-600 hover:text-white focus:outline-none focus:ring-2 focus:ring-blue-400 disabled:opacity-50"
            aria-label="실시간 상태 새로고침"
          >
            <span
              className={['text-base leading-none', isFetching ? 'animate-spin' : ''].join(' ')}
              aria-hidden
            >
              ↻
            </span>
            새로고침
          </button>
          <p className="text-[11px] text-gray-500">
            {POLL_SECONDS}초마다 자동 갱신
            {dataUpdatedAt > 0 && (
              <>
                {' · '}
                <span className="text-gray-400">
                  최근 fetch {new Date(dataUpdatedAt).toLocaleTimeString('ko-KR')}
                </span>
              </>
            )}
          </p>
        </div>
      </div>

      {/* 로딩 (최초 1회만 — polling refetch 중에는 직전 데이터 유지) */}
      {isLoading ? (
        <div className="rounded-lg border border-gray-800 bg-gray-900/40 p-12 text-center text-sm text-gray-500">
          상태를 불러오는 중...
        </div>
      ) : isError ? (
        /* 에러 — 정직 표기 (mock 합성 0). 상태 API 자체가 죽었으면 consumer/api 점검. */
        <div className="rounded-lg border border-red-800 bg-red-950/40 p-6">
          <p className="text-sm font-semibold text-red-300">상태 조회 실패</p>
          <p className="mt-1 text-xs text-red-400/90">
            {error instanceof Error ? error.message : 'unknown error'}
          </p>
          <p className="mt-3 text-xs text-gray-400">
            힌트: /api/admin/realtime-status 응답이 없습니다 — api/consumer 컨테이너 또는 네트워크를
            점검하세요. {POLL_SECONDS}초 뒤 자동 재시도합니다.
          </p>
        </div>
      ) : data ? (
        <div className="space-y-6">
          {/* ① 상단 상태 배너 */}
          <RealtimeStatusBanner
            stale={data.stale}
            updatedAt={data.updatedAt}
            ws={data.ws}
            executionNotice={data.executionNotice}
          />

          {/* ② 슬롯 게이지 */}
          <RealtimeSlotGauge slots={data.slots} />

          {/* ③ 구독 테이블 */}
          <RealtimeSubscriptionTable subscriptions={data.subscriptions} />

          {/* ④ 캔들 신선도 */}
          <RealtimeCandleFreshnessPanel items={data.candleFreshness} />
        </div>
      ) : null}
    </div>
  )
}
