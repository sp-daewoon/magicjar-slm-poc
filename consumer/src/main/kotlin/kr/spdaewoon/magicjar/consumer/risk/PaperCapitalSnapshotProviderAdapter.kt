package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.domain.risk.port.PaperCapitalSnapshotProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 발굴(Discovery) → MOCK 페이퍼 전략의 **가상 자본** snapshot provider 실어댑터
 * (ADR 0122 §2-C 가상 자본 격리 + spec §3-R1 계약 2 · R2 배선).
 *
 * ### 산출 규약 (가상 자본 잔액)
 * `잔액 = max(0, 초기 default 가상 자본 + 페이퍼 실현손익 누적)`.
 * - 초기 default = ENV `MAGICJAR_DAYTRADE_PAPER_DEFAULT_CAPITAL_KRW` (spec §8-2 결재 = 1천만원).
 * - 페이퍼 실현손익 = 해당 user 의 `origin=PAPER_DISCOVERY` 전략 귀속 strategy_position 의 `realized_pnl` 합
 *   ([StrategyPositionJpaRepository.sumPaperRealizedPnlByUser] — 실 전략 row 제외 join → 실 잔고 오염 0).
 *   부호 그대로 가산(손실이면 음수 → 잔액 감소). 음수 잔액은 가상 파산으로 0 floor.
 *
 * ### 실 잔고 오염 0 (spec §1-3 격리 LOCK)
 * 본 provider 는 **KIS 잔고를 절대 호출하지 않는다** — 실현손익은 페이퍼 전략 귀속 ledger(strategy_position)에서만
 * 읽고, 초기 자본은 상수다. 실 [kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider](KIS 잔고)와
 * 데이터 출처가 완전히 분리된 별 빈 — [CapitalSnapshotSelector] 가 origin 술어로 둘 중 하나를 선택한다.
 *
 * ### fail 분기 (헌법 §11 — silent fallback 금지)
 * 페이퍼 ledger 조회 실패 시 실 잔고로 폴백하지 않는다(실 잔고 오염 위험). 본 provider 는 조회 실패를
 * 상위로 전파해 [SignalGeneratedKafkaListener] 의 buildContext capital fail 분기(MOCK fallback 0 / REAL fail-fast)가
 * 정상 동작하게 한다 — 페이퍼는 MOCK 경로이므로 capital 0 → POSITION_LIMIT 으로 모든 BUY 차단(자본 보호 fail-safe).
 *
 * ### 모듈 경계 정합 (ADR 0004)
 * consumer → domain(`PaperCapitalSnapshotProvider` port) + consumer 내부(strategy_position Repository). api 의존 0.
 *
 * // AI 호출 0 — 페이퍼는 룰 기반 시그널 경로 100% (ADR 0122 §4-1 + development-rules §5)
 */
@Component
class PaperCapitalSnapshotProviderAdapter(
    private val strategyPositionJpaRepository: StrategyPositionJpaRepository,
    @Value("\${magicjar.daytrade.paper.default-capital-krw:10000000}")
    private val defaultCapitalKrw: BigDecimal = BigDecimal("10000000"),
) : PaperCapitalSnapshotProvider {
    private val log = LoggerFactory.getLogger(PaperCapitalSnapshotProviderAdapter::class.java)

    @Transactional(readOnly = true)
    override fun getCapital(userId: String): BigDecimal {
        val realizedPnl = strategyPositionJpaRepository.sumPaperRealizedPnlByUser(userId)
        val balance = (defaultCapitalKrw + realizedPnl).coerceAtLeast(BigDecimal.ZERO)
        log.debug(
            "페이퍼 가상 자본 산출: userId={} default={} realizedPnl={} balance={}",
            userId,
            defaultCapitalKrw,
            realizedPnl,
            balance,
        )
        return balance
    }
}
