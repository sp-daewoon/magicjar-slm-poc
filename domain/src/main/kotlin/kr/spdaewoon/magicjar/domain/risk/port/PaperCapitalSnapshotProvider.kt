package kr.spdaewoon.magicjar.domain.risk.port

import java.math.BigDecimal

/**
 * 발굴(Discovery) → MOCK 페이퍼 트레이딩 전략의 **가상 자본** snapshot provider
 * (ADR 0122 §2-C 가상 자본 격리 + spec §3-R1 계약 2). 헌법 §6 abstract만.
 *
 * ### 왜 [CapitalSnapshotProvider]와 별 빈인가 (책임 분리)
 *
 * 실 [CapitalSnapshotProvider]는 **KIS 잔고**(ADR 0042 Redis cache `balance:user:{userId}`)를 권위로 한다.
 * 페이퍼 전략(`origin=PAPER_DISCOVERY`)의 RiskGateway 평가가 이 실 provider를 호출하면 실 KIS 잔고
 * 조회·캐시가 페이퍼 시뮬 때문에 오염되고, 거꾸로 페이퍼 가상 자본이 실 리스크 판정에 새어 들어간다.
 *
 * → 페이퍼는 **가상 자본 snapshot**(초기 default 1천만원 − 페이퍼 실현손익 누적, ADR 0122 §8-2)을
 *   별 빈으로 반환한다. 실 잔고·실 리스크 판정 **오염 0**(spec §1-3 격리 LOCK).
 *
 * 시그니처는 [CapitalSnapshotProvider]와 동일(`getCapital(userId): BigDecimal`)하지만 **데이터 출처가 다른**
 * 별개 책임이므로 공통 추상화로 합치지 않는다(별 빈 LOCK).
 *
 * ### fail-fast 계약 (헌법 §11 silent fallback 금지)
 *
 * 본 포트의 어댑터(R2 배선)가 빈으로 합류하지 않은 채 페이퍼 전략이 평가되면, 호출부는 **실 [CapitalSnapshotProvider]로
 * 조용히 폴백해서는 안 된다**(실 잔고 오염 위험). 포트 미배선은 페이퍼 경로의 fail-fast(가시화 후 차단/스킵)로
 * 다룬다 — R2 배선이 이 계약을 구현한다. R1은 포트 + 분기 시그니처([CapitalSnapshotSelector])까지만.
 *
 * ### 가상 자본 잔액 규약
 *
 * 반환값 = `max(0, 초기 가상 자본 − 페이퍼 실현손익 누적)`. 음수 금지(가상 파산 시 0). 실현손익 누적·초기
 * 자본 seed는 R2 배선(Redis `daytrade:paper:capital:<userId>` 또는 페이퍼 ledger)의 책임이며, 본 포트는
 * 그 산출 결과 잔액만 노출한다.
 *
 * // AI 호출 0 — 페이퍼는 룰 기반 시그널 경로 100% (ADR 0122 §4-1 + development-rules §5)
 */
fun interface PaperCapitalSnapshotProvider {
    /** 페이퍼 가상 자본 잔액(초기 default − 페이퍼 실현손익 누적). 음수 방지 `max(0)`. */
    fun getCapital(userId: String): BigDecimal
}
