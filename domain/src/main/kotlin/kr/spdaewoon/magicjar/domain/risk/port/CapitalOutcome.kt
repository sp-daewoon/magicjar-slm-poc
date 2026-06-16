package kr.spdaewoon.magicjar.domain.risk.port

import java.math.BigDecimal

/**
 * 자본(잔고) 산출 결과 — **성공(값) vs 조회 불가(스킵)** 를 타입으로 구분하는 sealed 계약
 * (F10 hotfix · 2026-06-12 최고 관리자 결재 — "0으로 임의값을 만든 건 큰 문제").
 *
 * ### 왜 도입했는가 (14:12 라이브 incident 근본)
 * 이전 [CapitalSnapshotProvider.getCapital]은 KIS 잔고조회가 rate limit(EGW00215/EGW00201) 등으로
 * 실패하면 **`BigDecimal.ZERO`를 fabricate** 해 반환했다. 그 0이 그대로 `POSITION_LIMIT` 룰
 * (총자산 × 비율 = 예산) 평가로 흘러 "총 자산 0원 × 70% = 한도 0원"으로 정상 매수를 차단하고
 * AutoPause 카운트를 오염시켰다. 실계좌엔 자산이 있는데 거짓 사실(0원)로 기회를 잃은 것이다.
 *
 * "0원"은 **사실(실제 잔고가 0)** 일 수도 있고 **조회 실패(알 수 없음)** 일 수도 있는데,
 * 단일 [BigDecimal] 반환은 이 둘을 구분할 수 없다. 그래서 결과를 sealed 로 모델링해
 * 호출부가 "값이 있다"와 "조회 불가 → 이번 시그널 스킵"을 컴파일 타임에 분리하도록 한다.
 *
 * ### 진보 페르소나 (ADR 0087) 설계 사유
 * - ✨ **sealed interface** — caller `when` exhaustive 강제. "조회 불가"를 0으로 뭉개는 경로를 타입이 차단.
 * - 🔄 **0 fabricate 제거** — 어떤 실패에서도 capital=0 으로 룰 평가 진행 금지(결재 지침 §4).
 * - 💡 **stale 표식** — 최근 성공 snapshot(유효 N분) 재사용 시 `stale=true` 로 운영자 가시화.
 *
 * // AI 호출 0 — T-RISK 룰 경로 (ADR 0023 §2-4)
 */
sealed interface CapitalOutcome {
    /**
     * 자본 값 확정. KIS fresh 조회 성공 또는 최근 성공 snapshot(stale-cache) 재사용.
     *
     * @param capital 순자산금액(음수 방지는 provider 책임). 페이퍼는 가상 자본. DAILY_LOSS percent 등 기존 룰 기준값.
     * @param orderableCash 주문가능 예수금(매수 버짓 기준 — 사용자 결재 2026-06-16). KIS 잔고의 `prvs_rcdl_excc_amt`
     *   (D+2 실가용, [kr.spdaewoon.magicjar.domain.account.AccountBalance.cashD2])이 권위, 비정상이면 `dnca_tot_amt`
     *   (예수금총액, cashBalance) fallback. **새 KIS 호출 없이 기존 잔고 snapshot 에서 같이 노출**된다.
     *   null 이면 예수금을 모르는 provider(페이퍼·레거시 default 위임) — 호출부가 [capital] 로 대체(가상 자본=예수금).
     * @param stale `true`면 KIS fresh 조회는 실패했으나 유효 TTL 내 최근 성공값을 재사용(운영 가시화용).
     */
    data class Available(
        val capital: BigDecimal,
        val orderableCash: BigDecimal? = null,
        val stale: Boolean = false,
    ) : CapitalOutcome

    /**
     * 자본 조회 불가 — 재시도·stale-cache 모두 실패. **0 fabricate 금지** → 호출부는 이번 시그널만
     * 조용히 스킵(audit)하고 POSITION_LIMIT 위반·Slack 위반 알람·AutoPause 카운트로 흐르지 않는다(결재 §3).
     *
     * @param reason 진단용 사유 코드(예: `KIS_RATE_LIMIT`·`KIS_LOOKUP_FAILED:<msg>`·`PROVIDER_UNAVAILABLE`).
     */
    data class Unavailable(
        val reason: String,
    ) : CapitalOutcome
}
