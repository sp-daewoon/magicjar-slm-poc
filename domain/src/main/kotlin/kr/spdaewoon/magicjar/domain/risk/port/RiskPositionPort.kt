package kr.spdaewoon.magicjar.domain.risk.port

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.position.Position
import java.math.BigDecimal

/**
 * Risk 평가용 Position 조회 port (ADR 0067 §5-5 + ADR 0042 정합).
 *
 * ADR 0042 [kr.spdaewoon.magicjar.domain.position.port.PositionRepository]는 KIS 잔고 부트스트랩
 * 용 단순 upsert/findBy* port — 본 ADR 0067 risk 평가용 신규 메서드(currentByUser·dailyPnl)는
 * 별도 port로 분리 (헌법 §6 abstract만 + 기존 어댑터 호환 보존).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface RiskPositionPort {
    fun findCurrentByUser(userId: String): List<Position>

    fun countCurrentByUser(userId: String): Int

    fun sumDailyRealizedPnl(
        userId: String,
        kstMidnight: Instant,
    ): BigDecimal
}
