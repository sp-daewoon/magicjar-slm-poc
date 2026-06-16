/**
 * STOMP destination 중앙 레지스트리 (Phase 2 — 새 명명규칙 wire 플립 완료)
 *
 * magicJar STOMP 구독 destination 문자열이 14개 파일에 인라인 template literal로
 * 흩어져 있어 "fanout인지 source event인지" 헷갈림이 컸다. 이 모듈은 17개 패턴을
 * 한곳에 모아 빌더 함수/상수로 노출한다.
 *
 * ★ Phase 2 플립 (이 파일): 빌더 반환값을 새 명명규칙
 *   `/topic/stomp.{owner}.{domain}.{stream}[.{key}]` 로 교체했다. 출처는
 *   `docs/architecture/backup/realtime-topic-inventory.md`
 *   "STOMP Inventory & Rename Proposal" 표의 "Proposed destination" 컬럼이다.
 *
 * ★ 불변식 (lockstep): 모든 빌더 반환값 == 백엔드
 *   `api/.../messaging/StompDestinations.kt` 의 동일 멤버 반환값과 **글자단위 동일**.
 *   frontend·backend 가 동시(lockstep)로 플립되며, 한쪽만 바뀌면 구독 미스매치로
 *   실시간 push 가 0 이 된다.
 *
 * 각 멤버 위 주석:
 *   - 본문 = current(현행) 명명규칙
 *   - `legacyName:` = Phase 1 이전 옛 destination (추적성 — 롤백·디버깅 참조용)
 *
 * 동적 키({code}/{userId}/{requestId}/{jobId})는 인자로 받는 함수, 정적은 상수.
 */

// ──────────────────────────────────────────────────────────────
// Market — 시세·캔들·지수·섹터·선호도
// ──────────────────────────────────────────────────────────────

/** 현재가 tick — Kafka `market.tick.v1` fanout
 *  current: /topic/stomp.api.market.tick.{code}
 *  legacyName: /topic/market.tick.{code} */
export const marketTick = (code: string) => `/topic/stomp.api.market.tick.${code}`

/** 통합 호가 — Kafka `market.orderbook.v1` fanout
 *  current: /topic/stomp.api.market.orderbook.{code}
 *  legacyName: /topic/market.orderbook.{code} */
export const marketOrderbook = (code: string) =>
  `/topic/stomp.api.market.orderbook.${code}`

/** 1분봉 closed — Kafka `market.candle.minute.closed.v1` fanout
 *  current: /topic/stomp.api.market.candle.1m.{code}  ← minute → 1m
 *  legacyName: /topic/market.candle.minute.{code} */
export const marketCandleMinute = (code: string) =>
  `/topic/stomp.api.market.candle.1m.${code}`

/** boundary closed 캔들 (interval = 3m·5m·15m·30m·90m·1h·4h·12h)
 *  current: /topic/stomp.api.market.candle.{interval}.{code}
 *  legacyName: /topic/market.candle.{interval}.{code} */
export const marketCandle = (interval: string, code: string) =>
  `/topic/stomp.api.market.candle.${interval}.${code}`

/** 지수 트렌드 — market trend notifier (1분 cron)
 *  current: /topic/stomp.api.market.index.{code}
 *  legacyName: /topic/market.index.{code} */
export const marketIndex = (code: string) => `/topic/stomp.api.market.index.${code}`

/** 섹터 랭킹 — market trend notifier (5분 캐시) — 정적
 *  current: /topic/stomp.api.market.sector.rank
 *  legacyName: /topic/market.sector.rank */
export const marketSectorRank = '/topic/stomp.api.market.sector.rank'

/** 트렌드 선호도 sync — 다중 탭 동기화
 *  current: /topic/stomp.api.market.preference.user.{userId}  ← 구조 변경
 *  legacyName: /topic/market.user.{userId}.pref */
