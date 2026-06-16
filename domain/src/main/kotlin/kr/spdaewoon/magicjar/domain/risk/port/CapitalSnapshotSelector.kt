package kr.spdaewoon.magicjar.domain.risk.port

/**
 * 평가 대상 전략의 출처에 따라 **실 자본 vs 가상(페이퍼) 자본** provider를 선택하는 계약
 * (ADR 0122 §2-C + spec §3-R1 "선택 분기 LOCK"). 순수 도메인 — Spring·enum 직접 의존 0.
 *
 * ### 왜 strategy 도메인의 `StrategyOrigin` enum을 직접 의존하지 않는가
 *
 * spec §4 R1은 strategy-engine(`Strategy.origin` + `StrategyOrigin` enum)과 **add-only 병렬**이다.
 * backend-core가 `StrategyOrigin` enum을 직접 import 하면 동시 작업 중인 strategy 도메인 파일에
 * 컴파일 결합이 생긴다(병렬 충돌). 따라서 본 selector는 origin enum 대신 **`isPaper: Boolean` 술어**로
 * 분기를 표현한다 — "페이퍼 전략인가"의 판정(`strategy.origin == PAPER_DISCOVERY`)은 호출부(R2 배선)가
 * 수행해 그 결과 boolean만 주입한다. enum 결합 0 · add-only 병렬 안전.
 *
 * ### 선택 분기 시그니처 (R1 = 계약/시그니처만, 실 배선은 R2)
 *
 * 기존 `SignalGeneratedKafkaListener`의 capital 산출(`capitalSnapshotProvider.getCapital(userId)`)이,
 * 평가 대상 전략이 페이퍼면 [PaperCapitalSnapshotProvider], 아니면 실 [CapitalSnapshotProvider]를 쓰도록
 * 한 지점에서 선택을 캡슐화한다. R1은 이 계약 + 함수형 구현([forStrategy])을 제공하고, 실제 listener
 * 배선·빈 등록·예외 fallback 정책은 R2가 [forStrategy]를 호출하는 형태로 연결한다.
 *
 * ### fail-fast 계약 (헌법 §11 silent fallback 금지)
 *
 * 페이퍼 전략인데 [PaperCapitalSnapshotProvider]가 미배선(null)이면 **실 provider로 조용히 폴백 금지**
 * (실 잔고 오염 — spec §1-3). 이 경우 [forStrategy]는 [PaperCapitalProviderUnavailableException]을 던져
 * 호출부가 페이퍼 경로 fail-fast(가시화 후 차단/스킵)로 다루게 한다. 비페이퍼(실) 전략은 항상 실
 * provider를 쓰므로 본 selector 도입으로 인한 회귀 0(default isPaper=false 경로 = 기존 동작 동일).
 *
 * // AI 호출 0 — 페이퍼는 룰 기반 시그널 경로 100% (ADR 0122 §4-1 + development-rules §5)
 */
fun interface CapitalSnapshotSelector {
    /**
     * 평가 대상 전략의 [isPaper] 술어에 따라 자본 산출 **결과**([CapitalOutcome])를 반환한다.
     *
     * F10 hotfix(2026-06-12) — 반환 타입을 [BigDecimal] → [CapitalOutcome] 로 격상. 실 KIS 잔고조회는
     * 실패(rate limit 등) 시 [CapitalOutcome.Unavailable] 로 "이번 시그널 skip"을 호출부에 전달한다
     * (0 fabricate 금지). 페이퍼는 가상 자본이라 항상 [CapitalOutcome.Available].
     *
     * @param userId 평가 대상 사용자 식별자(실/페이퍼 동일 키 — 페이퍼는 가상 자본 seed 키로 재사용).
     * @param isPaper `true`면 페이퍼(`origin=PAPER_DISCOVERY`) 전략 — 가상 자본. `false`면 실 KIS 잔고.
     * @return 선택된 provider의 자본 산출 결과(값 또는 조회 불가). 음수 방지 책임은 각 provider 구현에 위임.
     */
    fun capitalFor(
        userId: String,
        isPaper: Boolean,
    ): CapitalOutcome

    companion object {
        /**
         * 실/페이퍼 provider를 묶어 selector를 합성한다(R2 배선 진입점).
         *
         * - [paperProvider]가 null이면 비페이퍼 전용 환경(페이퍼 비활성 `MAGICJAR_DAYTRADE_PAPER_ENABLED=false`)으로
         *   간주 — 비페이퍼(`isPaper=false`)는 정상 동작하고, 페이퍼(`isPaper=true`) 요청은
         *   [PaperCapitalProviderUnavailableException] fail-fast(헌법 §11 silent fallback 금지).
         * - 비페이퍼 경로는 항상 [realProvider]의 [CapitalSnapshotProvider.capitalOutcome] — F10 재시도·stale·skip 정책 적용.
         * - 페이퍼 경로는 [PaperCapitalSnapshotProvider.getCapital]을 [CapitalOutcome.Available]로 감싼다(가상 자본 = 실패 개념 없음).
         */
        fun forStrategy(
            realProvider: CapitalSnapshotProvider,
            paperProvider: PaperCapitalSnapshotProvider?,
        ): CapitalSnapshotSelector =
            CapitalSnapshotSelector { userId, isPaper ->
                if (isPaper) {
                    val paper = paperProvider ?: throw PaperCapitalProviderUnavailableException(userId)
                    CapitalOutcome.Available(capital = paper.getCapital(userId), stale = false)
                } else {
                    realProvider.capitalOutcome(userId)
                }
            }
    }
}

/**
 * 페이퍼 전략 평가인데 [PaperCapitalSnapshotProvider]가 미배선일 때 던지는 fail-fast 신호
 * (헌법 §11 — 실 자본 provider로의 silent fallback 금지). 호출부(R2)는 이를 페이퍼 경로 차단/스킵으로
 * 다루며 실 KIS 잔고를 오염시키지 않는다(spec §1-3 격리 LOCK).
 */
class PaperCapitalProviderUnavailableException(
    userId: String,
) : IllegalStateException(
        "PaperCapitalSnapshotProvider 미배선 — 페이퍼 전략 가상 자본 산출 불가 (userId=$userId). " +
            "실 CapitalSnapshotProvider로 폴백 금지(실 잔고 오염 위험). R2 페이퍼 배선/빈 합류 확인 필요.",
    )
