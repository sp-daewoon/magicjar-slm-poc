package kr.spdaewoon.magicjar.domain.account.port

import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.user.AccountType

/**
 * KIS 잔고 조회 포트 — 사용자 노출 endpoint 전용 (T-ACCT, ADR 0081 §2-2).
 *
 * 기존 [kr.spdaewoon.magicjar.domain.position.port.KisBalancePort] (Bootstrap 영속 전용 · output1 only)와
 * 책임 분리 — 본 port는 사용자 요청 트리거 + Redis cache + output2 포함 (관심사 분리).
 *
 * 구현체: api 모듈 [kr.spdaewoon.magicjar.api.infrastructure.kis.ApiKisBalanceQueryAdapter]
 * - KIS REST `inquire-balance` 직접 호출 (TR_ID TTTC8434R 실전 / VTTC8434R 모의)
 * - output1 + output2 1회 호출 동시 흡수 (KIS endpoint 추가 0 — ADR 0037 정합)
 *
 * Spring 의존 0 — 순수 도메인 인터페이스.
 *
 * ## KIS-first raw fetch 인용 (헌법 §3 LOCK · spec §3-1)
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py
 */
interface KisBalanceQueryPort {
    /**
     * KIS 잔고 1회 조회.
     *
     * @throws IllegalStateException KIS 자격증명 부재 (HTTP 412 매핑)
     * @throws org.springframework.web.reactive.function.client.WebClientResponseException KIS upstream 실패 (HTTP 502 매핑)
     *
     * mock 합성 0 LOCK (feedback_no_mock_data) — output2 null이면 0 값 + fetchedAt 반환은 reject,
     * 명시적 IllegalStateException 발행 (호출자가 502로 변환 의무).
     */
    fun fetchFullBalance(
        userId: String,
        accountType: AccountType,
    ): KisAccountBalance
}
