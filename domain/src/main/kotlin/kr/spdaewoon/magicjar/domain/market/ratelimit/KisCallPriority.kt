package kr.spdaewoon.magicjar.domain.market.ratelimit

/**
 * KIS REST 호출 path 우선순위 분류 (spec PR #564 §2-2 + ADR 0037 amend 2026-05-24 LOCK).
 *
 * Phase 1: 정보 수집만 — 모든 priority 동일 bucket (단일 LOCK).
 * Phase 2: USER_PATH > INGESTION > BACKFILL > INGESTION_LOW 우선순위 큐 적용 (별도 사이클).
 *
 * sealed class 대신 enum 선택 사유:
 *   - Phase 1은 분류 라벨 LOCK (인스턴스 상태 0)
 *   - log·통계 라벨 친화 (`name` property 직접 사용)
 *   - when 분기 exhaustive 강제 — 신규 우선순위 추가 시 빌드 게이트
 */
enum class KisCallPriority {
    /**
     * 사용자 직접 path — 현재가·차트·주문 등.
     * Phase 2에서 우선 처리 (다른 path 대기 중에도 즉시 acquire).
     */
    USER_PATH,

    /**
     * Watchlist Ingestion (1~5초 polling) + 정기 cron.
     * Phase 2에서 중간 우선순위.
     */
    INGESTION,

    /**
     * ACTIVE 전략 degraded candle feed 등 장중 실시간성 보강 REST.
     * USER_PATH(주문·잔고)보다는 낮고, 일반 ingestion/backfill보다는 높게 보호한다.
     */
    REALTIME_MARKET,

    /**
     * Backfill·corp action·reconcile (백그라운드 batch).
     * Phase 2에서 최후 우선순위 (사용자 path 방해 0).
     */
    BACKFILL,

    /**
     * T-MARKET 시장 추세 cron (1분 cron, 26 req/min · 4.3% 한도 점유 · ADR 0100 §2-3 LOCK).
     *
     * 우선순위 사유 (ADR 0037 amend 2026-05-24 + ADR 0100 §2-8):
     *   - 사용자 path 보다 느린 user-facing latency 허용 (1분 지연 OK)
     *   - 정기 시장 추세 갱신 본질 — burst 시 BACKFILL 보다 먼저 drop 가능 (사용자·자본 path 영향 0)
     *   - 본 priority의 5 KIS endpoint:
     *     · FHPUP02100000 inquire-index-price (국내 지수·업종 현재가)
     *     · FHPUP02120000 inquire-index-daily-price (국내 일봉)
     *     · FHPUP02140000 inquire-index-category-price (국내 섹터 ranking)
     *     · HHDFS00000300 overseas price (해외 지수 현재가 .IXIC/.SPX)
     *     · HHDFS76240000 overseas dailyprice (해외 일봉)
     *
     * Phase 2 라우팅 LOCK: USER_PATH > INGESTION > BACKFILL > INGESTION_LOW
     */
    INGESTION_LOW,
}