export const marketUserPref = (userId: string | number) =>
  `/topic/stomp.api.market.preference.user.${userId}`

// ──────────────────────────────────────────────────────────────
// Trading / Risk / Account — user-scoped
// ──────────────────────────────────────────────────────────────

/** 주문 이벤트 — Kafka `order.*.v1` fanout
 *  current: /topic/stomp.api.order.user.{userId}
 *  legacyName: /topic/order.user.{userId} */
export const orderUser = (userId: string | number) =>
  `/topic/stomp.api.order.user.${userId}`

/** 시그널 — Kafka `signal.generated.v1` fanout
 *  current: /topic/stomp.api.signal.user.{userId}
 *  legacyName: /topic/signal.user.{userId} */
export const signalUser = (userId: string | number) =>
  `/topic/stomp.api.signal.user.${userId}`

/** 리스크 위반 — Kafka `risk.violation.v1` fanout
 *  current: /topic/stomp.api.risk.user.{userId}
 *  legacyName: /topic/risk.user.{userId} */
export const riskUser = (userId: string | number) =>
  `/topic/stomp.api.risk.user.${userId}`

/** 계좌 잔고 변경 — Kafka `account.balance.changed.v1` fanout
 *  current: /topic/stomp.api.account.balance.user.{userId}  ← .user 삽입
 *  legacyName: /topic/account.balance.{userId} */
export const accountBalanceUser = (userId: string | number) =>
  `/topic/stomp.api.account.balance.user.${userId}`

/** 정합/킬스위치 — Kafka reconcile/kill switch fanout (현재 미구독, 정의만 추가)
 *  current: /topic/stomp.api.reconcile.user.{userId}  ← recon → reconcile
 *  legacyName: /topic/recon.user.{userId} */
export const reconUser = (userId: string | number) =>
  `/topic/stomp.api.reconcile.user.${userId}`

// ──────────────────────────────────────────────────────────────
// System — user-scoped + broadcast
// ──────────────────────────────────────────────────────────────

/** 시스템 이벤트 (사용자별) — Kafka `system.event.v1` fanout
 *  current: /topic/stomp.api.system.event.user.{userId}
 *  legacyName: /topic/system.event.user.{userId} */
export const systemEventUser = (userId: string | number) =>
  `/topic/stomp.api.system.event.user.${userId}`

/** 시스템 이벤트 (broadcast) — 정적
 *  current: /topic/stomp.api.system.event.broadcast
 *  legacyName: /topic/system.event.broadcast */
export const systemEventBroadcast = '/topic/stomp.api.system.event.broadcast'

// ──────────────────────────────────────────────────────────────
// AI — request-scoped
// ──────────────────────────────────────────────────────────────

/** AI 코멘트 응답 — Kafka `ai.comment.responded.v1` fanout (requestId당 1회)
 *  current: /topic/stomp.api.ai.explain.{requestId}
 *  legacyName: /topic/ai.explain.{requestId} */
export const aiExplain = (requestId: string) =>
  `/topic/stomp.api.ai.explain.${requestId}`

// ──────────────────────────────────────────────────────────────
// Job — candle recollect 진행률
// ──────────────────────────────────────────────────────────────

/**
 * 캔들 재수집 잡 진행률 — candle recollect job service.
 *
 * ⚠ 현행 source에서 이 destination은 백엔드 응답(job.topic)을 그대로 구독하며
 *   클라이언트가 직접 문자열을 조립하지 않는다(passthrough). 빌더는 인벤토리 정합·향후
 *   사용을 위해 정의만 갱신한다. (jobId는 backend가 `job-{n}` 형태로 부여)
 *
 * current: /topic/stomp.api.job.candle-recollect.{code}.{jobId}
 * legacyName: /topic/candle.recollect.{code}.{jobId}
 */
export const candleRecollectJob = (code: string, jobId: string) =>
  `/topic/stomp.api.job.candle-recollect.${code}.${jobId}`
