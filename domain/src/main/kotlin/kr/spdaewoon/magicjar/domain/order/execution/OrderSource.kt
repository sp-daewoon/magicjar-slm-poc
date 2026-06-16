package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 출처 — 사용자 수동 주문(MANUAL) vs 자동매매 시그널 주문(AUTO) 구분 (ADR 0082 T-MAN B-2-extended LOCK).
 *
 * 사용자 결정 정합 (2026-05-18 최고 관리자): "데이터 상에선 사용자 주문이랑 자동매매주문의 타입이 구분되어야하는거 필수".
 *
 * - AUTO     : 자동매매 path — signal.generated.v1 → T-RISK ALLOWED → order.intent.v1 (ADR 0068 §14-2)
 * - MANUAL   : 사용자 수동 주문 — POST /api/orders/manual → order.intent.v1 (ADR 0082 T-MAN)
 * - EXTERNAL : magicJar 외부(KIS HTS·MTS·타 client)에서 직접 낸 주문을 체결통보/REST backfill이 흡수한 경우.
 *              우리 시스템이 발의하지 않았으므로 AUTO·MANUAL 어느 쪽도 아님 — 감사 명확성 위해 별도 분류
 *              (2026-06-05 자본 path fix · 외부 HTS 주문 흡수 user 매핑·mode·source 오기록 정정).
 *              EXTERNAL 주문은 canary 일일 cap(ADR 0109) 카운트에서 제외 — 우리 자동매매 예산이 아님.
 *
 * 향후 확장 baseline: MCP·CLI·외부 API client 등 (T-MCP — ADR 0027).
 *
 * 데이터 영속:
 *   - Kafka payload `order.intent.v1` "source" 필드 (consumer OrderRouterKafkaListener step 2 deserialize)
 *   - DB `order_request.source` 컬럼 (consumer step 5 INSERT 시 채움 — Flyway V37 + V66 CHECK 확장)
 *   - audit_event payloadJson "source" 마커 (DB 컬럼 + JSON 이중 정합)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082)
 */
enum class OrderSource {
    AUTO,
    MANUAL,
    EXTERNAL,
}
