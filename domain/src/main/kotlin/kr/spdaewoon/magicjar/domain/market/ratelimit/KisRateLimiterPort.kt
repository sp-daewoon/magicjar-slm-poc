package kr.spdaewoon.magicjar.domain.market.ratelimit

/**
 * KIS OpenAPI REST 호출 Rate Limit 강제 port (ADR 0037 + spec PR #564 §2-2 LOCK).
 *
 * 책임:
 *   - KIS REST 어댑터 호출 직전 token 1개 acquire 의무 (multi-JVM 단일 bucket share)
 *   - EGW00201 / HTTP 429 응답 시그널 수집 (Circuit Breaker 통계 누적 — Phase 3 별도)
 *   - 정상 응답 시그널 수집 (fail rate 분모)
 *
 * 정합 ADR:
 *   - ADR 0003 KIS OpenAPI 사용 — 본 port가 호출 baseline
 *   - ADR 0037 Rate Limit 강제 — 본 port가 §2-1 공통 진입점 권위
 *   - ADR 0038 Redis OAuth — Redis backend 공유 인프라
 *   - ADR 0057 KIS 단일 출처 — 본 port 적용 대상 권위
 *
 * raw 출처 (헌법 §3 KIS-first):
 *   - KIS 공식 한도 문서: https://apiportal.koreainvestment.com/intro ("REST API 한도: 초당 10건")
 *   - EGW00201 응답 raw: open-trading-api/examples_llm/ 폴더 다수 위치
 *
 * 의존 방향:
 *   - 본 port는 domain 모듈 LOCK (Spring·JPA·Redis 의존 0 — ADR 0004 §모듈 경계)
 *   - 어댑터 구현은 api 모듈 `RedisKisRateLimiterAdapter` (Phase 1) — Phase 2 multi-JVM 모듈 분리 후속
 *
 * 자본 영향:
 *   - 본 port 미구현 시 KIS 계정·앱키 차단 risk (사용자 자본 흐름 catastrophic)
 *   - Phase 1 단위 테스트로 11번째 호출 throw 강제 검증 의무
 */
interface KisRateLimiterPort {
    /**
     * KIS REST 호출 직전 token 1개 acquire (blocking 패턴).
     *
     * Phase 1 단순화: 모든 priority 동일 LOCK (단일 bucket).
     * Phase 2 우선순위 큐: USER_PATH > INGESTION > BACKFILL (별도 사이클).
     *
     * @param priority 호출 path 우선순위 (Phase 1 정보 수집만, 라우팅 LOCK 없음)
     * @param timeoutMillis acquire 최대 대기 (default 1000ms).
     *                      KIS 한도(10 token/sec) + refill 1s 기준 → 1초 대기로 다음 윈도 진입 가능
     * @param trId KIS HTTP 헤더 `tr_id` (호출 식별 라벨 · ADR 0116 §2-E 연장).
     *             add-only default null — 기존 호출부(2-arg) 무변경 컴파일 LOCK.
     *             어댑터가 acquire 직전 자기 tr_id 상수를 전달하면 `kis_call_total{tr_id}` counter 라벨 부착.
     *             null이면 `unknown` 라벨로 집계 (cardinality: tr_id는 유한 상수 집합이라 안전).
     * @throws KisRateLimitTimeoutException timeout 초과 시 (사용자 path는 명시 에러로 throw,
     *         backfill은 caller가 catch 후 재시도 또는 skip)
     * @throws KisCircuitOpenException Circuit Breaker OPEN 상태 (Phase 3 합류)
     */
    fun acquire(
        priority: KisCallPriority = KisCallPriority.INGESTION,
        timeoutMillis: Long = 1000L,
        trId: String? = null,
    )

    /**
     * EGW00201 / HTTP 429 응답 발생 시그널.
     * adapter 응답 처리 path에서 호출 의무 (Circuit Breaker 통계 — Phase 3 합류).
     *
     * Phase 1: log 누적만 (Circuit Breaker 통계 0 — Phase 3 후속).
     */
    fun onTpsExceeded()

    /**
     * 정상 응답 시그널 — Circuit Breaker fail rate 분모 누적 (Phase 3 합류).
     *
     * Phase 1: log 누적만.
     */
    fun onSuccess()
}
